package com.roufsyed.onekey.feature.importexport.domain.proton

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.OtpParams
import com.roufsyed.onekey.core.domain.model.OtpType
import com.roufsyed.onekey.core.domain.notes.notesNormalize
import com.roufsyed.onekey.feature.importexport.domain.FailedEntry
import com.roufsyed.onekey.feature.importexport.domain.ParsedImport
import com.roufsyed.onekey.feature.twofa.domain.OtpAuthUriParser
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Reads the plain-text export of Proton Authenticator. The shape is fixed by
 * Proton's open-source core (proton-pass-common, `proton-authenticator/src/entry/exporter.rs`):
 *
 * ```
 * { "version": 1,
 *   "entries": [
 *     { "id": "...",
 *       "content": { "uri": "otpauth://totp/...", "entry_type": "Totp" | "Steam", "name": "..." },
 *       "note": "..." | null } ] }
 * ```
 *
 * The password-protected variant is `{ "version": 1, "salt": "...", "content": "..." }`
 * and is recognised only to tell the user to export again without a password.
 *
 * Every entry becomes a standalone 2FA account, the same Login shape the in-app
 * QR scanner saves: title from the entry name, username from the account part
 * of the otpauth label, the note as notes, no password. An entry that cannot be
 * read (unknown type, unreadable URI, missing content) is reported on its own
 * row and the rest of the file still imports, as Proton's own importer does.
 */
object ProtonAuthenticatorExport {

    private const val KEY_VERSION = "version"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_SALT = "salt"
    private const val KEY_CONTENT = "content"
    private const val KEY_URI = "uri"
    private const val KEY_ENTRY_TYPE = "entry_type"
    private const val KEY_NAME = "name"
    private const val KEY_NOTE = "note"
    private const val TYPE_TOTP = "Totp"
    private const val TYPE_STEAM = "Steam"
    private const val STEAM_SCHEME = "steam://"
    private const val SUPPORTED_VERSION = 1

    private const val NOT_AN_EXPORT = "Not a Proton Authenticator export."
    private const val PASSWORD_PROTECTED =
        "This Proton Authenticator export is password-protected. In Proton Authenticator open Settings, " +
            "tap Export, choose \"Export data without password\", then import that file."

