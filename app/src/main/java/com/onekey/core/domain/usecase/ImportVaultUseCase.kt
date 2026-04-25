package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.importexport.domain.FailedEntry
import com.onekey.feature.importexport.domain.ImportResult
import com.onekey.feature.importexport.domain.SkipReason
import com.onekey.feature.importexport.domain.SkippedCredential
import com.onekey.feature.importexport.domain.VaultImporter
import javax.inject.Inject

class ImportVaultUseCase @Inject constructor(
    private val repository: CredentialRepository,
    private val importer: VaultImporter,
) {
    suspend fun isEncrypted(filePath: String): Boolean = importer.isEncrypted(filePath)

    suspend operator fun invoke(filePath: String): AppResult<ImportResult> {
        val parseResult = importer.parse(filePath)
        if (parseResult is AppResult.Error) return parseResult
        val (parsed, failed) = (parseResult as AppResult.Success).data
        return deduplicateAndImport(parsed, failed)
    }

    suspend fun fromEncrypted(filePath: String, password: CharArray): AppResult<ImportResult> {
        val parseResult = importer.parseEncrypted(filePath, password)
        if (parseResult is AppResult.Error) return parseResult
        val (parsed, failed) = (parseResult as AppResult.Success).data
        return deduplicateAndImport(parsed, failed)
    }

    private suspend fun deduplicateAndImport(
        parsed: List<Credential>,
        failedEntries: List<FailedEntry>,
    ): AppResult<ImportResult> {
        val existingResult = repository.getAllCredentials()
        if (existingResult is AppResult.Error) return existingResult

        val existing = (existingResult as AppResult.Success).data
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val existingTitleUsername = existing.mapTo(HashSet()) { it.title.trim() to it.username.trim() }

        val toImport = mutableListOf<Credential>()
        val skipped = mutableListOf<SkippedCredential>()

        for (cred in parsed) {
            val titleKey = cred.title.trim() to cred.username.trim()
            when {
                cred.id in existingIds ->
                    skipped.add(SkippedCredential(cred.title, cred.username, SkipReason.DUPLICATE_ID))
                titleKey in existingTitleUsername ->
                    skipped.add(SkippedCredential(cred.title, cred.username, SkipReason.DUPLICATE_TITLE_USERNAME))
                else -> {
                    toImport.add(cred)
                    existingIds.add(cred.id)
                    existingTitleUsername.add(titleKey)
                }
            }
        }

        if (toImport.isNotEmpty()) {
            val importResult = repository.importCredentials(toImport)
            if (importResult is AppResult.Error) return importResult
        }

        return AppResult.Success(ImportResult(imported = toImport.size, skipped = skipped, failed = failedEntries))
    }
}
