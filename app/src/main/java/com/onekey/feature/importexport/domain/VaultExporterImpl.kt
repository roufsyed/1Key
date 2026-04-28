package com.onekey.feature.importexport.domain

import com.google.gson.GsonBuilder
import com.opencsv.CSVWriter
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.runCatchingResult
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.security.CryptoManager
import java.io.File
import java.io.FileWriter
import java.io.StringWriter
import javax.inject.Inject

class VaultExporterImpl @Inject constructor(
    private val crypto: CryptoManager,
) : VaultExporter {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    override suspend fun export(
        credentials: List<Credential>,
        format: ExportFormat,
        path: String,
    ): AppResult<Unit> = runCatchingResult {
        when (format) {
            ExportFormat.JSON -> File(path).writeText(buildJsonString(credentials))
            ExportFormat.CSV -> writeCsvToFile(credentials, path)
        }
    }

    override suspend fun exportEncrypted(
        credentials: List<Credential>,
        password: CharArray,
        format: ExportFormat,
        path: String,
    ): AppResult<Unit> = runCatchingResult {
        val plaintext = when (format) {
            ExportFormat.JSON -> buildJsonString(credentials).toByteArray(Charsets.UTF_8)
            ExportFormat.CSV -> buildCsvString(credentials).toByteArray(Charsets.UTF_8)
        }
        File(path).writeBytes(BackupEncryption.encrypt(plaintext, password, format, crypto))
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    private fun buildJsonString(credentials: List<Credential>): String =
        gson.toJson(credentials.map { it.toDto() })

    private fun buildCsvString(credentials: List<Credential>): String {
        val sw = StringWriter()
        CSVWriter(sw).use { writer ->
            writer.writeNext(CSV_HEADERS)
            credentials.forEach { writer.writeNext(it.toCsvRow()) }
        }
        return sw.toString()
    }

    private fun writeCsvToFile(credentials: List<Credential>, path: String) {
        CSVWriter(FileWriter(path)).use { writer ->
            writer.writeNext(CSV_HEADERS)
            credentials.forEach { writer.writeNext(it.toCsvRow()) }
        }
    }

    private fun Credential.toDto(): Map<String, Any> {
        val base = mapOf<String, Any>(
            "id" to id,
            "type" to type.name,
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
        // deletedAt only emitted for bin items so active backups stay byte-compatible
        // with the previous format (no spurious "deleted_at": null lines everywhere).
        return if (deletedAt != null) base + ("deleted_at" to deletedAt) else base
    }

    private fun Credential.toCsvRow() = arrayOf(
        title, username, password, url, notes,
        tags.joinToString("|"),
        totpSecret ?: "",
        createdAt.toString(),
        updatedAt.toString(),
    )

    private companion object {
        val CSV_HEADERS = arrayOf(
            "title", "username", "password", "url", "notes",
            "tags", "totp_secret", "created_at", "updated_at",
        )
    }
}
