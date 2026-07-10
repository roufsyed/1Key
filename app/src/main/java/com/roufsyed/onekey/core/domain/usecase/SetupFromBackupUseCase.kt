package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.feature.importexport.domain.EncryptedParseResult
import com.roufsyed.onekey.feature.importexport.domain.ImportResult
import com.roufsyed.onekey.feature.importexport.domain.VaultImporter
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
     *  1. Decrypt the backup first - fast-fail on wrong password before touching auth state.
     *  2. Set up the vault using the same password - also unlocks it in memory.
     *  3. Import decrypted credentials into the fresh vault.
     *
     * **Password handling:** both [VaultImporter.parseEncrypted] and
     * [AuthRepository.setupMasterPassword] consume their input - they zero the
     * [CharArray] in place after deriving the KDF key. We must therefore pass each
     * one a fresh, fully-populated copy. Step 1 receives a defensive copy
     * ([passwordForParse]); step 2 receives the original [password]. The caller
     * (typically AuthViewModel) zeros [password] itself in a `finally` block as a
     * final safety net.
     *
     * The bug this prevents: previously the same [password] reference was used twice;
     * step 1 zeroed it via `BackupEncryption.decrypt`, so step 2 saw all-spaces input
     * and the vault was set up keyed to "N spaces" rather than the user's actual
     * password - making the vault unrecoverable on next launch.
     */
    suspend operator fun invoke(password: CharArray, filePath: String): AppResult<ImportResult> {
        // Step 1 - defensive copy because parseEncrypted will zero its argument.
        val passwordForParse = password.copyOf()
        val parseResult = try {
            importer.parseEncrypted(filePath, passwordForParse)
        } finally {
            // Belt-and-suspenders: the contract is that parseEncrypted zeroes this,
            // but double-clear so a future implementation that skips the zeroing
            // (or returns early before reaching it) still leaves no residue.
            passwordForParse.fill(' ')
        }
        val parsed = when (parseResult) {
            is EncryptedParseResult.Success -> parseResult.parsed
            is EncryptedParseResult.Failure -> return AppResult.Error(
                parseResult.throwable,
                parseResult.message,
            )
            is EncryptedParseResult.SecretKeyRequired -> {
                // The restore-from-backup-with-SK pivot is owned by the
                // onboarding ViewModel (Stage 7). This use case is the
                // legacy MP-only path: surface an error so the caller can
                // route to the SK-aware entry point instead of silently
                // failing. The message carries the parsed header metadata
                // so a debugger sees the export time.
                return AppResult.Error(
                    IllegalStateException("Secret Key required"),
                    "This backup needs your Secret Key. " +
                        "Backup taken at ts=${parseResult.createdAtMs}, vault v${parseResult.vaultVersion}.",
                )
            }
        }
        val failed = parsed.failed
        val credentials = parsed.credentials

        // Step 2 - password is still intact here. setupMasterPassword zeros it
        // internally before returning.
        val setupResult = authRepository.setupMasterPassword(password)
        if (setupResult is AppResult.Error) return setupResult

        // Step 3 - vault is now unlocked; import credentials.
        if (credentials.isNotEmpty()) {
            val importResult = credentialRepository.importCredentials(credentials)
            if (importResult is AppResult.Error) return importResult
        }

        return AppResult.Success(ImportResult(imported = credentials.size, skipped = emptyList(), failed = failed))
    }
}
