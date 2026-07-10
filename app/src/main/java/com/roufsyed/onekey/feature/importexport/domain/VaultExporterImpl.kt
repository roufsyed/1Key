package com.roufsyed.onekey.feature.importexport.domain

import com.google.gson.GsonBuilder
import com.opencsv.CSVWriter
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.runCatchingResult
import com.roufsyed.onekey.core.domain.usecase.ExportFormat
import com.roufsyed.onekey.core.security.CryptoManager
import com.roufsyed.onekey.core.security.SecretKeyHolder
import com.roufsyed.onekey.feature.twofa.domain.OtpAuthUriBuilder
import java.io.File
import java.io.FileWriter
import java.io.StringWriter
import javax.inject.Inject

class VaultExporterImpl @Inject constructor(
    private val crypto: CryptoManager,
    // Direct injection of the in-memory SK holder. The holder is a
    // @Singleton with no dependency on AuthRepository, so wiring it here
    // does NOT create a Hilt cycle (in contrast to injecting
    // AuthRepository, which loops through SyncEngine -> VaultExporter ->
    // AuthRepository). The active KDF params and the SK-enabled flag
    // arrive in [EncryptedExportContext] from the use case layer.
    private val secretKeyHolder: SecretKeyHolder,
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
        context: EncryptedExportContext,
        createdAtMs: Long,
        vaultVersion: Int,
    ): AppResult<Unit> = runCatchingResult {
        val plaintext = when (format) {
            ExportFormat.JSON -> buildJsonString(credentials).toByteArray(Charsets.UTF_8)
            ExportFormat.CSV -> buildCsvString(credentials).toByteArray(Charsets.UTF_8)
        }
        // V5 envelopes embed the active KDF params verbatim so a restore on
        // a device whose preset has drifted (e.g. user lowered strength
        // after the backup was taken) still re-derives the same key.
        val bytes = if (context.secretKeyEnabled && secretKeyHolder.isPresent()) {
            // withBytes hands back a defensive copy that is zeroed in
            // finally, so the SK lifetime is bounded to the encrypt() call.
            // The encrypt() function does NOT zero this array (it owns its
            // own combined-input buffer in CryptoManager and zeros that),
            // so the holder's finally is the only zero-out we need.
            secretKeyHolder.withBytes { sk ->
                BackupEncryption.encrypt(
                    plaintext = plaintext,
                    password = password,
                    format = format,
                    crypto = crypto,
                    createdAtMs = createdAtMs,
                    vaultVersion = vaultVersion,
                    secretKey = sk,
                    kdfParams = context.kdfParams,
                )
            }
        } else {
            // SK off (or the unlocked session never loaded an SK into the
            // holder). The V5 envelope is still written, FLAGS=0x00, so
            // on-disk format is forward-compatible.
            BackupEncryption.encrypt(
                plaintext = plaintext,
                password = password,
                format = format,
                crypto = crypto,
                createdAtMs = createdAtMs,
                vaultVersion = vaultVersion,
                secretKey = null,
                kdfParams = context.kdfParams,
            )
        }
        File(path).writeBytes(bytes)
    }

    override fun serializeForSync(credentials: List<Credential>, format: ExportFormat): ByteArray =
        when (format) {
            ExportFormat.JSON -> buildJsonString(credentials).toByteArray(Charsets.UTF_8)
            ExportFormat.CSV -> buildCsvString(credentials).toByteArray(Charsets.UTF_8)
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

    /**
     * Encode a credential's OTP enrolment as a string fit for the `totp_secret`
     * export column. The shape is `otpauth://...?...` for any enrolled entry -
     * the spec format Aegis / 2FAS / Bitwarden / 1Password understand on import,
     * carrying every field needed to reproduce codes (algorithm, digits, period,
     * counter, type). Empty string when there's no OTP, matching the prior
     * convention so consumers that grep `totp_secret == ""` still work.
     */
    private fun Credential.exportableOtp(): String =
        otpParams?.let { params ->
            OtpAuthUriBuilder.build(params, issuer = title, account = username)
        } ?: ""

    private fun Credential.toDto(): Map<String, Any> {
        var dto = mapOf<String, Any>(
            "id" to id,
            "type" to type.name,
            "title" to title,
            "username" to username,
            "password" to password,
            "url" to url,
            "notes" to notes,
            "tags" to tags,
            "totp_secret" to exportableOtp(),
            "custom_fields" to customFields.map { mapOf("key" to it.key, "value" to it.value, "sensitive" to it.isSensitive) },
            "created_at" to createdAt,
            "updated_at" to updatedAt,
        )
        // deletedAt / accessedAt only emitted when set, so active backups stay
        // byte-compatible with the previous format (no spurious "key": null
        // lines everywhere) and so legacy entries that never carried an
        // accessed_at don't suddenly start showing one in the export.
        if (deletedAt != null) dto = dto + ("deleted_at" to deletedAt)
        if (accessedAt != null) dto = dto + ("accessed_at" to accessedAt)
        return dto
    }

    private fun Credential.toCsvRow() = arrayOf(
        title, username, password, url, notes,
        tags.joinToString("|"),
        exportableOtp(),
        createdAt.toString(),
        updatedAt.toString(),
        accessedAt?.toString().orEmpty(),
    )

    private companion object {
        val CSV_HEADERS = arrayOf(
            "title", "username", "password", "url", "notes",
            "tags", "totp_secret", "created_at", "updated_at", "accessed_at",
        )
    }
}
