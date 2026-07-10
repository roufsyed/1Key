package com.roufsyed.onekey.feature.importexport.presentation.viewmodel

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.BackgroundLockTimeout
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialSortOrder
import com.roufsyed.onekey.core.domain.model.InactivityLockTimeout
import com.roufsyed.onekey.core.domain.model.MasterPasswordInterval
import com.roufsyed.onekey.core.domain.model.RecycleBinRetention
import com.roufsyed.onekey.core.domain.model.ThemeMode
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.domain.repository.BiometricUnlockGate
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.SyncGate
import com.roufsyed.onekey.core.domain.usecase.ExportFormat
import com.roufsyed.onekey.core.domain.usecase.ExportVaultUseCase
import com.roufsyed.onekey.core.domain.usecase.ImportVaultUseCase
import com.roufsyed.onekey.core.security.AutoLockManager
import com.roufsyed.onekey.core.security.KdfParams
import com.roufsyed.onekey.core.security.KdfPreset
import com.roufsyed.onekey.core.security.VaultVersionTracker
import com.roufsyed.onekey.feature.importexport.domain.EncryptedExportContext
import com.roufsyed.onekey.feature.importexport.domain.EncryptedParseResult
import com.roufsyed.onekey.feature.importexport.domain.ParsedImport
import com.roufsyed.onekey.feature.importexport.domain.VaultExporter
import com.roufsyed.onekey.feature.importexport.domain.VaultImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the export-password loss bug introduced on 2026-05-09 in
 * commit 102bda9 ("Add Argon2id backup KDF, password strength gating, secure
 * password fields, and unlock lockout").
 *
 * Symptom: the BackupScreen's `ExportPasswordVerified` event handler called
 * `viewModel.setPendingExportPassword(exportPasswordState.consume())` AFTER
 * the verify-button click had already called `consume()` once. The second
 * `consume()` returns an empty `CharArray` because [com.roufsyed.onekey.core.presentation
 * .lockaware.SecurePasswordFieldState.consume] clears the field state on the
 * first read. As a result every encrypted export taken between 2026-05-09 and
 * the fix was encrypted under `Argon2id("" || SK?, salt, params)` rather than
 * the user's master password, and the AEAD tag failed on every restore attempt
 * with the real password ("Wrong password or corrupted backup").
 *
 * The fix moves password ownership into the VM: `verifyPasswordForExport`
 * snapshots the caller's `CharArray` BEFORE `unlockWithPassword` zeroes it,
 * and on `Success` transfers the snapshot into `pendingExportPassword` -
 * the SAF-picker callback then reads the real bytes instead of the empty
 * array a UI-layer `consume()` would have produced.
 *
 * This test pins that VM-level transfer. Reflection is used to read the
 * private `pendingExportPassword` field because there is no public surface
 * that exposes it without also consuming it via `exportEncrypted` (which
 * requires an Android `Uri`/`Context` round-trip and would broaden the test
 * past the invariant it locks).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportExportViewModelExportPasswordTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var appScope: CoroutineScope
    private lateinit var auth: FakeAuthRepository
    private lateinit var appPrefs: FakeAppPreferencesRepository
    private lateinit var credentialRepository: FakeCredentialRepository
    private lateinit var vaultExporter: FakeVaultExporter
    private lateinit var vaultImporter: FakeVaultImporter
    private lateinit var vaultVersionStore: DataStore<Preferences>

    @Before fun setup() {
        // UnconfinedTestDispatcher so viewModelScope.launch lands inline. The
        // VM's `withContext(Dispatchers.Default)` hops to the REAL default
        // dispatcher; waitFor below polls real time to bridge that.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        auth = FakeAuthRepository()
        appPrefs = FakeAppPreferencesRepository()
        credentialRepository = FakeCredentialRepository()
        vaultExporter = FakeVaultExporter()
        vaultImporter = FakeVaultImporter()
        vaultVersionStore = freshStore("vault_version.preferences_pb")
    }

    @After fun teardown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    // ── The regression lock ──────────────────────────────────────────────────

    @Test
    fun successful_verify_transfers_caller_password_into_pendingExportPassword() = runBlocking {
        val vm = buildVm()
        val seenEvents = mutableListOf<ImportExportEvent>()
        val eventJob = appScope.launch { vm.events.collect { seenEvents.add(it) } }

        val typed = "CorrectHorseBatteryStaple1!".toCharArray()
        vm.verifyPasswordForExport(typed)

        waitFor { seenEvents.any { it is ImportExportEvent.ExportPasswordVerified } }
        assertTrue(
            "verifyPasswordForExport must emit ExportPasswordVerified on a valid + correct " +
                "password - the rest of the assertions depend on having reached the success branch",
            seenEvents.any { it is ImportExportEvent.ExportPasswordVerified },
        )

        val pending = readPendingExportPassword(vm)
        assertNotNull(
            "After a successful verify the VM MUST own a non-null pending export password " +
                "snapshot. If this is null, the SAF-picker callback in BackupScreen will " +
                "fall through `pw = pendingExportPassword ?: return` and the export is " +
                "silently dropped on the floor.",
            pending,
        )
        assertEquals(
            "pendingExportPassword MUST equal the caller's typed password verbatim. The bug " +
                "this test guards against produced an empty CharArray here - the export then " +
                "encrypted the backup under Argon2id(\"\" || SK?, salt) instead of the real " +
                "MP, making every restore attempt fail with AEADBadTagException. If you ever " +
                "make BackupScreen's ExportPasswordVerified handler call setPendingExportPassword " +
                "(state.consume()) again, this test will fail because the field state has " +
                "ALREADY been cleared by the verify-button's consume() at submit time.",
            "CorrectHorseBatteryStaple1!",
            String(pending!!),
        )

        eventJob.cancel()
    }

    @Test
    fun snapshot_is_independent_of_callers_array_zero_in_unlockWithPassword() = runBlocking {
        // The real AuthRepository.unlockWithPassword zeroes its CharArray argument
        // as part of verifyMasterPassword's memory-hygiene contract. The FakeAuthRepository
        // below mirrors that behaviour. The snapshot the VM transfers into
        // pendingExportPassword MUST be a copy taken BEFORE that zero, not a reference -
        // otherwise the SAF-picker callback reads all-zero bytes and we're back to the bug.
        val vm = buildVm()
        val seenEvents = mutableListOf<ImportExportEvent>()
        val eventJob = appScope.launch { vm.events.collect { seenEvents.add(it) } }

        val typed = "CorrectHorseBatteryStaple1!".toCharArray()
        vm.verifyPasswordForExport(typed)
        waitFor { seenEvents.any { it is ImportExportEvent.ExportPasswordVerified } }

        // The fake confirmed unlockWithPassword zeroed its argument (this is the contract
        // the real implementation also keeps).
        assertTrue(
            "FakeAuthRepository.unlockWithPassword is expected to mirror the real impl and " +
                "zero its caller-owned CharArray. If this assertion fails, the test no longer " +
                "exercises the regression - the snapshot looks correct only because the source " +
                "array was never zeroed.",
            auth.lastUnlockPasswordWasZeroed,
        )

        val pending = readPendingExportPassword(vm)!!
        assertEquals(
            "pendingExportPassword survived the unlockWithPassword zero - it MUST be a " +
                "defensive copy taken before the call, not the caller's array",
            "CorrectHorseBatteryStaple1!",
            String(pending),
        )

        eventJob.cancel()
    }

    @Test
    fun weak_password_path_does_not_leave_a_pending_export_password() = runBlocking {
        val vm = buildVm()
        val seenEvents = mutableListOf<ImportExportEvent>()
        val eventJob = appScope.launch { vm.events.collect { seenEvents.add(it) } }

        vm.verifyPasswordForExport("abc".toCharArray())  // too short

        waitFor { seenEvents.any { it is ImportExportEvent.ExportPasswordTooWeak } }
        assertEquals(
            "Weak-password branch returns BEFORE the snapshot is taken, so pendingExportPassword " +
                "must remain null. If a stale snapshot leaks here, a subsequent SAF-picker " +
                "callback would encrypt the export with the prior session's password.",
            null,
            readPendingExportPassword(vm),
        )
        eventJob.cancel()
    }

    @Test
    fun wrong_password_path_does_not_leave_a_pending_export_password() = runBlocking {
        auth.unlockResult = AppResult.Error(IllegalStateException("Incorrect master password"))
        val vm = buildVm()
        val seenEvents = mutableListOf<ImportExportEvent>()
        val eventJob = appScope.launch { vm.events.collect { seenEvents.add(it) } }

        vm.verifyPasswordForExport("CorrectHorseBatteryStaple1!".toCharArray())

        waitFor {
            seenEvents.any { it is ImportExportEvent.ExportPasswordFailed } ||
                seenEvents.any { it is ImportExportEvent.ExportVaultLocked }
        }
        assertEquals(
            "On a wrong-password unlock, the snapshot MUST be zeroed in finally (transferred=false " +
                "branch). pendingExportPassword stays null so the next verify attempt starts " +
                "from a clean slate.",
            null,
            readPendingExportPassword(vm),
        )
        eventJob.cancel()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun readPendingExportPassword(vm: ImportExportViewModel): CharArray? {
        val field = ImportExportViewModel::class.java.getDeclaredField("pendingExportPassword")
        field.isAccessible = true
        return field.get(vm) as? CharArray
    }

    private suspend fun waitFor(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    private fun freshStore(name: String): DataStore<Preferences> {
        val file = tempFolder.newFile(name)
        file.delete()
        return PreferenceDataStoreFactory.create(scope = appScope, produceFile = { file })
    }

    private fun buildVm(): ImportExportViewModel {
        val vaultVersionTracker = VaultVersionTracker(vaultVersionStore)
        val exportVaultUseCase = ExportVaultUseCase(
            repository = credentialRepository,
            exporter = vaultExporter,
            vaultVersionTracker = vaultVersionTracker,
            authRepository = auth,
        )
        val importVaultUseCase = ImportVaultUseCase(
            repository = credentialRepository,
            importer = vaultImporter,
        )
        val autoLockManager = AutoLockManager(auth, appPrefs)
        return ImportExportViewModel(
            exportVault = exportVaultUseCase,
            importVault = importVaultUseCase,
            autoLockManager = autoLockManager,
            authRepository = auth,
            appPreferencesRepository = appPrefs,
        )
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    /**
     * Mirrors the real [com.roufsyed.onekey.core.data.repository.AuthRepositoryImpl.unlockWithPassword]
     * memory contract: the caller-owned password CharArray is zeroed in place as part
     * of verifyMasterPassword. The flag [lastUnlockPasswordWasZeroed] lets the test
     * assert the contract still holds so the "snapshot survives the zero" invariant
     * is being exercised end-to-end.
     */
    private class FakeAuthRepository : AuthRepository {
        var unlockResult: AppResult<Unit> = AppResult.Success(Unit)
        var lastUnlockPasswordWasZeroed: Boolean = false

        override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> {
            val outcome = unlockResult
            password.fill(' ')
            lastUnlockPasswordWasZeroed = password.all { it == ' ' }
            return outcome
        }

        override fun isUnlocked(): Flow<Boolean> = flowOf(false)
        override fun isSetupComplete(): Flow<Boolean> = flowOf(true)
        override fun isPinSetup(): Flow<Boolean> = flowOf(false)

        override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> =
            error("unused")
        override suspend fun setupMasterPasswordWithSecretKey(
            password: CharArray,
            secretKey: ByteArray,
        ): AppResult<Unit> = error("unused")
        override suspend fun setupMasterPasswordOptingOutOfSecretKey(
            password: CharArray,
        ): AppResult<Unit> = error("unused")
        override suspend fun setupWithSecretKeyFromBackup(
            password: CharArray,
            secretKey: ByteArray,
        ): AppResult<Unit> = error("unused")
        override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithBiometric(): AppResult<Unit> = error("unused")
        override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun verifyMasterPassword(password: CharArray): AppResult<Unit> =
            error("unused")
        override suspend fun setupPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun changePassword(
            oldPassword: CharArray,
            newPassword: CharArray,
        ): AppResult<Unit> = error("unused")
        override suspend fun lock(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun resetPin(): AppResult<Unit> = error("unused")
        override suspend fun resetVault(): AppResult<Unit> = error("unused")
        override suspend fun clearAll(): AppResult<Unit> = error("unused")
        override fun observeActiveKdfPreset(): Flow<KdfPreset> = flowOf(KdfPreset.STANDARD)
        override fun observeActiveKdfCustomParams(): Flow<Pair<Int, Int>?> = flowOf(null)
        override suspend fun activeKdfParams(): KdfParams = KdfPreset.STANDARD.toKdfParams()
        override suspend fun isSecretKeyEnabled(): Boolean = false
        override fun observeIsSecretKeyEnabled(): Flow<Boolean> = flowOf(false)
        override fun observeSecretKeyOptedOut(): Flow<Boolean> = flowOf(false)
    }

    private class FakeAppPreferencesRepository : AppPreferencesRepository {
        override fun isSyncEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncEnabled(enabled: Boolean) = Unit
        override fun getSyncLocationUri(): Flow<String?> = flowOf(null)
        override suspend fun setSyncLocationUri(uri: String?) = Unit
        override fun isSyncCompletionNotificationEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean) = Unit
        override fun getSyncLastSuccessAt(): Flow<Long> = flowOf(0L)
        override suspend fun setSyncLastSuccessAt(timestamp: Long) = Unit
        override suspend fun getSyncGateDirect(): SyncGate = SyncGate(enabled = false, locationUri = null)
        override fun getBackgroundLockTimeout(): Flow<BackgroundLockTimeout> =
            flowOf(BackgroundLockTimeout.IMMEDIATE)
        override fun getInactivityLockTimeout(): Flow<InactivityLockTimeout> =
            flowOf(InactivityLockTimeout.THIRTY_SECONDS)

        override fun getThemeMode(): Flow<ThemeMode> = error("unused")
        override suspend fun setThemeMode(mode: ThemeMode) = error("unused")
        override fun isBiometricEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setBiometricEnabled(enabled: Boolean) = error("unused")
        override fun isScreenshotsEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setScreenshotsEnabled(enabled: Boolean) = error("unused")
        override suspend fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout) = error("unused")
        override suspend fun setInactivityLockTimeout(timeout: InactivityLockTimeout) = error("unused")
        override fun isMasterPasswordRecheckEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setMasterPasswordRecheckEnabled(enabled: Boolean) = error("unused")
        override fun getMasterPasswordRecheckInterval(): Flow<MasterPasswordInterval> = error("unused")
        override suspend fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval) = error("unused")
        override fun getLastMasterPasswordTimestamp(): Flow<Long> = error("unused")
        override suspend fun setLastMasterPasswordTimestamp(timestamp: Long) = error("unused")
        override fun isShowFavourites(): Flow<Boolean> = error("unused")
        override suspend fun setShowFavourites(show: Boolean) = error("unused")
        override fun getCredentialSortOrder(): Flow<CredentialSortOrder> = error("unused")
        override suspend fun setCredentialSortOrder(order: CredentialSortOrder) = error("unused")
        override fun isHideTopBarOnScroll(): Flow<Boolean> = error("unused")
        override suspend fun setHideTopBarOnScroll(enabled: Boolean) = error("unused")
        override fun isNotesRenderMarkdownEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setNotesRenderMarkdownEnabled(enabled: Boolean) = error("unused")
        override fun isVaultFooterVisible(): Flow<Boolean> = error("unused")
        override suspend fun setVaultFooterVisible(visible: Boolean) = error("unused")
        override fun getRecycleBinRetention(): Flow<RecycleBinRetention> = error("unused")
        override suspend fun setRecycleBinRetention(retention: RecycleBinRetention) = error("unused")
        override fun isRecycleBinEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setRecycleBinEnabled(enabled: Boolean) = error("unused")
        override fun isRestoreLastScreenOnUnlock(): Flow<Boolean> = error("unused")
        override suspend fun setRestoreLastScreenOnUnlock(enabled: Boolean) = error("unused")
        override fun isAutofillEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setAutofillEnabled(enabled: Boolean) = error("unused")
        override fun isAutofillCategoryFilterEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setAutofillCategoryFilterEnabled(enabled: Boolean) = error("unused")
        override fun isAutofillSaveUrlOnCrossHostEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setAutofillSaveUrlOnCrossHostEnabled(enabled: Boolean) = error("unused")
        override fun getLockReasonContext(): Flow<String?> = error("unused")
        override suspend fun getLockReasonContextDirect(): String? = error("unused")
        override suspend fun setLockReasonContext(context: String?) = error("unused")
        override fun getBiometricUnlockGate(): Flow<BiometricUnlockGate> = error("unused")
    }

    /**
     * The export use case calls [getAllCredentials] + [getAllInRecycleBin] at the
     * start of every encrypted export. None of the regression tests below actually
     * trigger an export - only verify - so empty lists are sufficient. The two
     * methods exist on the fake purely so a stray future test that calls
     * [ImportExportViewModel.exportEncrypted] doesn't blow up with `error("unused")`.
     */
    private class FakeCredentialRepository : CredentialRepository {
        override suspend fun getAllCredentials(): AppResult<List<Credential>> = AppResult.Success(emptyList())
        override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = AppResult.Success(emptyList())

        override fun observeCredential(id: String): Flow<Credential?> = flowOf(null)
        override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> = flowOf(null)
        override suspend fun getCredential(id: String): AppResult<Credential> = error("unused")
        override suspend fun saveCredential(credential: Credential): AppResult<Unit> = error("unused")
        override suspend fun deleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun hardDeleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun restoreCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> = error("unused")
        override suspend fun emptyRecycleBin(): AppResult<Int> = error("unused")
        override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = error("unused")
        override fun observeRecycleBin(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeRecycleBinCount(): Flow<Int> = flowOf(0)
        override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> = error("unused")
        override fun observeCount(): Flow<Int> = flowOf(0)
        override fun observeCountForTag(tag: String): Flow<Int> = flowOf(0)
        override fun observeFavoriteCount(): Flow<Int> = flowOf(0)
        override fun observeFavorites(): Flow<List<Credential>> = flowOf(emptyList())
        @Suppress("OVERRIDE_DEPRECATION")
        override fun observeCredentials(
            query: String,
            tag: String,
            sortOrder: CredentialSortOrder,
        ): Flow<List<Credential>> = flowOf(emptyList())
        @Suppress("OVERRIDE_DEPRECATION")
        override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> =
            flowOf(emptyList())
        override fun observeRotatingOtp(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeHotpEntries(): Flow<List<Credential>> = flowOf(emptyList())
        override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = error("unused")
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> = error("unused")
        override suspend fun deleteAllCredentials(): AppResult<Unit> = error("unused")
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }

    private class FakeVaultExporter : VaultExporter {
        override suspend fun export(
            credentials: List<Credential>,
            format: ExportFormat,
            path: String,
        ): AppResult<Unit> = error("unused")

        override suspend fun exportEncrypted(
            credentials: List<Credential>,
            password: CharArray,
            format: ExportFormat,
            path: String,
            context: EncryptedExportContext,
            createdAtMs: Long,
            vaultVersion: Int,
        ): AppResult<Unit> = error("unused")

        override fun serializeForSync(
            credentials: List<Credential>,
            format: ExportFormat,
        ): ByteArray = error("unused")
    }

    private class FakeVaultImporter : VaultImporter {
        override suspend fun isEncrypted(path: String): Boolean = error("unused")
        override suspend fun parse(path: String): AppResult<ParsedImport> = error("unused")
        override suspend fun parseEncrypted(
            path: String,
            password: CharArray,
            secretKey: ByteArray?,
        ): EncryptedParseResult = error("unused")
    }
}
