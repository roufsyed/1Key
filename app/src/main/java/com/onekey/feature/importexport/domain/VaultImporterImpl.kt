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

    override suspend fun parse(path: String, format: ExportFormat): AppResult<List<Credential>> =
        runCatchingResult {
            when (format) {
                ExportFormat.JSON -> parseJson(path)
                ExportFormat.CSV -> parseCsv(path)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(path: String): List<Credential> {
        val json = File(path).readText()
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        val list: List<Map<String, Any>> = gson.fromJson(json, type)
        return list.map { map ->
            Credential(
                id = (map["id"] as? String) ?: UUID.randomUUID().toString(),
                title = map["title"] as? String ?: "",
                username = map["username"] as? String ?: "",
                password = map["password"] as? String ?: "",
                url = map["url"] as? String ?: "",
                notes = map["notes"] as? String ?: "",
                totpSecret = (map["totp_secret"] as? String)?.takeIf { it.isNotBlank() },
                tags = (map["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                customFields = parseCustomFields(map["custom_fields"]),
                createdAt = (map["created_at"] as? Double)?.toLong() ?: System.currentTimeMillis(),
                updatedAt = (map["updated_at"] as? Double)?.toLong() ?: System.currentTimeMillis(),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCustomFields(raw: Any?): List<CustomField> {
        val list = raw as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<String, Any>>().take(CustomField.MAX_FIELDS).map { cf ->
            CustomField(
                key = cf["key"] as? String ?: "",
                value = cf["value"] as? String ?: "",
                isSensitive = cf["sensitive"] as? Boolean ?: false,
            )
        }
    }

    private fun parseCsv(path: String): List<Credential> {
        val now = System.currentTimeMillis()
        return CSVReader(FileReader(path)).use { reader ->
            val rows = reader.readAll()
            if (rows.size < 2) return emptyList()
            rows.drop(1).map { row ->
                Credential(
                    id = UUID.randomUUID().toString(),
                    title = row.getOrElse(0) { "" },
                    username = row.getOrElse(1) { "" },
                    password = row.getOrElse(2) { "" },
                    url = row.getOrElse(3) { "" },
                    notes = row.getOrElse(4) { "" },
                    tags = row.getOrElse(5) { "" }.split("|").filter { it.isNotBlank() },
                    totpSecret = row.getOrElse(6) { "" }.takeIf { it.isNotBlank() },
                    customFields = emptyList(),
                    createdAt = row.getOrElse(7) { "0" }.toLongOrNull() ?: now,
                    updatedAt = row.getOrElse(8) { "0" }.toLongOrNull() ?: now,
                )
            }
        }
    }
}
