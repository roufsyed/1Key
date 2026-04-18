package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.importexport.domain.VaultImporter
import javax.inject.Inject

class ImportVaultUseCase @Inject constructor(
    private val repository: CredentialRepository,
    private val importer: VaultImporter,
) {
    suspend operator fun invoke(format: ExportFormat, filePath: String): AppResult<Int> {
        val parseResult = importer.parse(filePath, format)
        if (parseResult is AppResult.Error) return parseResult
        val credentials = (parseResult as AppResult.Success).data
        return repository.importCredentials(credentials)
    }
}
