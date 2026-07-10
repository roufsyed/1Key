package com.roufsyed.onekey.core.domain.usecase

import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.domain.repository.CredentialHistoryRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import javax.inject.Inject

class ResetVaultUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
) {
    /**
     * Locks the vault FIRST, then deletes credential + history data, then
     * resets the auth state.
     *
     * Why lock-first: [com.roufsyed.onekey.core.security.VaultKeyHolder.lock] now
     * synchronously fires the [com.roufsyed.onekey.core.security.VaultLockHook]
     * installed by [com.roufsyed.onekey.core.data.snapshot.VaultSnapshotStore]. That
     * hook drops the snapshot's plaintext credential list and clears the
     * decryptor's cached HKDF subkeys on the lock() caller's thread, BEFORE
     * the SQL DELETE runs. Without this ordering, the snapshot would still
     * hold decrypted bytes from the "old" vault when the SQL rows have
     * already been wiped - a cross-vault residency window that becomes
     * exploitable the moment the user re-sets up the vault with a different
     * password and a third party (e.g. an autofill request handler) reads
     * the snapshot before the relock fires.
     *
     * The pre-fix ordering (delete -> history.delete -> resetVault) is
     * replaced with (lock -> delete -> history.delete -> resetVault). Lock
     * failures (`authRepository.lock` returning Error) are surfaced
     * because callers (Settings -> Delete Vault) need to know - in practice
     * `VaultKeyHolder.lock()` does not fail, but the contract type allows
     * it.
     */
    suspend operator fun invoke(): AppResult<Unit> {
        // STEP 1 - Lock first. The synchronous VaultLockHook drops the
        // shared snapshot's plaintext list BEFORE we delete the encrypted
        // SQL rows.
        val lockResult = authRepository.lock()
        if (lockResult is AppResult.Error) return lockResult

        // STEP 2 - Delete encrypted credential rows.
        val deleteResult = credentialRepository.deleteAllCredentials()
        if (deleteResult is AppResult.Error) return deleteResult

        // STEP 3 - Delete history rows.
        val historyResult = historyRepository.deleteAll()
        if (historyResult is AppResult.Error) return historyResult

        // STEP 4 - Reset auth state (master-password verifier, PIN,
        // biometric pref, etc.). The new setup flow runs from scratch.
        return authRepository.resetVault()
    }
}
