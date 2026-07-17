package com.roufsyed.onekey.feature.auth.presentation

import android.app.Application
import android.content.Context
import android.net.Uri
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
import com.roufsyed.onekey.core.domain.usecase.SetupFromBackupUseCase
import com.roufsyed.onekey.core.domain.usecase.SetupMasterPasswordUseCase
import com.roufsyed.onekey.core.domain.usecase.UnlockVaultUseCase
import com.roufsyed.onekey.core.security.AuthAttemptsStore
import com.roufsyed.onekey.core.security.AutoLockManager
import com.roufsyed.onekey.core.security.BiometricAttemptTracker
import com.roufsyed.onekey.core.security.KdfParams
import com.roufsyed.onekey.core.security.KdfPreset
import com.roufsyed.onekey.core.security.LockReasonStore
import com.roufsyed.onekey.core.security.PasswordAttemptTracker
import com.roufsyed.onekey.core.security.PinAttemptTracker
import com.roufsyed.onekey.core.security.SecretKeyHolder
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.roufsyed.onekey.feature.importexport.domain.EncryptedParseResult
import com.roufsyed.onekey.feature.importexport.domain.ParsedImport
import com.roufsyed.onekey.feature.importexport.domain.VaultImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Locks in the three Stage-7 contract bullets that the workflow brief calls
 * out:
 *
 *  - Onboarding default-on: completing the SK ceremony without explicit
 *    opt-out -> SK enabled (setupMasterPasswordWithSecretKey is invoked
 *    with the freshly-generated SK).
 *  - Onboarding opt-out: skip + second-confirm path ->
 *    setupMasterPasswordOptingOutOfSecretKey is invoked, the pending SK
 *    bytes are dropped before the MP-only setup runs.
 *  - Restore SK-required: a V5 SecretKeyRequired parse result surfaces
 *    [AuthUiState.SecretKeyRequiredForRestore] BEFORE any Argon2id /
 *    setup work. We assert the AuthRepository's setup methods are NOT
 *    invoked on the SK-required pivot.
 *
 * Robolectric is used because [AuthViewModel.restoreFromEncryptedBackup]
 * touches `Context` (cacheDir file I/O). `application = Application::class`
 * bypasses HiltAndroidApp's eager EncryptedSharedPreferences provisioning,
 * which Robolectric's shadow KeyStore cannot resolve.
 *
 * Attempt-tracker fakes use a real [DataStore] backed by a per-test temp
 * file (mirroring [com.roufsyed.onekey.core.data.repository.NotesDisplayPrefsRepositoryImplTest]).
 * The real DataStore avoids overriding final tracker methods - the lockout
 * state under test is "no lockout" so the trackers behave as no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthViewModelSecretKeyTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appScope: CoroutineScope
    private lateinit var passwordTrackerStore: DataStore<Preferences>
    private lateinit var pinTrackerStore: DataStore<Preferences>
    private lateinit var biometricTrackerStore: DataStore<Preferences>

    private lateinit var auth: RecordingAuthRepository
    private lateinit var appPrefs: FakeAppPreferencesRepository
    private lateinit var importer: RecordingVaultImporter
    private lateinit var credentialRepository: NoopCredentialRepository
    private lateinit var secretKeyHolder: SecretKeyHolder

    @Before fun setup() {
        // Use kotlinx.coroutines.test.UnconfinedTestDispatcher for Main so
        // viewModelScope.launch lands inline. The VM's
        // `withContext(Dispatchers.Default)` hops to the real Default
        // dispatcher; we drive completion via [waitFor] real-time polls.
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        passwordTrackerStore = freshStore("pw_tracker.preferences_pb")
        pinTrackerStore = freshStore("pin_tracker.preferences_pb")
        biometricTrackerStore = freshStore("bio_tracker.preferences_pb")
        auth = RecordingAuthRepository()
        appPrefs = FakeAppPreferencesRepository()
        importer = RecordingVaultImporter()
        credentialRepository = NoopCredentialRepository()
        secretKeyHolder = SecretKeyHolder()
    }

    @After fun teardown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    // -- Onboarding: default-on (SK enabled) ----------------------------------

    @Test
    fun setupWithSecretKey_calls_repository_with_generated_sk() = runBlocking {
        val vm = buildVm()
        vm.ensurePendingSecretKey()
        // Yield through the StandardTestDispatcher so the ensurePendingSecretKey
        // viewModelScope.launch lands. The VM's withContext(Dispatchers.Default)
        // hops to the REAL default dispatcher which TestDispatcher cannot
        // virtualise; we drive the test off real time via waitFor.
        waitFor { vm.pendingSecretKeyCanonical.value != null }
        assertNotNull(
            "ensurePendingSecretKey must publish a canonical printed-form SK",
            vm.pendingSecretKeyCanonical.value,
        )

        vm.setupWithSecretKey("StrongPassword1!".toCharArray())
        waitFor { vm.state.value is AuthUiState.SetupComplete }

        assertEquals(
            "Default-on path MUST call setupMasterPasswordWithSecretKey",
            1,
            auth.setupWithSkCalls,
        )
        assertEquals(
            "Opt-out path MUST NOT be invoked on the default-on path",
            0,
            auth.setupOptOutCalls,
        )
        assertNotNull("SK bytes captured by repo fake", auth.capturedSecretKey)
        assertEquals(
            "Generated SK is the locked 16-byte length",
            SecretKeyHolder.SECRET_KEY_RAW_LENGTH,
            auth.capturedSecretKey?.size,
        )
        assertEquals(AuthUiState.SetupComplete, vm.state.value)
    }

    @Test
    fun ensurePendingSecretKey_is_idempotent_within_a_ceremony() = runBlocking {
        val vm = buildVm()
        vm.ensurePendingSecretKey()
        waitFor { vm.pendingSecretKeyCanonical.value != null }
        val first = vm.pendingSecretKeyCanonical.value
        vm.ensurePendingSecretKey()
        // Idempotent - no state change to wait for. A small delay is fine.
        delay(50)
        val second = vm.pendingSecretKeyCanonical.value
        assertEquals(
            "Calling ensurePendingSecretKey twice must NOT rotate the SK",
            first,
            second,
        )
    }

    @Test
    fun clearPendingSecretKey_drops_canonical_form() = runBlocking {
        val vm = buildVm()
        vm.ensurePendingSecretKey()
        waitFor { vm.pendingSecretKeyCanonical.value != null }
        vm.clearPendingSecretKey()
        waitFor { vm.pendingSecretKeyCanonical.value == null }
        assertNull(
            "clearPendingSecretKey must null the canonical form",
            vm.pendingSecretKeyCanonical.value,
        )
    }

    // -- Onboarding: opt-out path ---------------------------------------------

    @Test
    fun setupSkippingSecretKey_calls_opt_out_path() = runBlocking {
        val vm = buildVm()
        vm.ensurePendingSecretKey()
        waitFor { vm.pendingSecretKeyCanonical.value != null }

        vm.setupSkippingSecretKey("StrongPassword1!".toCharArray())
        waitFor { vm.state.value is AuthUiState.SetupComplete }

        assertEquals(
            "Skip path MUST call setupMasterPasswordOptingOutOfSecretKey",
            1,
            auth.setupOptOutCalls,
        )
        assertEquals(
            "Skip path MUST NOT call setupMasterPasswordWithSecretKey",
            0,
            auth.setupWithSkCalls,
        )
        assertNull(
            "Skip path drops the canonical form so a back-press cannot resubmit it",
            vm.pendingSecretKeyCanonical.value,
        )
        assertEquals(AuthUiState.SetupComplete, vm.state.value)
    }

    @Test
    fun setupSkippingSecretKey_works_even_without_prior_ensurePendingSecretKey() = runBlocking {
        // A user who back-presses the SK ceremony then immediately re-taps
        // Skip without lingering on the page should not see a no-op.
        val vm = buildVm()
        vm.setupSkippingSecretKey("StrongPassword1!".toCharArray())
        waitFor { vm.state.value is AuthUiState.SetupComplete }
        assertEquals(1, auth.setupOptOutCalls)
        assertEquals(AuthUiState.SetupComplete, vm.state.value)
    }

    // -- Restore from backup: SK-required pivot -------------------------------

    @Test
    fun restoreFromEncryptedBackup_pivots_to_SecretKeyRequiredForRestore_when_envelope_demands_sk() = runBlocking {
        importer.nextParseResult = EncryptedParseResult.SecretKeyRequired(
            createdAtMs = 1_700_000_000_000L,
            vaultVersion = 42,
        )
        val vm = buildVm()
        val ctx = RuntimeEnvironment.getApplication() as Context
        // Robolectric serves file:// URIs through its ContentResolver shadow.
        val tmp = File(ctx.cacheDir, "fake_backup.1key").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        val uri = Uri.fromFile(tmp)

        vm.restoreFromEncryptedBackup(uri, "BackupPassword!1".toCharArray(), ctx)
        waitFor { vm.state.value is AuthUiState.SecretKeyRequiredForRestore }

        val state = vm.state.value
        assertTrue(
            "SK-required envelope MUST surface SecretKeyRequiredForRestore (was ${state::class.simpleName})",
            state is AuthUiState.SecretKeyRequiredForRestore,
        )
        state as AuthUiState.SecretKeyRequiredForRestore
        assertEquals(1_700_000_000_000L, state.backupCreatedAtMs)
        assertEquals(42, state.backupVaultVersion)

        // No setup variant should have been invoked - the pivot is BEFORE
        // any vault-state commit.
        assertEquals(
            "SK-required pivot must NOT invoke setupMasterPassword",
            0,
            auth.setupBaseCalls,
        )
        assertEquals(
            "SK-required pivot must NOT invoke setupMasterPasswordWithSecretKey",
            0,
            auth.setupWithSkCalls,
        )
        assertEquals(
            "SK-required pivot must NOT invoke setupWithSecretKeyFromBackup",
            0,
            auth.setupFromBackupSkCalls,
        )

        // The preserved password should not be the zeroed source - it is a
        // defensive copy that the retry path consumes.
        assertArrayEquals(
            "Preserved password must equal the original entered value",
            "BackupPassword!1".toCharArray(),
            state.pendingPassword,
        )
    }

    @Test
    fun restoreFromEncryptedBackup_surfaces_failure_when_password_wrong() = runBlocking {
        importer.nextParseResult = EncryptedParseResult.Failure(
            throwable = IllegalStateException("Wrong password or corrupted backup"),
            message = "Wrong password or corrupted backup",
        )
        val vm = buildVm()
        val ctx = RuntimeEnvironment.getApplication() as Context
        val tmp = File(ctx.cacheDir, "fake_backup_wrong.1key").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        val uri = Uri.fromFile(tmp)
        vm.restoreFromEncryptedBackup(uri, "WrongPassword!1".toCharArray(), ctx)
        waitFor { vm.state.value is AuthUiState.Error }
        val state = vm.state.value
        assertTrue(
            "Wrong password must surface AuthUiState.Error - not the SK pivot",
            state is AuthUiState.Error,
        )
        // setupMasterPassword / setupWithSecretKey / setupWithSecretKeyFromBackup
        // MUST NOT run on a wrong-password parse.
        assertEquals(0, auth.setupWithSkCalls)
        assertEquals(0, auth.setupFromBackupSkCalls)
    }

    @Test
    fun restoreWithSecretKey_wrong_sk_preserves_pivot_state_and_surfaces_error() = runBlocking {
        // Bug 3 regression: a wrong-SK retry must NOT drop the user back to
        // a generic AuthUiState.Error (which would lose the preserved file +
        // password context and force a full re-pick). Instead the VM must
        // re-emit AuthUiState.SecretKeyRequiredForRestore with the failure
        // message attached, so the dialog can render it in supportingText
        // and the user can retry by typing a fresh SK without re-uploading.

        // Stage 1: initial restore pivots to SK-required.
        importer.nextParseResult = EncryptedParseResult.SecretKeyRequired(
            createdAtMs = 1_700_000_000_000L,
            vaultVersion = 5,
        )
        val vm = buildVm()
        val ctx = RuntimeEnvironment.getApplication() as Context
        val tmp = File(ctx.cacheDir, "fake_backup_sk_retry.1key").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        val uri = Uri.fromFile(tmp)
        vm.restoreFromEncryptedBackup(uri, "BackupPassword!1".toCharArray(), ctx)
        waitFor { vm.state.value is AuthUiState.SecretKeyRequiredForRestore }
        val initialPivot = vm.state.value as AuthUiState.SecretKeyRequiredForRestore
        assertNull(
            "Initial pivot must NOT carry an error",
            initialPivot.error,
        )

        // Stage 2: retry with a (well-formed) but wrong SK. The importer
        // returns Failure on this second parseEncrypted call, so the VM
        // must re-emit the pivot state - not a generic Error - and the
        // pendingPassword must still be readable.
        importer.nextParseResult = EncryptedParseResult.Failure(
            throwable = IllegalStateException("Wrong Secret Key"),
            message = "Wrong Secret Key or corrupted backup",
        )
        // A well-formed 26-char Crockford SK that canonicalSkToBytes will
        // accept (the decoder cares about character set + length, not
        // semantic correctness against the backup).
        // 26 zeros decode cleanly to all-zero raw bytes - the canonicalSkToBytes
        // rejects characters outside Crockford base32, and the trailing two
        // padding bits must be zero (so the last char's value mod 4 must be 0;
        // "0" trivially satisfies that). The SK is well-formed but does not
        // match anything, so the importer fake's Failure result drives the
        // path under test.
        val canonical = "0".repeat(26)
        vm.restoreFromEncryptedBackupWithSecretKey(canonical, ctx)

        // The state should leave Loading and land back on the pivot with
        // the error attached. Wait for the loop to settle.
        waitFor {
            val s = vm.state.value
            s is AuthUiState.SecretKeyRequiredForRestore && s.error != null
        }

        val afterFailure = vm.state.value
        assertTrue(
            "Wrong-SK retry MUST keep AuthUiState.SecretKeyRequiredForRestore (was ${afterFailure::class.simpleName})",
            afterFailure is AuthUiState.SecretKeyRequiredForRestore,
        )
        afterFailure as AuthUiState.SecretKeyRequiredForRestore
        assertEquals(
            "Failure message must be propagated onto the pivot state's error field",
            "Wrong Secret Key or corrupted backup",
            afterFailure.error,
        )
        assertEquals(
            "Backup metadata must be preserved across the retry",
            1_700_000_000_000L,
            afterFailure.backupCreatedAtMs,
        )
        assertEquals(5, afterFailure.backupVaultVersion)
        // Critical: the preserved password must still equal the original
        // entered value. The bug being fixed was that the retry's finally
        // zeroed the only live copy via the aliased CharArray reference,
        // leaving the "preserved" snapshot full of spaces after one failed
        // attempt. Verify we have clean bytes for the next retry.
        assertArrayEquals(
            "Preserved password must NOT be zeroed after a failed retry",
            "BackupPassword!1".toCharArray(),
            afterFailure.pendingPassword,
        )

        // The setup-with-SK-from-backup repo path must NOT have run on a
        // Failure parse.
        assertEquals(
            "Failed SK retry must NOT call setupWithSecretKeyFromBackup",
            0,
            auth.setupFromBackupSkCalls,
        )
    }

    @Test
    fun clearSkRestoreError_drops_error_but_keeps_pivot_state() = runBlocking {
        // Bug 3 regression: typing in the SK input after a failed retry
        // must clear the supportingText immediately without losing the
        // preserved file + password.
        importer.nextParseResult = EncryptedParseResult.SecretKeyRequired(
            createdAtMs = 42L,
            vaultVersion = 5,
        )
        val vm = buildVm()
        val ctx = RuntimeEnvironment.getApplication() as Context
        val tmp = File(ctx.cacheDir, "fake_backup_clear_err.1key").apply {
            writeBytes(byteArrayOf(0x01))
        }
        val uri = Uri.fromFile(tmp)
        vm.restoreFromEncryptedBackup(uri, "MyPassword".toCharArray(), ctx)
        waitFor { vm.state.value is AuthUiState.SecretKeyRequiredForRestore }

        // Drive a failed retry to populate the error field.
        importer.nextParseResult = EncryptedParseResult.Failure(
            throwable = IllegalStateException("nope"),
            message = "Wrong Secret Key",
        )
        vm.restoreFromEncryptedBackupWithSecretKey("0".repeat(26), ctx)
        waitFor {
            val s = vm.state.value
            s is AuthUiState.SecretKeyRequiredForRestore && s.error != null
        }
        val withError = vm.state.value as AuthUiState.SecretKeyRequiredForRestore
        assertEquals("Wrong Secret Key", withError.error)

        // clearSkRestoreError() drops the message while keeping the pivot.
        vm.clearSkRestoreError()
        // No coroutine here - the call is synchronous. Pause briefly so
        // the StateFlow emission propagates to .value, then assert.
        waitFor {
            val s = vm.state.value
            s is AuthUiState.SecretKeyRequiredForRestore && s.error == null
        }
        val cleared = vm.state.value
        assertTrue(
            "clearSkRestoreError must KEEP the SK-required pivot state (was ${cleared::class.simpleName})",
            cleared is AuthUiState.SecretKeyRequiredForRestore,
        )
        cleared as AuthUiState.SecretKeyRequiredForRestore
        assertNull(
            "clearSkRestoreError must drop the error field",
            cleared.error,
        )
        assertEquals(42L, cleared.backupCreatedAtMs)
        assertEquals(5, cleared.backupVaultVersion)
        // Password snapshot must still be readable so the next retry has
        // clean bytes to work with.
        assertArrayEquals(
            "Preserved password must survive clearSkRestoreError",
            "MyPassword".toCharArray(),
            cleared.pendingPassword,
        )
    }

    @Test
    fun restoreWithSecretKey_malformed_sk_preserves_pivot_state() = runBlocking {
        // Bug 3 regression: a malformed canonical SK string (caught by
        // canonicalSkToBytes throwing IllegalArgumentException) must surface
        // the message on the pivot state - not drop to AuthUiState.Error.
        importer.nextParseResult = EncryptedParseResult.SecretKeyRequired(
            createdAtMs = 0L,
            vaultVersion = 5,
        )
        val vm = buildVm()
        val ctx = RuntimeEnvironment.getApplication() as Context
        val tmp = File(ctx.cacheDir, "fake_backup_malformed_sk.1key").apply {
            writeBytes(byteArrayOf(0x01))
        }
        val uri = Uri.fromFile(tmp)
        vm.restoreFromEncryptedBackup(uri, "MyPassword".toCharArray(), ctx)
        waitFor { vm.state.value is AuthUiState.SecretKeyRequiredForRestore }

        // "I" is excluded from the Crockford alphabet - decode throws
        // IllegalArgumentException, which the VM must translate into a
        // pivot-preserving error message.
        vm.restoreFromEncryptedBackupWithSecretKey("I".repeat(26), ctx)
        waitFor {
            val s = vm.state.value
            s is AuthUiState.SecretKeyRequiredForRestore && s.error != null
        }
        val state = vm.state.value
        assertTrue(
            "Malformed SK input must KEEP the SK-required pivot state",
            state is AuthUiState.SecretKeyRequiredForRestore,
        )
        state as AuthUiState.SecretKeyRequiredForRestore
        assertNotNull(
            "Malformed SK must populate the error field on the pivot",
            state.error,
        )
        assertArrayEquals(
            "Preserved password must survive a malformed-SK retry",
            "MyPassword".toCharArray(),
            state.pendingPassword,
        )
    }

    @Test
    fun clearPendingSecretKeyRestore_returns_to_Idle_and_zeros_password() = runBlocking {
        importer.nextParseResult = EncryptedParseResult.SecretKeyRequired(
            createdAtMs = 1L,
            vaultVersion = 0,
        )
        val vm = buildVm()
        val ctx = RuntimeEnvironment.getApplication() as Context
        val tmp = File(ctx.cacheDir, "fake_backup_clear.1key").apply {
            writeBytes(byteArrayOf(0x01))
        }
        val uri = Uri.fromFile(tmp)
        vm.restoreFromEncryptedBackup(uri, "MyBackupPassword".toCharArray(), ctx)
        waitFor { vm.state.value is AuthUiState.SecretKeyRequiredForRestore }
        val state = vm.state.value as AuthUiState.SecretKeyRequiredForRestore

        vm.clearPendingSecretKeyRestore()
        waitFor { vm.state.value == AuthUiState.Idle }
        assertEquals(AuthUiState.Idle, vm.state.value)
        assertFalse(
            "Preserved password must be zeroed after clearPendingSecretKeyRestore",
            state.pendingPassword.any { it != ' ' },
        )
    }

    /**
     * Polls [condition] on the Main test dispatcher until it becomes true.
     * Used by tests that drive AuthViewModel coroutines which hop to the
     * REAL [Dispatchers.Default] inside `withContext`; that hop escapes the
     * virtual-time scheduler so `advanceUntilIdle` cannot deterministically
     * drain the launch. A short real-time poll is sufficient because the
     * test fakes return immediately.
     */
    private suspend fun waitFor(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                delay(20)
            }
        }
    }

    // -- helpers --------------------------------------------------------------

    private fun freshStore(name: String): DataStore<Preferences> {
        val file = tempFolder.newFile(name)
        // PreferenceDataStoreFactory writes to the produceFile lazily; an
        // empty file works, but it does so as a no-op until first write.
        // Delete so it is created as a fresh proto on first write/read.
        file.delete()
        return PreferenceDataStoreFactory.create(
            scope = appScope,
            produceFile = { file },
        )
    }

    private fun buildVm(): AuthViewModel {
        return AuthViewModel(
            authRepository = auth,
            appPrefs = appPrefs,
            setupMasterPassword = SetupMasterPasswordUseCase(auth),
            unlockVault = UnlockVaultUseCase(auth),
            setupFromBackup = SetupFromBackupUseCase(auth, importer, credentialRepository),
            lockReasonStore = LockReasonStore(appPrefs, appScope),
            autoLockManager = AutoLockManager(auth, appPrefs),
            authAttemptsStore = AuthAttemptsStore(),
            passwordAttemptTracker = PasswordAttemptTracker(passwordTrackerStore),
            pinAttemptTracker = PinAttemptTracker(pinTrackerStore),
            biometricAttemptTracker = BiometricAttemptTracker(biometricTrackerStore),
            secretKeyHolder = secretKeyHolder,
            importer = importer,
            credentialRepository = credentialRepository,
        )
    }

    /**
     * Fake [AuthRepository] that records how many times each setup variant
     * was invoked and captures the SK bytes handed to the SK-enabled path.
     * Everything not exercised by these tests fails loudly.
     */
    private class RecordingAuthRepository : AuthRepository {
        var setupBaseCalls = 0
        var setupWithSkCalls = 0
        var setupOptOutCalls = 0
        var setupFromBackupSkCalls = 0
        var capturedSecretKey: ByteArray? = null

        override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> {
            setupBaseCalls++
            password.fill(' ')
            return AppResult.Success(Unit)
        }

        override suspend fun setupMasterPasswordWithSecretKey(
            password: CharArray,
            secretKey: ByteArray,
        ): AppResult<Unit> {
            setupWithSkCalls++
            capturedSecretKey = secretKey.copyOf()
            password.fill(' ')
            return AppResult.Success(Unit)
        }

        override suspend fun setupMasterPasswordOptingOutOfSecretKey(
            password: CharArray,
        ): AppResult<Unit> {
            setupOptOutCalls++
            password.fill(' ')
            return AppResult.Success(Unit)
        }

        override suspend fun setupWithSecretKeyFromBackup(
            password: CharArray,
            secretKey: ByteArray,
        ): AppResult<Unit> {
            setupFromBackupSkCalls++
            capturedSecretKey = secretKey.copyOf()
            password.fill(' ')
            return AppResult.Success(Unit)
        }

        override fun isSetupComplete(): Flow<Boolean> = flowOf(false)
        override fun isUnlocked(): Flow<Boolean> = flowOf(false)
        override fun isPinSetup(): Flow<Boolean> = flowOf(false)
        override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithBiometric(): AppResult<Unit> = error("unused")
        override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun verifyMasterPassword(password: CharArray): AppResult<Unit> = error("unused")
        override suspend fun setupPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> = error("unused")
        override suspend fun lock(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun resetPin(): AppResult<Unit> = error("unused")
        override suspend fun resetVault(): AppResult<Unit> = error("unused")
        override suspend fun clearAll(): AppResult<Unit> = error("unused")
        override fun observeActiveKdfPreset(): Flow<KdfPreset> = error("unused")
        override fun observeActiveKdfCustomParams(): Flow<Pair<Int, Int>?> = error("unused")
        override suspend fun activeKdfParams(): KdfParams = error("unused")
        override suspend fun isSecretKeyEnabled(): Boolean = false
        override fun observeIsSecretKeyEnabled(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
        override fun observeSecretKeyOptedOut(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
    }

    /**
     * Recording [VaultImporter] that returns a programmable parseEncrypted
     * outcome. Default is a Success with an empty parsed import; tests
     * override before driving the VM.
     */
    private class RecordingVaultImporter : VaultImporter {
        var nextParseResult: EncryptedParseResult =
            EncryptedParseResult.Success(ParsedImport(emptyList(), emptyList()))
        var parseEncryptedCalls = 0
        var lastReceivedSecretKey: ByteArray? = null

        override suspend fun isEncrypted(path: String): Boolean = true
        override suspend fun parse(path: String): AppResult<ParsedImport> = error("unused")

        override suspend fun parseEncrypted(
            path: String,
            password: CharArray,
            secretKey: ByteArray?,
        ): EncryptedParseResult {
            parseEncryptedCalls++
            lastReceivedSecretKey = secretKey?.copyOf()
            password.fill(' ')
            return nextParseResult
        }
    }

    /** Empty CredentialRepository - the SetupFromBackup tests are not invoked here. */
    private class NoopCredentialRepository : CredentialRepository {
        override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> =
            AppResult.Success(credentials.size)

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
        override suspend fun getAllCredentials(): AppResult<List<Credential>> = error("unused")
        override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = error("unused")
        override fun observeCount(): Flow<Int> = flowOf(0)
        override fun observeCountForTag(tag: String): Flow<Int> = flowOf(0)
        override fun observeFavoriteCount(): Flow<Int> = flowOf(0)
        override fun observeFavorites(): Flow<List<Credential>> = flowOf(emptyList())
        @Suppress("OVERRIDE_DEPRECATION")
        override fun observeCredentials(query: String, tag: String, sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        @Suppress("OVERRIDE_DEPRECATION")
        override fun observeFavoritesSorted(sortOrder: CredentialSortOrder): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeRotatingOtp(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeHotpEntries(): Flow<List<Credential>> = flowOf(emptyList())
        override suspend fun incrementHotpCounter(credentialId: String): AppResult<Long?> = error("unused")
        override suspend fun toggleFavorite(id: String, isFavorite: Boolean): AppResult<Unit> = error("unused")
        override suspend fun deleteAllCredentials(): AppResult<Unit> = error("unused")
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }

    /**
     * Minimal AppPreferencesRepository fake covering only the surface
     * AuthViewModel touches in the SK paths. Everything else throws so a
     * future ill-placed dependency is loud.
     */
    private class FakeAppPreferencesRepository : AppPreferencesRepository {
        private var lastMpTs = 0L
        override suspend fun setLastMasterPasswordTimestamp(timestamp: Long) {
            lastMpTs = timestamp
        }
        override fun getLastMasterPasswordTimestamp(): Flow<Long> = flowOf(0L)
        override fun isMasterPasswordRecheckEnabled(): Flow<Boolean> = flowOf(false)
        override fun getMasterPasswordRecheckInterval(): Flow<MasterPasswordInterval> =
            flowOf(MasterPasswordInterval.WEEK_1)
        override fun isBiometricEnabled(): Flow<Boolean> = flowOf(false)
        override fun getBiometricUnlockGate(): Flow<BiometricUnlockGate> =
            flowOf(BiometricUnlockGate(false, false))
        override fun getLockReasonContext(): Flow<String?> = flowOf(null)
        override suspend fun getLockReasonContextDirect(): String? = null
        override suspend fun setLockReasonContext(context: String?) {}
        override fun getAcknowledgedAttestationReason(): Flow<String?> = flowOf(null)
        override suspend fun setAcknowledgedAttestationReason(reason: String) {}

        override fun getThemeMode(): Flow<ThemeMode> = error("unused")
        override suspend fun setThemeMode(mode: ThemeMode) = error("unused")
        override suspend fun setBiometricEnabled(enabled: Boolean) = error("unused")
        override fun isScreenshotsEnabled(): Flow<Boolean> = error("unused")
        override suspend fun setScreenshotsEnabled(enabled: Boolean) = error("unused")
        override fun getBackgroundLockTimeout(): Flow<BackgroundLockTimeout> = error("unused")
        override suspend fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout) = error("unused")
        override fun getInactivityLockTimeout(): Flow<InactivityLockTimeout> = error("unused")
        override suspend fun setInactivityLockTimeout(timeout: InactivityLockTimeout) = error("unused")
        override suspend fun setMasterPasswordRecheckEnabled(enabled: Boolean) = error("unused")
        override suspend fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval) = error("unused")
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
        override fun isSyncEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncEnabled(enabled: Boolean) = error("unused")
        override fun getSyncLocationUri(): Flow<String?> = flowOf(null)
        override suspend fun setSyncLocationUri(uri: String?) = error("unused")
        override fun isSyncCompletionNotificationEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean) = error("unused")
        override fun getSyncLastSuccessAt(): Flow<Long> = flowOf(0L)
        override suspend fun setSyncLastSuccessAt(timestamp: Long) = error("unused")
        override suspend fun getSyncGateDirect(): SyncGate =
            SyncGate(enabled = false, locationUri = null)
    }
}
