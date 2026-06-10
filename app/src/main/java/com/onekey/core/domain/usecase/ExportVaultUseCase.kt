package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.security.VaultVersionTracker
import com.onekey.feature.importexport.domain.VaultExporter
import javax.inject.Inject

class ExportVaultUseCase @Inject constructor(
    private val repository: CredentialRepository,
    private val exporter: VaultExporter,
    private val vaultVersionTracker: VaultVersionTracker,
) {
    suspend operator fun invoke(format: ExportFormat, outputPath: String): AppResult<Unit> {
        val all = collectAll() ?: return repository.getAllCredentials() as AppResult.Error
        return exporter.export(all, format, outputPath)
    }

    suspend fun encrypted(format: ExportFormat, password: CharArray, outputPath: String): AppResult<Unit> {
        val all = collectAll() ?: return repository.getAllCredentials() as AppResult.Error
        val vaultVersion = vaultVersionTracker.getVersion()
        return exporter.exportEncrypted(all, password, format, outputPath, vaultVersion = vaultVersion)
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
}

enum class ExportFormat { JSON, CSV }
