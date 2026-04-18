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
        val credentials = (result as AppResult.Success).data
        return exporter.export(credentials, format, outputPath)
    }
}

enum class ExportFormat { JSON, CSV }
