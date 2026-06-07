package com.onekey.core.domain.usecase

import androidx.paging.PagingData
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the post-fix ordering of [ResetVaultUseCase.invoke]:
 *
 *   1. authRepository.lock()                       - synchronous snapshot-clear via VaultLockHook
 *   2. credentialRepository.deleteAllCredentials() - SQL DELETE encrypted rows
 *   3. historyRepository.deleteAll()               - SQL DELETE history rows
 *   4. authRepository.resetVault()                 - clear auth state
 *
 * The pre-fix ordering was (delete, deleteHistory, resetVault) - lock came
 * implicitly via resetVault. That left a window where the snapshot still
 * held decrypted bytes from the deleted vault. The new ordering guarantees
 * the snapshot is dropped synchronously BEFORE any SQL delete runs.
 *
 * Plain JVM - recording fakes capture method-call order in a shared list,
 * then we assert the expected sequence.
 */
class ResetVaultUseCaseTest {

    @Test fun invokes_lock_then_delete_credentials_then_delete_history_then_reset() = runBlocking {
        val calls = mutableListOf<String>()
        val auth = RecordingAuthRepository(calls)
        val creds = RecordingCredentialRepository(calls)
        val history = RecordingHistoryRepository(calls)

        val useCase = ResetVaultUseCase(auth, creds, history)
        val result = useCase()

        assertTrue("Reset must succeed when every step returns Success", result is AppResult.Success)
        assertEquals(
            "Strict call sequence is the security contract - see ResetVaultUseCase KDoc",
            listOf(
                "auth.lock",
                "creds.deleteAllCredentials",
                "history.deleteAll",
                "auth.resetVault",
            ),
            calls,
        )
    }

    @Test fun lock_failure_short_circuits_before_any_delete() = runBlocking {
        val calls = mutableListOf<String>()
        val auth = RecordingAuthRepository(calls).apply {
            lockResult = AppResult.Error(IllegalStateException("simulated lock failure"))
        }
        val creds = RecordingCredentialRepository(calls)
        val history = RecordingHistoryRepository(calls)

        val useCase = ResetVaultUseCase(auth, creds, history)
        val result = useCase()

        assertTrue(result is AppResult.Error)
        assertEquals(
            "Lock-failure must short-circuit BEFORE any SQL delete",
            listOf("auth.lock"),
            calls,
        )
    }

    @Test fun delete_credentials_failure_short_circuits_before_history() = runBlocking {
        val calls = mutableListOf<String>()
        val auth = RecordingAuthRepository(calls)
        val creds = RecordingCredentialRepository(calls).apply {
            deleteAllResult = AppResult.Error(IllegalStateException("simulated DB failure"))
        }
        val history = RecordingHistoryRepository(calls)

        val useCase = ResetVaultUseCase(auth, creds, history)
        val result = useCase()

        assertTrue(result is AppResult.Error)
        assertEquals(
            listOf("auth.lock", "creds.deleteAllCredentials"),
            calls,
        )
    }

    @Test fun history_delete_failure_short_circuits_before_resetVault() = runBlocking {
        val calls = mutableListOf<String>()
        val auth = RecordingAuthRepository(calls)
        val creds = RecordingCredentialRepository(calls)
        val history = RecordingHistoryRepository(calls).apply {
            deleteAllResult = AppResult.Error(IllegalStateException("simulated history-DB failure"))
        }

        val useCase = ResetVaultUseCase(auth, creds, history)
        val result = useCase()

        assertTrue(result is AppResult.Error)
        assertEquals(
            listOf("auth.lock", "creds.deleteAllCredentials", "history.deleteAll"),
            calls,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Recording fakes - minimal implementations that record method names
    // into a shared list. Anything not exercised by ResetVaultUseCase
    // throws NotImplementedError to catch accidental drift.
    // ─────────────────────────────────────────────────────────────────────

    private class RecordingAuthRepository(
        private val calls: MutableList<String>,
    ) : AuthRepository {
        var lockResult: AppResult<Unit> = AppResult.Success(Unit)
        var resetVaultResult: AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun lock(): AppResult<Unit> {
            calls += "auth.lock"
            return lockResult
        }

        override suspend fun resetVault(): AppResult<Unit> {
            calls += "auth.resetVault"
            return resetVaultResult
        }

        override fun isSetupComplete(): Flow<Boolean> = error("unused")
        override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithBiometric(): AppResult<Unit> = error("unused")
        override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun setupPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> = error("unused")
        override fun isUnlocked(): Flow<Boolean> = error("unused")
        override fun isPinSetup(): Flow<Boolean> = error("unused")
        override suspend fun resetPin(): AppResult<Unit> = error("unused")
        override suspend fun clearAll(): AppResult<Unit> = error("unused")
    }

    private class RecordingCredentialRepository(
        private val calls: MutableList<String>,
    ) : CredentialRepository {
        var deleteAllResult: AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun deleteAllCredentials(): AppResult<Unit> {
            calls += "creds.deleteAllCredentials"
            return deleteAllResult
        }

        override fun getPagedCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> = error("unused")
        override fun observeCredential(id: String): Flow<Credential?> = error("unused")
        override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> = error("unused")
        override suspend fun getCredential(id: String): AppResult<Credential> = error("unused")
        override suspend fun saveCredential(credential: Credential): AppResult<Unit> = error("unused")
        override suspend fun deleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun hardDeleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun restoreCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> = error("unused")
        override suspend fun emptyRecycleBin(): AppResult<Int> = error("unused")
        override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = error("unused")
        override fun observeRecycleBin(): Flow<List<Credential>> = emptyFlow()
        override fun observeRecycleBinCount(): Flow<Int> = flowOf(0)
        override suspend fun getAllCredentials(): AppResult<List<Credential>> = error("unused")
        override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = error("unused")
        override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> = error("unused")
        override fun observeCount(): Flow<Int> = flowOf(0)
        override fun observeCountForTag(tag: String): Flow<Int> = flowOf(0)
        override fun observeFavoriteCount(): Flow<Int> = flowOf(0)
        override fun observeFavorites(): Flow<List<Credential>> = emptyFlow()
        override fun observeFavoritesPaged(sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> = error("unused")
        override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> = emptyFlow()
        override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> = emptyFlow()
        override fun observeAllTitlesAlphabetical(tag: String): Flow<List<String>> = emptyFlow()
        override fun observeFavoriteTitlesAlphabetical(): Flow<List<String>> = emptyFlow()
        override fun observeRotatingOtp(): Flow<List<Credential>> = emptyFlow()
        override fun observeHotpEntries(): Flow<List<Credential>> = emptyFlow()
        override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = error("unused")
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> = error("unused")
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }

    private class RecordingHistoryRepository(
        private val calls: MutableList<String>,
    ) : CredentialHistoryRepository {
        var deleteAllResult: AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun deleteAll(): AppResult<Unit> {
            calls += "history.deleteAll"
            return deleteAllResult
        }

        override suspend fun snapshotCredential(credential: Credential): AppResult<Unit> = error("unused")
        override fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>> = emptyFlow()
        override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> = error("unused")
    }
}
