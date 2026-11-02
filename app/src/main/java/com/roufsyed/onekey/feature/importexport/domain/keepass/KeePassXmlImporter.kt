package com.roufsyed.onekey.feature.importexport.domain.keepass

import com.roufsyed.onekey.core.domain.card.FieldLabelCatalog
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.CustomField
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.notes.notesNormalize
import com.roufsyed.onekey.core.domain.webauthn.PasskeyAlgorithm
import com.roufsyed.onekey.core.domain.webauthn.Pkcs8Pem
import com.roufsyed.onekey.feature.importexport.domain.FailedEntry
import com.roufsyed.onekey.feature.importexport.domain.ParsedImport
import com.roufsyed.onekey.feature.importexport.domain.TimestampParser
import com.roufsyed.onekey.feature.importexport.domain.UrlTitleExtractor
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the unencrypted XML a KeePass database exports to (KeePassXC:
 * Database, Export, XML file; the same shape KeePass 2.x writes). Every entry
 * arrives with its custom fields, its one-time-password setup and its passkey,
 * which the CSV route cannot carry (KEEPASS_IMPORT_PLAN sections 2 and 3).
 *
 * Format facts this relies on, verified against KeePassXC's `KdbxXmlWriter`:
 * protected values are written in plaintext with `ProtectInMemory="True"`; a
 * `Protected="True"` value is inner-stream ciphertext that no importer can
 * read, so such a file is refused whole. Dates are ISO 8601 for KDBX 3
 * databases and base64 little-endian Int64 seconds since 0001-01-01 UTC for
 * KDBX 4. History entries and attachments are ignored; the recycle bin is
 * skipped.
 *
 * Hardening: the file is capped at [MAX_BYTES], a DOCTYPE is rejected before
 * parsing, and the DOM parser runs with external entities, XInclude and entity
 * expansion disabled wherever the platform parser supports the switch.
 */
internal object KeePassXmlImporter {
    const val MAX_BYTES = 64L * 1024 * 1024

    /** Seconds between 0001-01-01T00:00:00Z and 1970-01-01T00:00:00Z, KeePass's KDBX 4 date origin. */
    private const val EPOCH_OFFSET_SECONDS = 62_135_596_800L
    private const val NULL_UUID = "AAAAAAAAAAAAAAAAAAAAAA=="
    private const val ROOT_ELEMENT = "KeePassFile"

    private const val KEY_TITLE = "Title"
    private const val KEY_USERNAME = "UserName"
    private const val KEY_PASSWORD = "Password"
    private const val KEY_URL = "URL"
    private const val KEY_NOTES = "Notes"
    private val STANDARD_KEYS = setOf(KEY_TITLE, KEY_USERNAME, KEY_PASSWORD, KEY_URL, KEY_NOTES)

    private const val KPEX_RELYING_PARTY = "KPEX_PASSKEY_RELYING_PARTY"
    private const val KPEX_USERNAME = "KPEX_PASSKEY_USERNAME"
    private const val KPEX_CREDENTIAL_ID = "KPEX_PASSKEY_CREDENTIAL_ID"
    private const val KPEX_USER_HANDLE = "KPEX_PASSKEY_USER_HANDLE"
    private const val KPEX_PRIVATE_KEY_PEM = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
    /** StrongBox compatibility names KeePassXC also reads. */
    private const val KPEX_GENERATED_USER_ID = "KPEX_PASSKEY_GENERATED_USER_ID"
    private const val KPXC_USERNAME = "KPXC_PASSKEY_USERNAME"
    private val PASSKEY_KEY_PREFIXES = listOf("KPEX_PASSKEY", "KPXC_PASSKEY")

    private val PASSKEY_TITLE_SUFFIX = Regex("""\s*\(passkey\)\s*$""", RegexOption.IGNORE_CASE)
    private val TAG_SEPARATORS = Regex("""[,;\t]""")
    private val DOCTYPE = Regex("""<!DOCTYPE""", RegexOption.IGNORE_CASE)

    /** One String element of an entry, with the protection flags the writer put on it. */
    private class Attribute(val key: String, val value: String, val protectInMemory: Boolean, val ciphertext: Boolean)

    fun parse(file: File, now: Long = System.currentTimeMillis()): ParsedImport {
        require(file.length() <= MAX_BYTES) { "This XML file is larger than 64 MB, which is more than 1Key will read in one import." }
        return parse(file.readText(Charsets.UTF_8), now)
    }

