package com.roufsyed.onekey.feature.importexport.domain.keepass

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.Passkey
import com.roufsyed.onekey.core.domain.model.PasskeyOrigin
import com.roufsyed.onekey.core.domain.webauthn.PasskeyAlgorithm
import com.roufsyed.onekey.core.domain.webauthn.Pkcs8Pem
import com.roufsyed.onekey.feature.importexport.domain.FailedEntry
import com.roufsyed.onekey.feature.importexport.domain.ParsedImport
import com.roufsyed.onekey.feature.importexport.domain.UrlTitleExtractor
import java.util.UUID

/**
 * KeePassXC's per-passkey export (`.passkey`): one JSON object with exactly the
 * keys `relyingParty`, `url`, `username`, `credentialId`, `userHandle` and
 * `privateKey` (PKCS#8 PEM), all required by KeePassXC's own importer. A root
 * array of such objects is accepted too, for users who concatenate files.
 *
 * Each object becomes one LOGIN credential with no password and one passkey,
 * created as a browser passkey for the relying party. The import pipeline then
 * merges it into an existing entry with the same title and username, or adds a
 * new one (KEEPASS_IMPORT_PLAN section 3, K12).
 */
internal object KeePassXcPasskeyFile {
    private const val KEY_RELYING_PARTY = "relyingParty"
    private const val KEY_URL = "url"
    private const val KEY_USERNAME = "username"
    private const val KEY_CREDENTIAL_ID = "credentialId"
    private const val KEY_USER_HANDLE = "userHandle"
    private const val KEY_PRIVATE_KEY = "privateKey"

    /** True when [root] is a `.passkey` object, or an array made only of them. Used by format detection. */
    fun looksLike(root: JsonElement): Boolean = when {
        root.isJsonObject -> isPasskeyObject(root.asJsonObject)
        root.isJsonArray -> root.asJsonArray.size() > 0 && root.asJsonArray.all { it.isJsonObject && isPasskeyObject(it.asJsonObject) }
        else -> false
    }

    private fun isPasskeyObject(o: JsonObject): Boolean = o.has(KEY_PRIVATE_KEY) && o.has(KEY_RELYING_PARTY)

    fun parse(content: String, now: Long = System.currentTimeMillis()): ParsedImport {
        val root = runCatching { JsonParser.parseString(content) }.getOrElse { throw IllegalArgumentException("Not a valid .passkey file.") }
        val objects: List<JsonObject> = when {
            root.isJsonObject -> listOf(root.asJsonObject)
            root.isJsonArray -> root.asJsonArray.map { it.takeIf { e -> e.isJsonObject }?.asJsonObject ?: throw IllegalArgumentException("Not a valid .passkey file.") }
            else -> throw IllegalArgumentException("Not a valid .passkey file.")
        }
        val credentials = ArrayList<Credential>(objects.size)
        val failed = ArrayList<FailedEntry>()
        objects.forEachIndexed { index, o ->
            runCatching { toCredential(o, now) }
                .onSuccess { credentials += it }
                .onFailure { failed += FailedEntry(rowIndex = index + 1, reason = it.message ?: "Unreadable passkey") }
        }
        return ParsedImport(credentials, failed)
    }

    private fun toCredential(o: JsonObject, now: Long): Credential {
        fun str(key: String): String? = o.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val rpId = str(KEY_RELYING_PARTY)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("relyingParty missing")
        val url = str(KEY_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: "https://$rpId"
        val username = str(KEY_USERNAME)?.trim().orEmpty()
        val credentialId = LenientBase64.decode(str(KEY_CREDENTIAL_ID))?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("credentialId missing or not base64")
        val userHandle = LenientBase64.decode(str(KEY_USER_HANDLE))?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("userHandle missing or not base64")
        val pem = str(KEY_PRIVATE_KEY) ?: throw IllegalArgumentException("privateKey missing")
        val privateKey = runCatching { Pkcs8Pem.decode(pem) }.getOrElse { throw IllegalArgumentException("privateKey is not a PEM key") }
        requireNotNull(PasskeyAlgorithm.ofPkcs8(privateKey)) { "privateKey is not an ES256, Ed25519 or RS256 key" }

        val title = UrlTitleExtractor.extractTitle(url)?.takeIf { it.isNotBlank() } ?: rpId
        val id = UUID.randomUUID().toString()
        val passkey = Passkey(
            id = "",
            credentialId = id,
            rpId = rpId,
            rpName = title,
            userName = username,
            userDisplayName = "",
            userHandle = userHandle,
            webauthnCredentialId = credentialId,
            privateKeyPkcs8 = privateKey,
            prfSecret = null,
            origin = PasskeyOrigin.Browser("https://$rpId"),
            createdAt = now,
            lastUsedAt = null,
            // A copy of this key exists outside 1Key, so the backup-state flag is honest as true.
            backedUpAt = now,
        )
        return Credential(
            id = id,
            title = title,
            username = username,
            password = "",
            url = url,
            notes = "",
            otpParams = null,
            tags = listOf(CredentialType.LOGIN.displayName),
            customFields = emptyList(),
            createdAt = now,
            updatedAt = now,
            type = CredentialType.LOGIN,
            importedAt = now,
            passkeys = listOf(passkey),
        )
    }
}
