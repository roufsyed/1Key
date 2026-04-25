package com.onekey.core.domain.usecase

import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.importexport.domain.ImportResult
import com.onekey.feature.importexport.domain.VaultImporter
import javax.inject.Inject

class SetupFromBackupUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val importer: VaultImporter,
    private val credentialRepository: CredentialRepository,
) {
    /**
     * Restores an encrypted 1Key backup during onboarding (no vault exists yet).
     *
     * Order matters:
     *  1. Decrypt the backup first — fast-fail on wrong password before touching auth state.
     *  2. Set up the vault using the same password — also unlocks it in memory.
     *  3. Import decrypted credentials into the fresh vault.
     *
     * Note: [authRepository.setupMasterPassword] zeroes [password] internally, so callers
     * must not read from it after this function returns.
     */
    suspend operator fun invoke(password: CharArray, filePath: String): AppResult<ImportResult> {
        // Step 1: decrypt — fail early if password or file is wrong
        val parseResult = importer.parseEncrypted(filePath, password)
        if (parseResult is AppResult.Error) return parseResult
        val (parsed, failed) = (parseResult as AppResult.Success).data

        // Step 2: create vault (zeroes password internally)
        val setupResult = authRepository.setupMasterPassword(password)
        if (setupResult is AppResult.Error) return setupResult

        // Step 3: vault is now unlocked — import credentials
        if (parsed.isNotEmpty()) {
            val importResult = credentialRepository.importCredentials(parsed)
            if (importResult is AppResult.Error) return importResult
        }

        return AppResult.Success(ImportResult(imported = parsed.size, skipped = emptyList(), failed = failed))
    }
}
