package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.importexport.domain.VaultExporter
import javax.inject.Inject

class ExportVaultUseCase @Inject constructor(
    private val repository: CredentialRepository,
    private val exporter: VaultExporter,
) {
    suspend operator fun invoke(format: ExportFormat, outputPath: String): AppResult<Unit> {
        val result = repository.getAllCredentials()
        if (result is AppResult.Error) return result
        return exporter.export((result as AppResult.Success).data, format, outputPath)
    }

    suspend fun encrypted(format: ExportFormat, password: CharArray, outputPath: String): AppResult<Unit> {
        val result = repository.getAllCredentials()
        if (result is AppResult.Error) return result
        return exporter.exportEncrypted((result as AppResult.Success).data, password, format, outputPath)
    }
}

enum class ExportFormat { JSON, CSV }
