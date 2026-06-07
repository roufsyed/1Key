package com.onekey.core.domain.usecase

import androidx.paging.PagingData
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.importexport.domain.ParsedImport
import com.onekey.feature.importexport.domain.VaultImporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the SetupFromBackup zeroed-password bug.
 *
 * Background: [com.onekey.feature.importexport.domain.BackupEncryption.decrypt] zeros the caller's
 * password CharArray after deriving the KDF key. The historical bug in [SetupFromBackupUseCase]
 * was that the same CharArray was passed to both `parseEncrypted` (which zeroed it) and
 * then `setupMasterPassword` (which read all-spaces input and silently set up the vault
 * keyed to "N spaces" rather than the user's password).
 *
 * This test pins down the contract: even when `parseEncrypted` zeros its input,
 * `setupMasterPassword` must receive the original password byte-for-byte.
 */
class SetupFromBackupUseCaseTest {

    @Test
    fun `setupMasterPassword receives original password despite parseEncrypted zeroing its input`() = runBlocking {
        val originalPassword = "MyP@ss123!".toCharArray()
        val expectedSnapshot = originalPassword.copyOf()

        val fakeImporter = ZeroingFakeImporter()
        val capturingAuth = CapturingFakeAuth()
        val fakeCreds = NoopFakeCreds()

        val useCase = SetupFromBackupUseCase(capturingAuth, fakeImporter, fakeCreds)
        val result = useCase(originalPassword, "/fake/path.1key")

        assertTrue(
            "use case should succeed when parseEncrypted and setupMasterPassword both succeed",
            result is AppResult.Success,
        )
        val captured = capturingAuth.capturedSetupPassword
        assertNotNull("setupMasterPassword should have been invoked exactly once", captured)
        // The exact assertion that catches the original bug:
        assertArrayEquals(
            "setupMasterPassword received the original password (not the zeroed copy)",
            expectedSnapshot,
            captured,
        )
        // And the importer should have been invoked, confirming we exercised the consume path:
        assertEquals(1, fakeImporter.parseEncryptedCalls)
        assertTrue(
            "the CharArray passed to parseEncrypted is expected to be zeroed by the contract",
            fakeImporter.lastReceivedPasswordAfterReturn?.all { it == ' ' } == true,
        )
    }

    @Test
    fun `wrong password (parseEncrypted error) does not invoke setupMasterPassword`() = runBlocking {
        val password = "wrong".toCharArray()
        val fakeImporter = object : ZeroingFakeImporter() {
            override suspend fun parseEncrypted(path: String, password: CharArray): AppResult<ParsedImport> {
                // Mirror real implementation: zero even on failure.
                password.fill(' ')
                return AppResult.Error(IllegalStateException("Wrong password"), "Wrong password")
            }
        }
        val capturingAuth = CapturingFakeAuth()
        val fakeCreds = NoopFakeCreds()

        val useCase = SetupFromBackupUseCase(capturingAuth, fakeImporter, fakeCreds)
        val result = useCase(password, "/fake/path.1key")

        assertTrue("returns the importer's Error", result is AppResult.Error)
        assertEquals(
            "setupMasterPassword must NOT be invoked when decryption failed",
            null,
            capturingAuth.capturedSetupPassword,
        )
    }
}

// ── Fakes ─────────────────────────────────────────────────────────────────────

/** Fake importer that mirrors BackupEncryption.decrypt's contract: zeros the password
 *  parameter in place, then returns an empty parsed result. */
private open class ZeroingFakeImporter : VaultImporter {
    var parseEncryptedCalls: Int = 0
    var lastReceivedPasswordAfterReturn: CharArray? = null

    override suspend fun isEncrypted(path: String): Boolean = true
    override suspend fun parse(path: String): AppResult<ParsedImport> =
        error("parse is not part of the SetupFromBackup path")

    override suspend fun parseEncrypted(path: String, password: CharArray): AppResult<ParsedImport> {
        parseEncryptedCalls++
        password.fill(' ')
        lastReceivedPasswordAfterReturn = password.copyOf()
        return AppResult.Success(ParsedImport(emptyList(), emptyList()))
    }
}

/** Fake AuthRepository that captures the password handed to setupMasterPassword. */
private class CapturingFakeAuth : AuthRepository {
    var capturedSetupPassword: CharArray? = null

    override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> {
        // Snapshot before any potential zeroing the real implementation might do.
        capturedSetupPassword = password.copyOf()
        return AppResult.Success(Unit)
    }

    // ── unused in this test - fail loudly if accidentally invoked ──
    override fun isSetupComplete(): Flow<Boolean> = flowOf(false)
    override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> = unused()
    override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = unused()
    override suspend fun unlockWithBiometric(): AppResult<Unit> = unused()
    override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = unused()
    override suspend fun setupPin(pin: CharArray): AppResult<Unit> = unused()
    override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> = unused()
    override suspend fun lock(): AppResult<Unit> = unused()
    override fun isUnlocked(): Flow<Boolean> = flowOf(false)
    override fun isPinSetup(): Flow<Boolean> = flowOf(false)
    override suspend fun resetPin(): AppResult<Unit> = unused()
    override suspend fun resetVault(): AppResult<Unit> = unused()
    override suspend fun clearAll(): AppResult<Unit> = unused()

    private fun unused(): Nothing = error("not used by SetupFromBackupUseCase")
}

/** Fake CredentialRepository - only [importCredentials] is reachable, and it's a no-op
 *  because the test's parsed import is empty. */
private class NoopFakeCreds : CredentialRepository {
    override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> =
        AppResult.Success(credentials.size)

    // ── unused ──
    override fun getPagedCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<PagingData<Credential>> = flowOf(PagingData.empty())
    override fun observeCredential(id: String): Flow<Credential?> = flowOf(null)
    override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> = flowOf(null)
    override suspend fun getCredential(id: String): AppResult<Credential> = unused()
    override suspend fun saveCredential(credential: Credential): AppResult<Unit> = unused()
    override suspend fun deleteCredential(id: String): AppResult<Unit> = unused()
    override suspend fun hardDeleteCredential(id: String): AppResult<Unit> = unused()
    override suspend fun restoreCredential(id: String): AppResult<Unit> = unused()
    override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> = unused()
    override suspend fun emptyRecycleBin(): AppResult<Int> = unused()
    override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = unused()
    override fun observeRecycleBin(): Flow<List<Credential>> = flowOf(emptyList())
    override fun observeRecycleBinCount(): Flow<Int> = flowOf(0)
    override suspend fun getAllCredentials(): AppResult<List<Credential>> = unused()
    override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = unused()
    override fun observeCount(): Flow<Int> = flowOf(0)
    override fun observeCountForTag(tag: String): Flow<Int> = flowOf(0)
    override fun observeFavoriteCount(): Flow<Int> = flowOf(0)
    override fun observeFavorites(): Flow<List<Credential>> = flowOf(emptyList())
    override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
    override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
    override fun observeRotatingOtp(): Flow<List<Credential>> = flowOf(emptyList())
    override fun observeHotpEntries(): Flow<List<Credential>> = flowOf(emptyList())
    override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = unused()
    override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> = unused()
    override suspend fun deleteAllCredentials(): AppResult<Unit> = unused()
    override suspend fun markAccessed(id: String): AppResult<Unit> = unused()

    private fun unused(): Nothing = error("not used by SetupFromBackupUseCase")
}