    fun parse(xml: String, now: Long = System.currentTimeMillis()): ParsedImport {
        val text = xml.removePrefix("﻿")
        require(!DOCTYPE.containsMatchIn(text)) { "XML files that declare a DOCTYPE are not accepted." }
        val document = runCatching { newDocumentBuilder().parse(InputSource(StringReader(text))) }
            .getOrElse { throw IllegalArgumentException("Not a valid KeePass XML export.") }
        val root = document.documentElement
        require(root != null && root.tagName == ROOT_ELEMENT) { "Not a KeePass XML export. In KeePassXC use Database, Export, XML file." }

        val recycleBinUuid = root.child("Meta")?.childText("RecycleBinUUID")?.trim()?.takeIf { it.isNotEmpty() && it != NULL_UUID }
        val state = ParseState(now)
        root.child("Root")?.children("Group")?.forEach { group -> walkGroup(group, isRoot = true, inBin = false, recycleBinUuid, state) }
        if (state.sawCiphertext) {
            throw IllegalArgumentException(
                "This file keeps its secrets encrypted, so passwords and passkeys cannot be read. " +
                    "In KeePassXC use Database, Export, XML file, which writes an unencrypted export.",
            )
        }
        return ParsedImport(state.credentials, state.failed)
    }

    private class ParseState(val now: Long) {
        val credentials = ArrayList<Credential>()
        val failed = ArrayList<FailedEntry>()
        var sawCiphertext = false
        var entryIndex = 0
    }

    private fun walkGroup(group: Element, isRoot: Boolean, inBin: Boolean, recycleBinUuid: String?, state: ParseState) {
        val uuid = group.childText("UUID")?.trim()
        val thisIsBin = inBin || (recycleBinUuid != null && uuid == recycleBinUuid)
        val groupName = group.childText("Name")?.trim().orEmpty()
        if (!thisIsBin) {
            group.children("Entry").forEach { entry ->
                state.entryIndex++
                runCatching { toCredential(entry, groupTag = if (isRoot) null else groupName.takeIf { it.isNotEmpty() }, state) }
                    .onSuccess { it?.let(state.credentials::add) }
                    .onFailure { state.failed += FailedEntry(rowIndex = state.entryIndex, reason = it.message ?: "Unreadable entry") }
            }
        }
        group.children("Group").forEach { child -> walkGroup(child, isRoot = false, inBin = thisIsBin, recycleBinUuid, state) }
    }

    /** Returns null only when the entry is nothing but ciphertext, which [ParseState.sawCiphertext] reports once at the end. */
    private fun toCredential(entry: Element, groupTag: String?, state: ParseState): Credential? {
        val attributes = entry.children("String").mapNotNull { string ->
            val key = string.childText("Key")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val valueElement = string.child("Value")
            val value = valueElement?.textContent.orEmpty()
            val ciphertext = valueElement?.getAttribute("Protected").equals("True", ignoreCase = true) && value.isNotEmpty()
            val protectInMemory = valueElement?.getAttribute("ProtectInMemory").equals("True", ignoreCase = true)
            Attribute(key, value, protectInMemory, ciphertext)
        }
        if (attributes.any { it.ciphertext }) {
            state.sawCiphertext = true
            return null
        }
        val byKey = attributes.associate { it.key to it.value }
        val hasPasskey = byKey.keys.any { key -> PASSKEY_KEY_PREFIXES.any { key.startsWith(it) } }

        val rawTitle = byKey[KEY_TITLE]?.trim().orEmpty()
        val url = byKey[KEY_URL]?.trim().orEmpty().ifEmpty { if (hasPasskey) byKey[KPEX_RELYING_PARTY]?.trim()?.lowercase()?.let { "https://$it" }.orEmpty() else "" }
        val title = (if (hasPasskey) rawTitle.replace(PASSKEY_TITLE_SUFFIX, "") else rawTitle)
            .ifBlank { UrlTitleExtractor.extractTitle(url)?.takeIf { it.isNotBlank() } ?: "Untitled" }
        val username = byKey[KEY_USERNAME]?.trim().orEmpty()
            .ifEmpty { if (hasPasskey) (byKey[KPXC_USERNAME] ?: byKey[KPEX_USERNAME])?.trim().orEmpty() else "" }

        val times = entry.child("Times")
        val createdAt = times?.childText("CreationTime")?.let(::parseTime) ?: state.now
        val updatedAt = times?.childText("LastModificationTime")?.let(::parseTime) ?: createdAt
        val accessedAt = times?.childText("LastAccessTime")?.let(::parseTime)

        val tags = LinkedHashSet<String>()
        entry.childText("Tags")?.split(TAG_SEPARATORS)?.map { it.trim() }?.filter { it.isNotEmpty() }?.let(tags::addAll)
        groupTag?.let(tags::add)

        val customFields = attributes
            .filterNot { it.key in STANDARD_KEYS || it.key in KeePassOtp.ATTRIBUTE_KEYS || PASSKEY_KEY_PREFIXES.any { p -> it.key.startsWith(p) } }
            .filter { it.value.isNotBlank() }
            .map { CustomField(key = it.key, value = it.value, isSensitive = it.protectInMemory || FieldLabelCatalog.sensitiveByDefault(it.key)) }
            .take(CustomField.MAX_FIELDS)

        val id = UUID.randomUUID().toString()
        val passkeys = if (!hasPasskey) emptyList() else {
            runCatching { toPasskey(byKey, credentialId = id, rpName = title, fallbackUserName = username, createdAt = createdAt, now = state.now) }
                .onFailure { state.failed += FailedEntry(rowIndex = state.entryIndex, reason = "$title: passkey skipped, ${it.message}") }
                .getOrNull()?.let(::listOf).orEmpty()
        }

        return Credential(
            id = id,
            title = title,
            username = username,
            password = byKey[KEY_PASSWORD].orEmpty(),
            url = url,
            notes = notesNormalize(byKey[KEY_NOTES].orEmpty()),
            otpParams = KeePassOtp.fromAttributes(byKey),
            tags = tags.toList(),
            customFields = customFields,
            createdAt = createdAt,
            updatedAt = updatedAt,
            type = CredentialType.LOGIN,
            accessedAt = accessedAt,
            importedAt = state.now,
            passkeys = passkeys,
        )
    }

