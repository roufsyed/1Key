package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import com.roufsyed.onekey.core.security.VaultVersionTracker
import com.roufsyed.onekey.feature.importexport.domain.EncryptedExportContext
import com.roufsyed.onekey.feature.importexport.domain.VaultExporter
import javax.inject.Inject

class ExportVaultUseCase @Inject constructor(
    private val repository: CredentialRepository,
    private val exporter: VaultExporter,
    private val vaultVersionTracker: VaultVersionTracker,
    // The use case (not the exporter) reads from AuthRepository so the
    // VaultExporter -> AuthRepository -> SyncEngine -> VaultExporter Hilt
    // cycle stays broken. The use case is downstream of both modules so
    // it can safely depend on both.
    private val authRepository: AuthRepository,
    private val passkeyRepository: PasskeyRepository,
) {
    /**
     * Plain export. Passkeys are deliberately NOT attached (plan decision D5):
     * an unencrypted file never carries a passkey private key, whatever the
     * format. The only plaintext passkey path is the per-passkey export.
     */
    suspend operator fun invoke(format: ExportFormat, outputPath: String): AppResult<Unit> {
        val all = collectAll() ?: return repository.getAllCredentials() as AppResult.Error
        return exporter.export(all, format, outputPath)
    }

    suspend fun encrypted(format: ExportFormat, password: CharArray, outputPath: String): AppResult<Unit> {
        val credentials = collectAll() ?: return repository.getAllCredentials() as AppResult.Error
        val (all, passkeyIds) = attachPasskeys(credentials, passkeyRepository)
        val vaultVersion = vaultVersionTracker.getVersion()
        val context = EncryptedExportContext(
            kdfParams = authRepository.activeKdfParams(),
            secretKeyEnabled = authRepository.isSecretKeyEnabled(),
        )
        val result = exporter.exportEncrypted(
            credentials = all,
            password = password,
            format = format,
            path = outputPath,
            context = context,
            vaultVersion = vaultVersion,
        )
        // The file exists now, so the BS flag may honestly say so from here on.
        if (result is AppResult.Success && format == ExportFormat.JSON) passkeyRepository.markBackedUp(passkeyIds)
        return result
    }

    /**
     * Active + recycle-bin credentials together. Bin items carry deletedAt so a round-trip
     * (export -> restore from this backup) preserves the recycle bin instead of silently
     * dropping it. CSV exports drop deletedAt at the format level - that's fine, CSV is for
     * cross-tool migration where the bin concept doesn't exist.
     */
    private suspend fun collectAll(): List<Credential>? {
        val active = repository.getAllCredentials()
        if (active is AppResult.Error) return null
        val bin = repository.getAllInRecycleBin()
        if (bin is AppResult.Error) return null
        return (active as AppResult.Success).data + (bin as AppResult.Success).data
    }

    companion object {
        /**
         * Second, per-row isolated pass that joins passkeys onto the credentials
         * an encrypted backup or sync file will carry. Shared with the sync
         * engine so both outputs agree. Returns the enriched list and the ids of
         * every passkey included, for the BS bookkeeping after a successful write.
         * A repository error leaves the credentials untouched: a backup without
         * passkeys beats no backup.
         */
        suspend fun attachPasskeys(
            credentials: List<Credential>,
            passkeyRepository: PasskeyRepository,
        ): Pair<List<Credential>, List<String>> {
            val batch = (passkeyRepository.allForExport() as? AppResult.Success)?.data
                ?: return credentials to emptyList()
            if (batch.passkeys.isEmpty()) return credentials to emptyList()
            val byCredential = batch.passkeys.groupBy { it.credentialId }
            val enriched = credentials.map { c -> byCredential[c.id]?.let { c.copy(passkeys = it) } ?: c }
            return enriched to batch.passkeys.map { it.id }
        }
    }
}

enum class ExportFormat { JSON, CSV }