    /**
     * True for a plain export (object with a numeric version and an entries array
     * of objects that carry a content object) and for the password-protected
     * shape (version, salt, content), so both route here and the second one gets
     * a precise message instead of the generic rejection.
     */
    fun looksLike(root: JsonElement): Boolean {
        if (!root.isJsonObject) return false
        val o = root.asJsonObject
        val version = o.get(KEY_VERSION)
        if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber) return false
        val entries = o.get(KEY_ENTRIES)
        if (entries != null && entries.isJsonArray) {
            return entries.asJsonArray.all { it.isJsonObject && it.asJsonObject.get(KEY_CONTENT)?.isJsonObject == true }
        }
        return isEncryptedShape(o)
    }

    fun parse(content: String, now: Long = System.currentTimeMillis()): ParsedImport {
        val root = runCatching { JsonParser.parseString(content) }.getOrElse { throw IllegalArgumentException(NOT_AN_EXPORT) }
        require(root.isJsonObject) { NOT_AN_EXPORT }
        val o = root.asJsonObject
        if (isEncryptedShape(o) && !o.has(KEY_ENTRIES)) throw IllegalArgumentException(PASSWORD_PROTECTED)

        val version = o.get(KEY_VERSION)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?: throw IllegalArgumentException(NOT_AN_EXPORT)
        require(version == SUPPORTED_VERSION) {
            "Proton Authenticator export version $version is not supported yet (1Key reads version $SUPPORTED_VERSION)."
        }
        val entries = o.get(KEY_ENTRIES)?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException(NOT_AN_EXPORT)

        val credentials = ArrayList<Credential>(entries.size())
        val failed = ArrayList<FailedEntry>()
        entries.forEachIndexed { index, element ->
            val rowIndex = index + 1
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject
            val label = entry?.get(KEY_CONTENT)?.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull(KEY_NAME)
                ?.takeIf { it.isNotBlank() } ?: "Entry $rowIndex"
            runCatching { toCredential(entry ?: throw IllegalArgumentException("not an entry"), now) }
                .onSuccess { credentials += it }
                .onFailure { failed += FailedEntry(rowIndex = rowIndex, reason = "$label: ${it.message ?: "unreadable entry"}") }
        }
        return ParsedImport(credentials, failed)
    }

    private fun isEncryptedShape(o: JsonObject): Boolean =
        o.get(KEY_SALT)?.isJsonPrimitive == true && o.get(KEY_CONTENT)?.isJsonPrimitive == true

    private fun toCredential(entry: JsonObject, now: Long): Credential {
        val content = entry.get(KEY_CONTENT)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("no content")
        val type = content.stringOrNull(KEY_ENTRY_TYPE) ?: throw IllegalArgumentException("no entry type")
        val uri = content.stringOrNull(KEY_URI)?.trim().orEmpty()
        val name = content.stringOrNull(KEY_NAME)?.trim()?.takeIf { it.isNotEmpty() }

        val (params, title, username) = when (type) {
            TYPE_TOTP -> {
                val parsed = OtpAuthUriParser.parse(uri) ?: throw IllegalArgumentException("unreadable otpauth URI")
                Triple(
                    parsed.params,
                    name ?: parsed.issuer.ifBlank { parsed.account }.ifBlank { "2FA account" },
                    parsed.account,
                )
            }
            TYPE_STEAM -> Triple(
                OtpParams(type = OtpType.STEAM, secret = steamSecret(uri)),
                name ?: "Steam",
                "",
            )
            else -> throw IllegalArgumentException("unsupported type \"$type\"")
        }

        return Credential(
            id = UUID.randomUUID().toString(),
            title = title,
            username = username,
            password = "",
            url = "",
            notes = notesNormalize(entry.stringOrNull(KEY_NOTE).orEmpty()),
            otpParams = params,
            tags = emptyList(),
            customFields = emptyList(),
            createdAt = now,
            updatedAt = now,
            type = CredentialType.LOGIN,
            importedAt = now,
        )
    }

    /**
     * Proton writes `steam://SECRET`, with the secret in base32 when it arrived
     * as base32 and in unpadded base64 otherwise (Steam's own shared secrets are
     * base64). 1Key keeps every secret as canonical base32, so a base64 secret
     * is decoded and re-encoded. Base32 is tried first, as Proton does, so a
     * string valid in both alphabets is read the same way on both sides.
     */
    private fun steamSecret(uri: String): String {
        require(uri.length > STEAM_SCHEME.length && uri.regionMatches(0, STEAM_SCHEME, 0, STEAM_SCHEME.length, ignoreCase = true)) {
            "unreadable Steam secret"
        }
        val raw = uri.substring(STEAM_SCHEME.length).substringBefore('?').substringBefore('#')
            .filterNot { it.isWhitespace() }.trimEnd('=')
        require(raw.isNotEmpty()) { "unreadable Steam secret" }
        base32DecodeStrict(raw)?.let { return raw.uppercase() }
        val bytes = runCatching { Base64.getDecoder().decode(raw) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("unreadable Steam secret")
        return base32Encode(bytes)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 base32 without padding; null for anything outside the alphabet or with an impossible length. */
    private fun base32DecodeStrict(text: String): ByteArray? {
        val s = text.uppercase()
        if (s.any { it !in BASE32_ALPHABET }) return null
        if (s.length % 8 == 1 || s.length % 8 == 3 || s.length % 8 == 6) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in s) {
            buffer = (buffer shl 5) or BASE32_ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun base32Encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(BASE32_ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}
