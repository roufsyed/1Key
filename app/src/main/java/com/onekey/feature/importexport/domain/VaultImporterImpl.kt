package com.onekey.feature.importexport.domain

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opencsv.CSVReader
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CustomField
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.usecase.ExportFormat
import java.io.File
import java.io.FileReader
import java.util.UUID
import javax.inject.Inject

class VaultImporterImpl @Inject constructor() : VaultImporter {

    private val gson = Gson()

    override suspend fun parse(path: String, format: ExportFormat): AppResult<ParsedImport> =
        runCatchingResult {
            when (format) {
                ExportFormat.JSON -> parseJson(path)
                ExportFormat.CSV -> parseCsv(path)
            }
        }

    // ── JSON ─────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(path: String): ParsedImport {
        val now = System.currentTimeMillis()
        val failed = mutableListOf<FailedEntry>()
        val credentials = mutableListOf<Credential>()

        val json = File(path).readText()
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        val list: List<Map<String, Any>> = gson.fromJson(json, type)

        list.forEachIndexed { index, map ->
            runCatching {
                val knownCustomFields = parseCustomFields(map["custom_fields"])
                val unknownCustomFields = map.entries
                    .filter { (k, v) ->
                        k !in KNOWN_JSON_KEYS && (v is String || v is Number || v is Boolean)
                    }
                    .map { (k, v) -> CustomField(key = k, value = v.toString(), isSensitive = false) }

                val allCustomFields = (knownCustomFields + unknownCustomFields)
                    .take(CustomField.MAX_FIELDS)

                credentials.add(
                    Credential(
                        id = (map["id"] as? String) ?: UUID.randomUUID().toString(),
                        title = map["title"] as? String ?: "",
                        username = map["username"] as? String ?: "",
                        password = map["password"] as? String ?: "",
                        url = map["url"] as? String ?: "",
                        notes = map["notes"] as? String ?: "",
                        totpSecret = (map["totp_secret"] as? String)?.takeIf { it.isNotBlank() },
                        tags = (map["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        isFavorite = (map["is_favorite"] as? Boolean)
                            ?: (map["favorite"] as? Boolean) ?: false,
                        customFields = allCustomFields,
                        createdAt = (map["created_at"] as? Double)?.toLong() ?: now,
                        updatedAt = (map["updated_at"] as? Double)?.toLong() ?: now,
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

    private fun parseCsv(path: String): ParsedImport {
        val now = System.currentTimeMillis()
        val failed = mutableListOf<FailedEntry>()
        val credentials = mutableListOf<Credential>()

        CSVReader(FileReader(path)).use { reader ->
            val allRows = reader.readAll()
            if (allRows.isEmpty()) return ParsedImport(emptyList(), emptyList())

            // Preserve original case for custom-field keys; lowercase for lookup.
            val rawHeaders = allRows[0].map { it.trim() }
            val lowerHeaders = rawHeaders.map { it.lowercase() }

            // Map each column index to a known field name, or null (= custom field).
            val headerMapping: List<String?> = lowerHeaders.map { CSV_HEADER_MAP[it] }

            // Columns whose headers don't map to a known field become CustomField entries.
            // Blank column headers are silently ignored.
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
                            else CustomField(key = header, value = value, isSensitive = false)
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
                            totpSecret = col["totp_secret"]?.takeIf { it.isNotBlank() },
                            tags = col["tags"]?.split("|")?.filter { it.isNotBlank() } ?: emptyList(),
                            isFavorite = col["favorite"]?.lowercase() in TRUTHY_VALUES,
                            customFields = customFields,
                            createdAt = col["created_at"]?.toLongOrNull() ?: now,
                            updatedAt = col["updated_at"]?.toLongOrNull() ?: now,
                        )
                    )
                }.onFailure { e ->
                    failed.add(FailedEntry(rowIndex = rowIndex + 1, reason = e.message ?: "Unknown error"))
                }
            }
        }

        return ParsedImport(credentials, failed)
    }

    companion object {
        private val KNOWN_JSON_KEYS = setOf(
            "id", "title", "username", "password", "url", "notes",
            "totp_secret", "tags", "custom_fields", "created_at", "updated_at",
            "is_favorite", "favorite",
        )

        private val CSV_HEADER_MAP = mapOf(
            "title" to "title", "name" to "title", "account" to "title",
            "username" to "username", "login" to "username", "email" to "username",
            "user" to "username", "login_name" to "username",
            "password" to "password", "pass" to "password", "pwd" to "password",
            "url" to "url", "website" to "url", "uri" to "url",
            "web_address" to "url", "login_uri" to "url",
            "notes" to "notes", "note" to "notes", "comment" to "notes",
            "comments" to "notes", "extra" to "notes",
            "tags" to "tags", "category" to "tags", "group" to "tags",
            "type" to "tags", "folder" to "tags", "categories" to "tags",
            "totp_secret" to "totp_secret", "totp" to "totp_secret",
            "otp" to "totp_secret", "authenticator_key" to "totp_secret",
            "created_at" to "created_at", "created" to "created_at",
            "date_created" to "created_at",
            "updated_at" to "updated_at", "updated" to "updated_at",
            "modified" to "updated_at", "last_modified" to "updated_at",
            "date_modified" to "updated_at",
            "id" to "id", "uuid" to "id",
            "favorite" to "favorite", "is_favorite" to "favorite", "starred" to "favorite",
        )

        private val TRUTHY_VALUES = setOf("true", "1", "yes")
    }
}