    private fun toPasskey(byKey: Map<String, String>, credentialId: String, rpName: String, fallbackUserName: String, createdAt: Long, now: Long): Passkey {
        val rpId = byKey[KPEX_RELYING_PARTY]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("no relying party")
        val pem = byKey[KPEX_PRIVATE_KEY_PEM] ?: throw IllegalArgumentException("no private key")
        val privateKey = runCatching { Pkcs8Pem.decode(pem) }.getOrElse { throw IllegalArgumentException("private key is not PEM") }
        requireNotNull(PasskeyAlgorithm.ofPkcs8(privateKey)) { "key type is not ES256, Ed25519 or RS256" }
        val webauthnId = LenientBase64.decode(byKey[KPEX_CREDENTIAL_ID] ?: byKey[KPEX_GENERATED_USER_ID])?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("no credential id")
        val userHandle = LenientBase64.decode(byKey[KPEX_USER_HANDLE])?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("no user handle")
        val userName = (byKey[KPXC_USERNAME] ?: byKey[KPEX_USERNAME])?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackUserName
        return Passkey(
            id = "",
            credentialId = credentialId,
            rpId = rpId,
            rpName = rpName.ifBlank { rpId },
            userName = userName,
            userDisplayName = "",
            userHandle = userHandle,
            webauthnCredentialId = webauthnId,
            privateKeyPkcs8 = privateKey,
            prfSecret = null,
            origin = PasskeyOrigin.Browser("https://$rpId"),
            createdAt = createdAt,
            lastUsedAt = null,
            // A copy lives in the KeePass database, so the backup-state flag is honest as true.
            backedUpAt = now,
        )
    }

    /** ISO 8601 (KDBX 3 exports) or base64 little-endian Int64 seconds since 0001-01-01 UTC (KDBX 4). */
    internal fun parseTime(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.contains('-') || text.contains(':')) return TimestampParser.parseToEpochMillis(text)
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        if (bytes.size != 8) return null
        val seconds = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long - EPOCH_OFFSET_SECONDS
        // Reject the KeePass sentinel dates and anything outside a sane range (year 1970 to 9999).
        if (seconds < 0L || seconds > 253_402_300_799L) return null
        return seconds * 1000L
    }

    private fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            for ((feature, enabled) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            )) runCatching { setFeature(feature, enabled) }
        }
        return factory.newDocumentBuilder().apply {
            // Belt and braces: even if a DTD slipped through, resolve nothing.
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        val out = ArrayList<Element>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == name) out += node
        }
        return out
    }

    private fun Element.childText(name: String): String? = child(name)?.textContent
}
