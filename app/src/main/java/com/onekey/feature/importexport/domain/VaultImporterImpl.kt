package com.onekey.feature.importexport.domain

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opencsv.CSVReader
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.domain.model.CustomField
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.security.CryptoManager
import com.onekey.feature.twofa.domain.OtpAuthUriParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.io.Reader
import java.util.UUID
import javax.inject.Inject

class VaultImporterImpl @Inject constructor(
    private val crypto: CryptoManager,
) : VaultImporter {

    private val gson = Gson()

    override suspend fun isEncrypted(path: String): Boolean = BackupEncryption.isEncrypted(path)

    override suspend fun parse(path: String): AppResult<ParsedImport> =
        runCatchingResult {
            when (detectFormat(path)) {
                ExportFormat.JSON -> parseJsonContent(File(path).readText())
                ExportFormat.CSV -> parseCsvReader(FileReader(path))
            }
        }

    private fun detectFormat(path: String): ExportFormat {
        File(path).bufferedReader().use { reader ->
            var c = reader.read()
            while (c != -1 && c.toChar().isWhitespace()) c = reader.read()
            return if (c.toChar() == '[' || c.toChar() == '{') ExportFormat.JSON else ExportFormat.CSV
        }
    }

    override suspend fun parseEncrypted(path: String, password: CharArray): AppResult<ParsedImport> =
        runCatching {
            val fileBytes = File(path).readBytes()
            val (plaintext, format) = BackupEncryption.decrypt(fileBytes, password, crypto)
            when (format) {
                ExportFormat.JSON -> parseJsonContent(plaintext.toString(Charsets.UTF_8))
                ExportFormat.CSV -> parseCsvReader(InputStreamReader(ByteArrayInputStream(plaintext)))
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { e ->
                val msg = when {
                    e is javax.crypto.AEADBadTagException -> "Wrong password or corrupted backup"
                    e.message?.startsWith("Not a 1Key") == true -> "This is not a 1Key encrypted backup"
                    e.message?.startsWith("Unsupported backup") == true -> e.message!!
                    else -> e.message ?: "Failed to decrypt backup"
                }
                AppResult.Error(IllegalStateException(msg), msg)
            }
        )

    // ── JSON ─────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonContent(content: String): ParsedImport {
        require(content.trimStart().startsWith('[')) {
            "Not a valid credential file. Make sure this is a 1Key backup or a valid csv."
        }
        val now = System.currentTimeMillis()
        val failed = mutableListOf<FailedEntry>()
        val credentials = mutableListOf<Credential>()

        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        val list: List<Map<String, Any>> = gson.fromJson(content, type)

        list.forEachIndexed { index, map ->
            runCatching {
                // Translate foreign-tool keys (Firefox `timeCreated`, Bitwarden
                // `revisionDate`, generic `createdAt`, …) into our canonical
                // snake_case names so the rest of the parser doesn't need to
                // know about every tool's flavour. Canonical wins on collision:
                // if a source carries both `created_at` AND `timeCreated`, we
                // keep the explicit canonical one. Then drop known-noise keys
                // so they don't pollute the credential's custom-fields list.
                val normalized: MutableMap<String, Any> = map.toMutableMap()
                for ((alias, canonical) in JSON_KEY_ALIASES) {
                    val value = normalized.remove(alias) ?: continue
                    normalized.putIfAbsent(canonical, value)
                }
                JSON_KEY_IGNORED.forEach { normalized.remove(it) }

                val knownCustomFields = parseCustomFields(normalized["custom_fields"])
                val unknownCustomFields = normalized.entries
                    .filter { (k, v) ->
                        k !in KNOWN_JSON_KEYS && (v is String || v is Number || v is Boolean)
                    }
                    .map { (k, v) ->
                        CustomField(
                            key = CustomFieldKeyFormatter.prettify(k),
                            value = v.toString(),
                            isSensitive = false,
                        )
                    }

                val allCustomFields = (knownCustomFields + unknownCustomFields).take(CustomField.MAX_FIELDS)

                credentials.add(
                    Credential(
                        id = (normalized["id"] as? String) ?: UUID.randomUUID().toString(),
                        title = normalized["title"] as? String ?: "",
                        username = normalized["username"] as? String ?: "",
                        password = normalized["password"] as? String ?: "",
                        url = normalized["url"] as? String ?: "",
                        notes = normalized["notes"] as? String ?: "",
                        otpParams = parseImportedOtp(normalized["totp_secret"] as? String),
                        tags = (normalized["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        isFavorite = (normalized["is_favorite"] as? Boolean)
                            ?: (normalized["favorite"] as? Boolean) ?: false,
                        customFields = allCustomFields,
                        // Foreign exports may encode timestamps as ISO strings, RFC dates,
                        // or epoch sec/μs/ns rather than the ms Long we write — TimestampParser
                        // normalises every shape. Unrecognised input → fall through to `now`.
                        createdAt = TimestampParser.parseToEpochMillis(normalized["created_at"]) ?: now,
                        updatedAt = TimestampParser.parseToEpochMillis(normalized["updated_at"]) ?: now,
                        // Forward-compat: missing `type` (older exports, third-party files)
                        // becomes LOGIN, matching the migration default for legacy rows.
                        type = CredentialType.fromNameOrDefault(normalized["type"] as? String),
                        // Round-trip the recycle-bin marker so a backup→restore preserves
                        // bin state. Older exports without the field stay active (null).
                        deletedAt = TimestampParser.parseToEpochMillis(normalized["deleted_at"]),
                        // Optional — null when the source export didn't carry a
                        // last-used timestamp (e.g. our own pre-DB-v10 backups).
                        accessedAt = TimestampParser.parseToEpochMillis(normalized["accessed_at"]),
                    )
                )
            }.onFailure { e ->
                failed.add(FailedEntry(rowIndex = index + 1, reason = e.message ?: "Unknown error"))
            }
        }

        return ParsedImport(credentials, failed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCustomFields(raw: Any?): List<CustomField> {
        val list = raw as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<String, Any>>().map { cf ->
            CustomField(
                key = cf["key"] as? String ?: "",
                value = cf["value"] as? String ?: "",
                isSensitive = cf["sensitive"] as? Boolean ?: false,
            )
        }
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    private fun parseCsvReader(reader: Reader): ParsedImport {
        val now = System.currentTimeMillis()
        val failed = mutableListOf<FailedEntry>()
        val credentials = mutableListOf<Credential>()

        CSVReader(reader).use { csvReader ->
            val allRows = csvReader.readAll()
            if (allRows.isEmpty()) return ParsedImport(emptyList(), emptyList())

            val rawHeaders = allRows[0].map { it.trim() }
            val lowerHeaders = rawHeaders.map { it.lowercase() }
            val headerMapping: List<String?> = lowerHeaders.map { CSV_HEADER_MAP[it] }

            require(headerMapping.any { it != null }) {
                "No recognisable columns found. Expected headers like 'title', 'username', or 'password'. " +
                "Supported exports: 1Key, LastPass, KeePass, Google Passwords, Safari, Dashlane, NordPass, 1Password (CSV)."
            }

            val customFieldCols: List<Pair<Int, String>> = rawHeaders.mapIndexedNotNull { idx, h ->
                if (h.isNotBlank() && headerMapping[idx] == null) idx to h else null
            }

            allRows.drop(1).forEachIndexed { rowIndex, row ->
                runCatching {
                    val col = mutableMapOf<String, String>()
                    row.forEachIndexed { colIdx, cell ->
                        val field = headerMapping.getOrNull(colIdx) ?: return@forEachIndexed
                        col[field] = cell.trim()
                    }

                    val customFields = customFieldCols
                        .mapNotNull { (idx, header) ->
                            val value = row.getOrElse(idx) { "" }.trim()
                            if (value.isEmpty()) null
                            else CustomField(
                                key = CustomFieldKeyFormatter.prettify(header),
                                value = value,
                                isSensitive = false,
                            )
                        }
                        .take(CustomField.MAX_FIELDS)

                    credentials.add(
                        Credential(
                            id = col["id"] ?: UUID.randomUUID().toString(),
                            title = col["title"] ?: "",
                            username = col["username"] ?: "",
                            password = col["password"] ?: "",
                            url = col["url"] ?: "",
                            notes = col["notes"] ?: "",
                            otpParams = parseImportedOtp(col["totp_secret"]),
                            tags = col["tags"]?.split("|")?.filter { it.isNotBlank() } ?: emptyList(),
                            isFavorite = col["favorite"]?.lowercase() in TRUTHY_VALUES,
                            customFields = customFields,
                            createdAt = TimestampParser.parseToEpochMillis(col["created_at"]) ?: now,
                            updatedAt = TimestampParser.parseToEpochMillis(col["updated_at"]) ?: now,
                            accessedAt = TimestampParser.parseToEpochMillis(col["accessed_at"]),
                        )
                    )
                }.onFailure { e ->
                    failed.add(FailedEntry(rowIndex = rowIndex + 1, reason = e.message ?: "Unknown error"))
                }
            }
        }

        return ParsedImport(credentials, failed)
    }

    /**
     * Parse the `totp_secret` import column into [OtpParams]. Two shapes are
     * accepted to round-trip exports from this app and from third-party tools:
     *
     * - `otpauth://...` URI — extended exports (post-C8) and Aegis / 2FAS / 1Password
     *   files use this. We delegate to [OtpAuthUriParser], which carries algorithm,
     *   digits, period, counter, and Steam auto-detection through verbatim.
     * - Bare base32 secret — pre-C8 exports and CSV files from sources that only
     *   store the secret. We default everything else to TOTP / SHA-1 / 6 / 30,
     *   the same fallback the QR scanner used before C3.
     *
     * Blank / null collapses to no enrolment. Returning null rather than an empty
     * [OtpParams] keeps the contract simple: `credential.otpParams != null` always
     * means "this credential has 2FA."
     */
    private fun parseImportedOtp(raw: String?): OtpParams? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (trimmed.startsWith(OTPAUTH_PREFIX)) {
            OtpAuthUriParser.parse(trimmed)?.params
                ?: OtpParams.defaultTotp(trimmed)
        } else {
            OtpParams.defaultTotp(trimmed)
        }
    }

    companion object {
        private const val OTPAUTH_PREFIX = "otpauth://"

        private val KNOWN_JSON_KEYS = setOf(
            "id", "title", "username", "password", "url", "notes",
            "totp_secret", "tags", "custom_fields", "created_at", "updated_at",
            "is_favorite", "favorite", "type", "deleted_at", "accessed_at",
        )

        // Foreign-tool JSON keys we translate into canonical snake_case before
        // the per-row parser runs. Resolved in `parseJsonContent` so
        // KNOWN_JSON_KEYS remains the single source of truth for "what we
        // handle natively".
        private val JSON_KEY_ALIASES = mapOf(
            // Firefox passwords export
            "timeCreated" to "created_at",
            "timePasswordChanged" to "updated_at",
            "timeLastUsed" to "accessed_at",
            "guid" to "id",
            // Bitwarden, 1Password (newer JSON shape), generic camelCase
            "createdAt" to "created_at",
            "updatedAt" to "updated_at",
            "creationDate" to "created_at",
            "revisionDate" to "updated_at",
            "dateCreated" to "created_at",
            "dateModified" to "updated_at",
            "modifiedAt" to "updated_at",
            "modifiedDate" to "updated_at",
            "lastModified" to "updated_at",
            // Generic "last accessed / used" variants
            "accessedAt" to "accessed_at",
            "lastAccessed" to "accessed_at",
            "lastUsed" to "accessed_at",
        )

        // Foreign-tool internal-metadata keys we silently drop instead of
        // exposing as custom fields. Firefox writes these on every entry and
        // they have no UX value to a 1Key user.
        private val JSON_KEY_IGNORED = setOf(
            "httpRealm",         // Firefox — HTTP basic-auth realm
            "formActionOrigin",  // Firefox — form-submission URL
        )

        private val CSV_HEADER_MAP = mapOf(
            "title" to "title", "name" to "title", "account" to "title",
            "username" to "username", "login" to "username", "email" to "username",
            "user" to "username", "login_name" to "username",
            "login name" to "username",                          // KeePass
            "password" to "password", "pass" to "password", "pwd" to "password",
            "url" to "url", "website" to "url", "uri" to "url",
            "web_address" to "url", "login_uri" to "url",
            "web site" to "url",                                 // KeePass
            "notes" to "notes", "note" to "notes", "comment" to "notes",
            "comments" to "notes", "extra" to "notes",
            "tags" to "tags", "category" to "tags", "group" to "tags",
            "type" to "tags", "folder" to "tags", "categories" to "tags",
            "grouping" to "tags",                                // LastPass
            "totp_secret" to "totp_secret", "totp" to "totp_secret",
            "otp" to "totp_secret", "authenticator_key" to "totp_secret",
            "created_at" to "created_at", "created" to "created_at",
            "date_created" to "created_at",
            "updated_at" to "updated_at", "updated" to "updated_at",
            "modified" to "updated_at", "last_modified" to "updated_at",
            "date_modified" to "updated_at",
            "accessed_at" to "accessed_at", "last_accessed" to "accessed_at",
            "last_used" to "accessed_at", "accessed_on" to "accessed_at",
            "id" to "id", "uuid" to "id",
            "favorite" to "favorite", "is_favorite" to "favorite", "starred" to "favorite",
            "fav" to "favorite",                                 // LastPass
        )

        private val TRUTHY_VALUES = setOf("true", "1", "yes")
    }
}
