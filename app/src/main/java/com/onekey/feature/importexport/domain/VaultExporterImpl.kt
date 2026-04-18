package com.onekey.feature.importexport.domain

import com.google.gson.GsonBuilder
import com.opencsv.CSVWriter
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.usecase.ExportFormat
import java.io.File
import java.io.FileWriter
import javax.inject.Inject

class VaultExporterImpl @Inject constructor() : VaultExporter {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    override suspend fun export(
        credentials: List<Credential>,
        format: ExportFormat,
        path: String,
    ): AppResult<Unit> = runCatchingResult {
        when (format) {
            ExportFormat.JSON -> exportJson(credentials, path)
            ExportFormat.CSV -> exportCsv(credentials, path)
        }
    }

    private fun exportJson(credentials: List<Credential>, path: String) {
        val dtos = credentials.map { it.toDto() }
        File(path).writeText(gson.toJson(dtos))
    }

    private fun exportCsv(credentials: List<Credential>, path: String) {
        CSVWriter(FileWriter(path)).use { writer ->
            writer.writeNext(arrayOf("title", "username", "password", "url", "notes", "tags", "totp_secret", "created_at", "updated_at"))
            credentials.forEach { c ->
                writer.writeNext(arrayOf(
                    c.title, c.username, c.password, c.url, c.notes,
                    c.tags.joinToString("|"),
                    c.totpSecret ?: "",
                    c.createdAt.toString(),
                    c.updatedAt.toString(),
                ))
            }
        }
    }

    private fun Credential.toDto() = mapOf(
        "id" to id,
        "title" to title,
        "username" to username,
        "password" to password,
        "url" to url,
        "notes" to notes,
        "tags" to tags,
        "totp_secret" to (totpSecret ?: ""),
        "custom_fields" to customFields.map { mapOf("key" to it.key, "value" to it.value, "sensitive" to it.isSensitive) },
        "created_at" to createdAt,
        "updated_at" to updatedAt,
    )
}
