package com.onekey.feature.settings.presentation.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.model.TagWithCount
import com.onekey.core.domain.model.ThemeMode
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.BiometricUnlockGate
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.SyncGate
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.DeleteTagUseCase
import com.onekey.core.domain.usecase.ResetVaultUseCase
import com.onekey.core.domain.usecase.SeedDataUseCase
import com.onekey.core.security.AuthAttemptsStore
import com.onekey.core.security.CapacitySnapshot
import com.onekey.core.security.CryptoManager
import com.onekey.core.security.DeviceCapacityDetector
import com.onekey.core.security.HardwareKeyIsolationProbe
import com.onekey.core.security.KdfBenchmark
import com.onekey.core.security.KdfMigrator
import com.onekey.core.security.KdfPreset
import com.onekey.core.security.LockReasonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Behavioural locks for [SettingsViewModel]'s KDF-preset surface.
 *
 * # Scope
 *
 * What this file pins:
 *  - [SettingsViewModel.selectPreset] refuses [KdfPreset.CUSTOM] via a
 *    `require()` precondition. Routing CUSTOM through `selectPreset` would
 *    forward the enum's sentinel `(-1, -1)` params to `KdfMigrator` and
 *    blow up at `KdfParams.init` - the precondition is the design's chosen
 *    fail-loud point.
 *  - [SettingsViewModel.activeKdfPreset] and `activeKdfCustomParams` are
 *    wired through to the AuthRepository's observable streams unchanged.
 *  - [SettingsViewModel.deviceCapacity] surfaces the detector's snapshot
 *    exactly - it is the picker's "Recommended" + "Disabled" chip source.
 *  - The OOM-safe gating contract: with a constrained-device snapshot,
 *    `deviceCapacity.value.enabledPresets` excludes Hardened / Maximum.
 *    The picker disables those rows.
 *  - Custom param clamping: `deviceCapacity.value.maxArgon2MemoryMb` is the
 *    cap the KdfCustomDialog binds its memory-slider valueRange to. The VM
 *    must surface this number unmodified from the detector.
 *  - The reauth-failure path on `selectPreset` / `applyCustom` zeros the
 *    caller's password CharArray.
 *
 * # What is NOT covered here
 *
 * Full migration round-trip: that path runs Argon2id, whose native library
 * does not load on the host-side JVM Robolectric uses. The end-to-end
 * migration test lives in `androidTest` per the design plan
 * (`KdfMigrationE2ETest`).
 *
 * Migrator-interaction assertions (capturing the forwarded params from
 * `selectPreset` to `KdfMigrator.migrateTo`) cannot use a Mockito-style
 * spy because the production migrator is `final` and the project has no
 * Mockito dependency. The migrator's behaviour is covered directly by
 * `KdfMigratorTest`; the VM-level coverage here observes the routing path
 * via the password-zeroing contract and the require() precondition shape.
 *
 * `application = Application::class` follows the suite convention -
 * bypasses HiltAndroidApp's eager EncryptedSharedPreferences provisioning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsKdfStrengthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: CoroutineScope

    private lateinit var auth: FakeAuthRepository
    private lateinit var appPrefs: FakeAppPreferencesRepository
    private lateinit var detector: FixedDeviceCapacityDetector
    private lateinit var benchmark: KdfBenchmark
    private lateinit var migrator: KdfMigrator
    private lateinit var datastore: StubPreferencesDataStore
    private lateinit var authPrefs: android.content.SharedPreferences

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testScope = CoroutineScope(SupervisorJob() + testDispatcher)
        auth = FakeAuthRepository()
        appPrefs = FakeAppPreferencesRepository()
        datastore = StubPreferencesDataStore()

        val context: Context = RuntimeEnvironment.getApplication()
        // Default detector reports Hardened tier (mid-range device).
        detector = FixedDeviceCapacityDetector(
            context = context,
            snapshot = capacity(
                totalRamMb = 4_096L,
                isLowRamDevice = false,
                maxCustomMb = 256,
                recommended = KdfPreset.HARDENED,
                enabled = setOf(
                    KdfPreset.STANDARD,
                    KdfPreset.STANDARD_PLUS,
                    KdfPreset.HARDENED,
                ),
            ),
        )

        // Real KdfBenchmark and KdfMigrator with stub backing storage. They
        // are `final` in production so we cannot subclass; instead we drive
        // them via inputs that never reach Argon2id:
        //  - benchmark: backed by a stub DataStore that returns empty
        //    preferences, so `getCachedTiming()` always reports null and
        //    `benchmark()` never persists (and never gets called from the
        //    paths these tests exercise).
        //  - migrator: backed by an empty SharedPreferences (no verifier
        //    stored), so any `migrateTo` call returns Error at the
        //    verifier-read step BEFORE running Argon2id.
        benchmark = KdfBenchmark(datastore)
        authPrefs = context.getSharedPreferences("kdf_vm_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        authPrefs.edit().clear().commit()
        // SecretKeyKeystoreWrapper is constructor-only - it does not touch
        // the Keystore until wrap()/unwrap() is called. The KDF-migration
        // tests in this file never exercise an SK transition, so the wrapper
        // injection is satisfied with a thin instance over the same
        // in-memory SharedPreferences.
        val secretKeyWrapper = com.onekey.core.security.SecretKeyKeystoreWrapper(authPrefs)
        migrator = KdfMigrator(authPrefs, NoKeystoreCryptoManager(), detector, secretKeyWrapper)

        // Stub the ActivityManager so HardwareKeyIsolationProbe (constructed
        // inside the VM via its dependency) sees a coherent device state if
        // it ever runs.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().apply {
            totalMem = 4L * 1024L * 1024L * 1024L
        }
        shadowOf(am).setMemoryInfo(mi)
        shadowOf(am).setIsLowRamDevice(false)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        testScope.coroutineContext[Job]!!.cancel()
    }

    // ── selectPreset(CUSTOM) precondition ─────────────────────────────────

    @Test fun selectPreset_with_CUSTOM_throws_IllegalArgumentException() {
        // selectPreset is the four-fixed-preset entry point. CUSTOM goes
        // through `applyCustom` which carries the explicit slider-derived
        // KdfParams. Routing CUSTOM through `selectPreset` would forward
        // `KdfPreset.CUSTOM.toKdfParams()` - which throws because CUSTOM's
        // enum values are (-1, -1) sentinels. The `require()` is the
        // design's chosen fail-loud point ahead of that throw.
        val vm = buildVm()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            vm.selectPreset(KdfPreset.CUSTOM, "x".toCharArray())
        }
        assertNotNull(ex.message)
        assertTrue(
            "Exception message must direct callers to applyCustom: ${ex.message}",
            ex.message!!.contains("applyCustom") || ex.message!!.contains("CUSTOM"),
        )
    }

    @Test fun selectPreset_accepts_each_of_the_four_fixed_presets_without_throwing() {
        // No precondition trips for STANDARD / STANDARD_PLUS / HARDENED /
        // MAXIMUM. The body launches a coroutine that we don't wait for; we
        // just confirm the synchronous `require()` check passes.
        val vm = buildVm()
        listOf(
            KdfPreset.STANDARD,
            KdfPreset.STANDARD_PLUS,
            KdfPreset.HARDENED,
            KdfPreset.MAXIMUM,
        ).forEach { preset ->
            // Should not throw IllegalArgumentException.
            vm.selectPreset(preset, "anything".toCharArray())
        }
    }

    // ── Password zeroing contract ─────────────────────────────────────────

    @Test fun selectPreset_zeros_the_password_in_finally_after_reauth_failure() = runTest(testDispatcher) {
        // The reauth gate fails -> the VM emits KdfPresetConfirmFailed and
        // zeros the password in the finally block. Pin that path.
        val vm = buildVm()
        auth.verifyResult = AppResult.Error(IllegalStateException("nope"), "Incorrect")
        val password = "secretmasterpassword".toCharArray()

        vm.selectPreset(KdfPreset.HARDENED, password)
        advanceUntilIdle()

        assertTrue(
            "VM must zero the caller's password array on the failure path",
            password.all { it == ' ' },
        )
    }

    @Test fun applyCustom_zeros_the_password_in_finally_after_reauth_failure() = runTest(testDispatcher) {
        val vm = buildVm()
        auth.verifyResult = AppResult.Error(IllegalStateException("nope"), "Incorrect")
        val password = "secretmasterpassword".toCharArray()

        vm.applyCustom(
            params = com.onekey.core.security.KdfParams(mCostKiB = 96 * 1024, tCost = 5, parallelism = 1),
            password = password,
        )
        advanceUntilIdle()

        assertTrue(password.all { it == ' ' })
    }

    // ── deviceCapacity surface ────────────────────────────────────────────

    @Test fun deviceCapacity_StateFlow_reflects_detector_snapshot_exactly() {
        // The picker reads `deviceCapacity.value.enabledPresets` for
        // gating and `.maxArgon2MemoryMb` for the Custom slider's valueRange.
        // The detector is the source of truth; the VM must not transform.
        val vm = buildVm()
        val snapshot = vm.deviceCapacity.value

        assertSame(
            "VM must surface the detector's snapshot identity unchanged",
            detector.snapshot,
            snapshot,
        )
    }

    @Test fun deviceCapacity_exposes_recommendedPreset_for_picker_chip() {
        val vm = buildVm()
        assertEquals(
            "Recommended chip must reflect the detector's classification",
            KdfPreset.HARDENED,
            vm.deviceCapacity.value.recommendedPreset,
        )
    }

    // ── OOM-safe gating: enabled set excludes presets above tier ──────────

    @Test fun deviceCapacity_enabledPresets_excludes_MAXIMUM_on_a_4GB_device() {
        // The picker disables rows whose preset is not in this set. With
        // a 4 GB device (Hardened tier), MAXIMUM must NOT appear - the
        // picker would otherwise let the user pick a preset that the
        // device cannot safely run.
        val vm = buildVm()
        val enabled = vm.deviceCapacity.value.enabledPresets

        assertTrue("Standard tier must always be enabled", enabled.contains(KdfPreset.STANDARD))
        assertTrue("Standard-plus must be enabled on 4 GB devices", enabled.contains(KdfPreset.STANDARD_PLUS))
        assertTrue("Hardened must be enabled on a 4 GB device", enabled.contains(KdfPreset.HARDENED))
        assertFalse(
            "Maximum must NOT be enabled on a 4 GB device",
            enabled.contains(KdfPreset.MAXIMUM),
        )
    }

    @Test fun deviceCapacity_on_a_lowRam_device_caps_at_Standard_tier() {
        // Re-seed the detector with a low-RAM profile and rebuild the VM.
        // The picker's enabled set MUST cap at {Standard, Standard-plus}
        // even if the underlying RAM number would otherwise unlock more.
        val context: Context = RuntimeEnvironment.getApplication()
        detector = FixedDeviceCapacityDetector(
            context = context,
            snapshot = capacity(
                totalRamMb = 8_192L,  // 8 GB physical but...
                isLowRamDevice = true,  // ...OS flagged as low-RAM
                maxCustomMb = 256,
                recommended = KdfPreset.STANDARD,
                enabled = setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS),
            ),
        )
        val vm = buildVm()
        val enabled = vm.deviceCapacity.value.enabledPresets

        assertEquals(
            "Low-RAM device must offer only Standard + Standard-plus",
            setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS),
            enabled,
        )
    }

    @Test fun deviceCapacity_on_a_high_RAM_device_enables_every_fixed_preset() {
        val context: Context = RuntimeEnvironment.getApplication()
        detector = FixedDeviceCapacityDetector(
            context = context,
            snapshot = capacity(
                totalRamMb = 8_192L,
                isLowRamDevice = false,
                maxCustomMb = 256,
                recommended = KdfPreset.MAXIMUM,
                enabled = setOf(
                    KdfPreset.STANDARD,
                    KdfPreset.STANDARD_PLUS,
                    KdfPreset.HARDENED,
                    KdfPreset.MAXIMUM,
                ),
            ),
        )
        val vm = buildVm()
        val enabled = vm.deviceCapacity.value.enabledPresets

        assertEquals(
            "High-RAM device must enable every fixed preset",
            setOf(
                KdfPreset.STANDARD,
                KdfPreset.STANDARD_PLUS,
                KdfPreset.HARDENED,
                KdfPreset.MAXIMUM,
            ),
            enabled,
        )
    }

    // ── Custom param clamping: maxArgon2MemoryMb honours device cap ──────

    @Test fun deviceCapacity_maxArgon2MemoryMb_surfaces_detector_cap_unmodified() {
        val vm = buildVm()
        assertEquals(
            "VM must surface the detector's maxArgon2MemoryMb cap unmodified",
            detector.snapshot.maxArgon2MemoryMb,
            vm.deviceCapacity.value.maxArgon2MemoryMb,
        )
    }

    @Test fun deviceCapacity_maxArgon2MemoryMb_floors_at_32_MiB_on_tiny_devices() {
        // 32 MiB is the OWASP 2023 floor and the KdfCustomDialog's
        // `CUSTOM_M_MIN`. Even on a degenerate device the detector's cap
        // must be >= 32 so the slider's valueRange `32..max` is well-formed.
        val context: Context = RuntimeEnvironment.getApplication()
        detector = FixedDeviceCapacityDetector(
            context = context,
            snapshot = capacity(
                totalRamMb = 1_024L,
                isLowRamDevice = true,
                maxCustomMb = 32,  // The floor from the detector's coerceAtLeast(32).
                recommended = KdfPreset.STANDARD,
                enabled = setOf(KdfPreset.STANDARD, KdfPreset.STANDARD_PLUS),
            ),
        )
        val vm = buildVm()
        assertTrue(
            "Custom memory cap must be at least 32 MiB on every device",
            vm.deviceCapacity.value.maxArgon2MemoryMb >= 32,
        )
    }

    @Test fun deviceCapacity_maxArgon2MemoryMb_caps_at_256_MiB_on_high_RAM_devices() {
        // 256 MiB is the hard ceiling regardless of physical RAM. Pin that
        // the VM exposes this cap so the Custom slider stops at 256 - any
        // higher and attacker-cost gains plateau against unlock-cost pain.
        val context: Context = RuntimeEnvironment.getApplication()
        detector = FixedDeviceCapacityDetector(
            context = context,
            snapshot = capacity(
                totalRamMb = 16_384L,
                isLowRamDevice = false,
                maxCustomMb = 256,
                recommended = KdfPreset.MAXIMUM,
                enabled = setOf(
                    KdfPreset.STANDARD,
                    KdfPreset.STANDARD_PLUS,
                    KdfPreset.HARDENED,
                    KdfPreset.MAXIMUM,
                ),
            ),
        )
        val vm = buildVm()
        assertEquals(256, vm.deviceCapacity.value.maxArgon2MemoryMb)
    }

    // ── activeKdfPreset surface ───────────────────────────────────────────

    @Test fun activeKdfPreset_initial_value_is_STANDARD_before_any_emission() {
        // The StateFlow's seed value is STANDARD - this is what the
        // picker renders before the AuthRepository's flow has its first
        // emission. STANDARD is the historical default and the only safe
        // initial assumption (every legacy code 0/1 maps to it).
        val vm = buildVm()
        assertEquals(KdfPreset.STANDARD, vm.activeKdfPreset.value)
    }

    @Test fun activeKdfPreset_collects_AuthRepository_emissions() = runTest(testDispatcher) {
        val vm = buildVm()
        // Push a HARDENED emission through the AuthRepository's flow.
        auth.activePreset.value = KdfPreset.HARDENED
        advanceUntilIdle()
        assertEquals(KdfPreset.HARDENED, vm.activeKdfPreset.value)
    }

    @Test fun activeKdfCustomParams_is_null_when_active_is_not_CUSTOM() = runTest(testDispatcher) {
        val vm = buildVm()
        auth.activeCustomParams.value = null
        advanceUntilIdle()
        assertEquals(null, vm.activeKdfCustomParams.value)
    }

    @Test fun activeKdfCustomParams_surfaces_AuthRepository_pair_on_CUSTOM() = runTest(testDispatcher) {
        val vm = buildVm()
        auth.activeCustomParams.value = 96 to 5
        advanceUntilIdle()
        assertEquals(96 to 5, vm.activeKdfCustomParams.value)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildVm(): SettingsViewModel {
        val credentialRepository = StubCredentialRepository()
        val historyRepository = StubCredentialHistoryRepository()
        val tagRepository = StubTagRepository()
        val deleteTagUseCase = DeleteTagUseCase(tagRepository, credentialRepository)
        val resetVaultUseCase = ResetVaultUseCase(auth, credentialRepository, historyRepository)
        val seedDataUseCase = SeedDataUseCase(credentialRepository)
        val lockReasonStore = LockReasonStore(appPrefs, testScope)
        val authAttemptsStore = AuthAttemptsStore()
        val highlightStore = SettingsHighlightStore()
        val hardwareKeyIsolationProbe = HardwareKeyIsolationProbe(NoKeystoreCryptoManager(), testScope)
        return SettingsViewModel(
            tagRepository = tagRepository,
            appPrefs = appPrefs,
            authRepository = auth,
            deleteTagUseCase = deleteTagUseCase,
            resetVaultUseCase = resetVaultUseCase,
            seedDataUseCase = seedDataUseCase,
            lockReasonStore = lockReasonStore,
            authAttemptsStore = authAttemptsStore,
            highlightStore = highlightStore,
            hardwareKeyIsolationProbe = hardwareKeyIsolationProbe,
            deviceCapacityDetector = detector,
            kdfBenchmark = benchmark,
            kdfMigrator = migrator,
            authPrefs = authPrefs,
        )
    }

    private fun capacity(
        totalRamMb: Long,
        isLowRamDevice: Boolean,
        maxCustomMb: Int,
        recommended: KdfPreset,
        enabled: Set<KdfPreset>,
    ) = CapacitySnapshot(
        totalRamMb = totalRamMb,
        isLowRamDevice = isLowRamDevice,
        availableCores = 4,
        socModel = "test_soc",
        maxArgon2MemoryMb = maxCustomMb,
        recommendedPreset = recommended,
        enabledPresets = enabled,
    )

    // ── Fakes ─────────────────────────────────────────────────────────────

    /**
     * Fake [DeviceCapacityDetector] that returns a fixed snapshot. The
     * production class is `open` so this kind of stub is a one-line
     * override.
     */
    private class FixedDeviceCapacityDetector(
        context: Context,
        val snapshot: CapacitySnapshot,
    ) : DeviceCapacityDetector(context) {
        override fun snapshot(): CapacitySnapshot = snapshot
    }

    /**
     * Stubs [CryptoManager.loadKeystoreKey] to return null so the
     * [HardwareKeyIsolationProbe] takes its "no key yet" branch instead of
     * hitting `KeyStore.getInstance("AndroidKeyStore")` (which throws
     * `KeyStoreException` on Robolectric - the harness does NOT register
     * the AndroidKeyStore JCA provider; see
     * `CryptoManagerStrongBoxFallbackTest` for the deeper explanation).
     *
     * `CryptoManager.loadKeystoreKey` is the only `open` method on the
     * production class - the constructor is parameterless so subclassing
     * stays clean. Every OTHER call we need (`generateSalt`, the AES/GCM
     * helpers, ...) keeps the production behaviour.
     */
    private class NoKeystoreCryptoManager : CryptoManager() {
        override fun loadKeystoreKey(alias: String): javax.crypto.SecretKey? = null
    }

    private class StubPreferencesDataStore : DataStore<Preferences> {
        private val flow = MutableStateFlow(preferencesOf())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(flow.value)
            flow.value = next
            return next
        }
    }

    private class FakeAuthRepository : AuthRepository {
        val unlocked = MutableStateFlow(false)
        val activePreset = MutableStateFlow(KdfPreset.STANDARD)
        val activeCustomParams = MutableStateFlow<Pair<Int, Int>?>(null)
        var verifyResult: AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun verifyMasterPassword(password: CharArray): AppResult<Unit> = verifyResult

        override fun observeActiveKdfPreset(): Flow<KdfPreset> = activePreset
        override fun observeActiveKdfCustomParams(): Flow<Pair<Int, Int>?> = activeCustomParams

        override fun isUnlocked(): Flow<Boolean> = unlocked
        override fun isSetupComplete(): Flow<Boolean> = flowOf(true)
        override fun isPinSetup(): Flow<Boolean> = flowOf(false)

        override suspend fun setupMasterPassword(password: CharArray): AppResult<Unit> = error("unused")
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
        override suspend fun unlockWithPassword(password: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun unlockWithBiometric(): AppResult<Unit> = error("unused")
        override suspend fun verifyPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun setupPin(pin: CharArray): AppResult<Unit> = error("unused")
        override suspend fun changePassword(oldPassword: CharArray, newPassword: CharArray): AppResult<Unit> = error("unused")
        override suspend fun lock(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun resetPin(): AppResult<Unit> = error("unused")
        override suspend fun resetVault(): AppResult<Unit> = error("unused")
        override suspend fun clearAll(): AppResult<Unit> = error("unused")
        override suspend fun activeKdfParams(): com.onekey.core.security.KdfParams =
            activePreset.value.let { preset ->
                if (preset == KdfPreset.CUSTOM) {
                    val (m, t) = activeCustomParams.value ?: (64 to 3)
                    com.onekey.core.security.KdfParams(mCostKiB = m * 1024, tCost = t, parallelism = 1)
                } else preset.toKdfParams()
            }
        override suspend fun isSecretKeyEnabled(): Boolean = false
        override fun observeIsSecretKeyEnabled(): Flow<Boolean> = flowOf(false)
        override fun observeSecretKeyOptedOut(): Flow<Boolean> = flowOf(false)
    }

    /**
     * Minimal preferences fake covering the surface SettingsViewModel touches.
     * Every getter returns a benign default; every setter mutates the
     * MutableStateFlow so the VM observes its own writes.
     */
    private class FakeAppPreferencesRepository : AppPreferencesRepository {
        val theme = MutableStateFlow(ThemeMode.SYSTEM)
        val biometricEnabled = MutableStateFlow(false)
        val screenshots = MutableStateFlow(false)
        val backgroundTimeout = MutableStateFlow(BackgroundLockTimeout.IMMEDIATE)
        val inactivityTimeout = MutableStateFlow(InactivityLockTimeout.THIRTY_SECONDS)
        val masterPwRecheck = MutableStateFlow(true)
        val masterPwInterval = MutableStateFlow(MasterPasswordInterval.HOURS_48)
        val showFavourites = MutableStateFlow(false)
        val hideTopBar = MutableStateFlow(true)
        val notesMd = MutableStateFlow(true)
        val recycleRetention = MutableStateFlow(RecycleBinRetention.DAYS_30)
        val recycleEnabled = MutableStateFlow(true)
        val footerVisible = MutableStateFlow(true)
        val restoreLastScreen = MutableStateFlow(false)
        val lockReasonCtx = MutableStateFlow<String?>(null)

        override fun getThemeMode(): Flow<ThemeMode> = theme
        override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }
        override fun isBiometricEnabled(): Flow<Boolean> = biometricEnabled
        override suspend fun setBiometricEnabled(enabled: Boolean) { biometricEnabled.value = enabled }
        override fun isScreenshotsEnabled(): Flow<Boolean> = screenshots
        override suspend fun setScreenshotsEnabled(enabled: Boolean) { screenshots.value = enabled }
        override fun getBackgroundLockTimeout(): Flow<BackgroundLockTimeout> = backgroundTimeout
        override suspend fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout) { backgroundTimeout.value = timeout }
        override fun getInactivityLockTimeout(): Flow<InactivityLockTimeout> = inactivityTimeout
        override suspend fun setInactivityLockTimeout(timeout: InactivityLockTimeout) { inactivityTimeout.value = timeout }
        override fun isMasterPasswordRecheckEnabled(): Flow<Boolean> = masterPwRecheck
        override suspend fun setMasterPasswordRecheckEnabled(enabled: Boolean) { masterPwRecheck.value = enabled }
        override fun getMasterPasswordRecheckInterval(): Flow<MasterPasswordInterval> = masterPwInterval
        override suspend fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval) { masterPwInterval.value = interval }
        override fun getLastMasterPasswordTimestamp(): Flow<Long> = flowOf(0L)
        override suspend fun setLastMasterPasswordTimestamp(timestamp: Long) = Unit
        override fun isShowFavourites(): Flow<Boolean> = showFavourites
        override suspend fun setShowFavourites(show: Boolean) { showFavourites.value = show }
        override fun getCredentialSortOrder(): Flow<CredentialSortOrder> = flowOf(CredentialSortOrder.ALPHABETICAL)
        override suspend fun setCredentialSortOrder(order: CredentialSortOrder) = Unit
        override fun isHideTopBarOnScroll(): Flow<Boolean> = hideTopBar
        override suspend fun setHideTopBarOnScroll(enabled: Boolean) { hideTopBar.value = enabled }
        override fun isNotesRenderMarkdownEnabled(): Flow<Boolean> = notesMd
        override suspend fun setNotesRenderMarkdownEnabled(enabled: Boolean) { notesMd.value = enabled }
        override fun isVaultFooterVisible(): Flow<Boolean> = footerVisible
        override suspend fun setVaultFooterVisible(visible: Boolean) { footerVisible.value = visible }
        override fun getRecycleBinRetention(): Flow<RecycleBinRetention> = recycleRetention
        override suspend fun setRecycleBinRetention(retention: RecycleBinRetention) { recycleRetention.value = retention }
        override fun isRecycleBinEnabled(): Flow<Boolean> = recycleEnabled
        override suspend fun setRecycleBinEnabled(enabled: Boolean) { recycleEnabled.value = enabled }
        override fun isRestoreLastScreenOnUnlock(): Flow<Boolean> = restoreLastScreen
        override suspend fun setRestoreLastScreenOnUnlock(enabled: Boolean) { restoreLastScreen.value = enabled }
        override fun isAutofillEnabled(): Flow<Boolean> = flowOf(true)
        override suspend fun setAutofillEnabled(enabled: Boolean) = Unit
        override fun isAutofillCategoryFilterEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setAutofillCategoryFilterEnabled(enabled: Boolean) = Unit
        override fun isAutofillSaveUrlOnCrossHostEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setAutofillSaveUrlOnCrossHostEnabled(enabled: Boolean) = Unit
        override fun getLockReasonContext(): Flow<String?> = lockReasonCtx
        override suspend fun getLockReasonContextDirect(): String? = lockReasonCtx.value
        override suspend fun setLockReasonContext(context: String?) { lockReasonCtx.value = context }
        override fun getBiometricUnlockGate(): Flow<BiometricUnlockGate> =
            flowOf(BiometricUnlockGate(biometricEnabled = false, lockReasonSet = false))
        override fun isSyncEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncEnabled(enabled: Boolean) = Unit
        override fun getSyncLocationUri(): Flow<String?> = flowOf(null)
        override suspend fun setSyncLocationUri(uri: String?) = Unit
        override fun isSyncCompletionNotificationEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setSyncCompletionNotificationEnabled(enabled: Boolean) = Unit
        override fun getSyncLastSuccessAt(): Flow<Long> = flowOf(0L)
        override suspend fun setSyncLastSuccessAt(timestamp: Long) = Unit
        override suspend fun getSyncGateDirect(): SyncGate = SyncGate(enabled = false, locationUri = null)
    }

    private class StubTagRepository : TagRepository {
        override fun observeTags(): Flow<List<Tag>> = flowOf(emptyList())
        override fun observeTagsWithCounts(): Flow<List<TagWithCount>> = flowOf(emptyList())
        override suspend fun getTags(): AppResult<List<Tag>> = AppResult.Success(emptyList())
        override suspend fun addTag(tag: Tag): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun deleteTag(name: String): AppResult<Unit> = AppResult.Success(Unit)
    }

    /**
     * Stub repository - SettingsViewModel never invokes any of the
     * credential-mutation paths during KDF tests, so returning Success on
     * the few methods the deleteTagUseCase / seedDataUseCase / resetVaultUseCase
     * might touch is enough; anything else throws.
     */
    private class StubCredentialRepository : CredentialRepository {
        override suspend fun getAllCredentials(): AppResult<List<Credential>> = AppResult.Success(emptyList())
        override fun observeCredential(id: String): Flow<Credential?> = flowOf(null)
        override fun observeCredentialIncludingDeleted(id: String): Flow<Credential?> = flowOf(null)
        override suspend fun getCredential(id: String): AppResult<Credential> = error("unused")
        override suspend fun saveCredential(credential: Credential): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun deleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun hardDeleteCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun restoreCredential(id: String): AppResult<Unit> = error("unused")
        override suspend fun purgeFromRecycleBin(id: String): AppResult<Unit> = error("unused")
        override suspend fun emptyRecycleBin(): AppResult<Int> = error("unused")
        override suspend fun purgeRecycleBinOlderThan(cutoff: Long): AppResult<Int> = error("unused")
        override fun observeRecycleBin(): Flow<List<Credential>> = flowOf(emptyList())
        override fun observeRecycleBinCount(): Flow<Int> = flowOf(0)
        override suspend fun getAllInRecycleBin(): AppResult<List<Credential>> = error("unused")
        override suspend fun importCredentials(credentials: List<Credential>): AppResult<Int> = error("unused")
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
        override suspend fun deleteAllCredentials(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun markAccessed(id: String): AppResult<Unit> = error("unused")
    }

    private class StubCredentialHistoryRepository : CredentialHistoryRepository {
        override suspend fun deleteAll(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun snapshotCredential(credential: Credential): AppResult<Unit> = AppResult.Success(Unit)
        override fun observeHistory(credentialId: String): Flow<List<CredentialHistoryEntry>> = flowOf(emptyList())
        override suspend fun deleteForCredential(credentialId: String): AppResult<Unit> = AppResult.Success(Unit)
    }
}
