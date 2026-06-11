package com.onekey.feature.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.model.ThemeMode
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.DeleteTagUseCase
import com.onekey.core.domain.usecase.ResetVaultUseCase
import com.onekey.core.domain.usecase.SeedDataUseCase
import com.onekey.core.security.AuthAttemptsStore
import com.onekey.core.security.CapacitySnapshot
import com.onekey.core.security.DeviceCapacityDetector
import com.onekey.core.security.HardwareKeyIsolationProbe
import com.onekey.core.security.HardwareKeyIsolationStatus
import com.onekey.core.security.KdfBenchmark
import com.onekey.core.security.KdfMigrator
import com.onekey.core.security.KdfParams
import com.onekey.core.security.KdfPreset
import com.onekey.core.security.LockReason
import com.onekey.core.security.LockReasonStore
import com.onekey.feature.settings.presentation.viewmodel.SettingsHighlightStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SettingsEvent {
    data object PinRemoved : SettingsEvent()
    data class PinRemoveConfirmFailed(val attemptsRemaining: Int) : SettingsEvent()
    data object VaultContentsDeleted : SettingsEvent()
    data class SeedComplete(val count: Int) : SettingsEvent()
    data class TwoFaSeedComplete(val count: Int) : SettingsEvent()
    data class Error(val message: String) : SettingsEvent()
    data object BiometricEnabled : SettingsEvent()
    data class BiometricConfirmFailed(val attemptsRemaining: Int) : SettingsEvent()
    data class DeleteVaultConfirmFailed(val attemptsRemaining: Int) : SettingsEvent()
    data object VaultLocked : SettingsEvent()

    /**
     * Emitted after [SettingsViewModel.selectPreset] / [SettingsViewModel.applyCustom]
     * successfully completes a KDF migration. The new active preset is carried
     * so the snackbar can render "Encryption strength updated to <displayName>".
     */
    data class KdfPresetApplied(val preset: KdfPreset) : SettingsEvent()

    /**
     * Emitted on a wrong master-password attempt during a KDF preset change.
     * Mirrors [BiometricConfirmFailed] / [PinRemoveConfirmFailed] - the dialog
     * stays open and surfaces the remaining attempts to the user.
     */
    data class KdfPresetConfirmFailed(val attemptsRemaining: Int) : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val appPrefs: AppPreferencesRepository,
    private val authRepository: AuthRepository,
    private val deleteTagUseCase: DeleteTagUseCase,
    private val resetVaultUseCase: ResetVaultUseCase,
    private val seedDataUseCase: SeedDataUseCase,
    private val lockReasonStore: LockReasonStore,
    private val authAttemptsStore: AuthAttemptsStore,
    private val highlightStore: SettingsHighlightStore,
    private val hardwareKeyIsolationProbe: HardwareKeyIsolationProbe,
    private val deviceCapacityDetector: DeviceCapacityDetector,
    private val kdfBenchmark: KdfBenchmark,
    private val kdfMigrator: KdfMigrator,
) : ViewModel() {

    init {
        // Idempotent: subsequent reads of [hardwareKeyIsolation] off Security
        // do not re-probe. First entry into Settings -> Security kicks the
        // background probe; the StateFlow below holds the result for the
        // lifetime of the process.
        hardwareKeyIsolationProbe.start()
    }

    val highlightKey: StateFlow<String?> = highlightStore.pendingKey
    fun clearHighlight() = highlightStore.clear()

    /**
     * Resolved hardware-isolation tier for the vault wrapping key, or `null`
     * while the background probe is still running. Started [SharingStarted.Eagerly]
     * so that by the time Security composes, the StateFlow already holds the
     * latest value the probe has emitted (which is the cached value on every
     * subsequent visit to Security in the same process).
     */
    val hardwareKeyIsolation: StateFlow<HardwareKeyIsolationStatus?> =
        hardwareKeyIsolationProbe.status
            .stateIn(viewModelScope, SharingStarted.Eagerly, hardwareKeyIsolationProbe.status.value)

    private val _isSeedingData = MutableStateFlow(false)
    val isSeedingData: StateFlow<Boolean> = _isSeedingData.asStateFlow()

    private val _event = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<SettingsEvent> = _event.asSharedFlow()

    val tags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val themeMode: StateFlow<ThemeMode> = appPrefs.getThemeMode()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val isBiometricEnabled: StateFlow<Boolean> = appPrefs.isBiometricEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isPinSetup: StateFlow<Boolean> = authRepository.isPinSetup()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isScreenshotsEnabled: StateFlow<Boolean> = appPrefs.isScreenshotsEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val backgroundLockTimeout: StateFlow<BackgroundLockTimeout> = appPrefs.getBackgroundLockTimeout()
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackgroundLockTimeout.IMMEDIATE)

    val inactivityLockTimeout: StateFlow<InactivityLockTimeout> = appPrefs.getInactivityLockTimeout()
        .stateIn(viewModelScope, SharingStarted.Eagerly, InactivityLockTimeout.THIRTY_SECONDS)

    val isMasterPasswordRecheckEnabled: StateFlow<Boolean> = appPrefs.isMasterPasswordRecheckEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val masterPasswordRecheckInterval: StateFlow<MasterPasswordInterval> =
        appPrefs.getMasterPasswordRecheckInterval()
            .stateIn(viewModelScope, SharingStarted.Eagerly, MasterPasswordInterval.HOURS_48)

    val isShowFavourites: StateFlow<Boolean> = appPrefs.isShowFavourites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isHideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isNotesRenderMarkdownEnabled: StateFlow<Boolean> = appPrefs.isNotesRenderMarkdownEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val recycleBinRetention: StateFlow<RecycleBinRetention> = appPrefs.getRecycleBinRetention()
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecycleBinRetention.DAYS_30)

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isVaultFooterVisible: StateFlow<Boolean> = appPrefs.isVaultFooterVisible()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isRestoreLastScreenOnUnlock: StateFlow<Boolean> = appPrefs.isRestoreLastScreenOnUnlock()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── KDF (encryption strength) state ────────────────────────────────────
    //
    // The KDF picker reads four flows:
    //  - [activeKdfPreset]:        which preset is currently bound to the verifier
    //  - [activeKdfCustomParams]:  (m, t) iff activePreset == CUSTOM, else null
    //  - [deviceCapacity]:         RAM-derived rules table for picker gating
    //  - [kdfBenchmarks]:          per-preset measured unlock time in ms
    //
    // The picker also calls [refreshBenchmark] / [selectPreset] / [applyCustom];
    // [isKdfMigrating] disables the picker while a Phase-1-then-Phase-2
    // migration is in flight on the background dispatcher.

    /** Currently-active Argon2id preset, resolved from the stored KDF version int. */
    val activeKdfPreset: StateFlow<KdfPreset> = authRepository.observeActiveKdfPreset()
        .stateIn(viewModelScope, SharingStarted.Eagerly, KdfPreset.STANDARD)

    /**
     * Custom (m, t) pair when [activeKdfPreset] is [KdfPreset.CUSTOM]; null
     * for any of the four fixed presets. Subscribed to by the Settings UI to
     * render the secondary subtitle line under the Encryption strength row.
     */
    val activeKdfCustomParams: StateFlow<Pair<Int, Int>?> = authRepository.observeActiveKdfCustomParams()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Device capacity snapshot. We hold the value in a StateFlow (rather than
     * a plain val) so test fakes that flip the snapshot mid-test can drive UI
     * recomposition predictably. The DeviceCapacityDetector itself memoises so
     * repeated reads here are free.
     */
    val deviceCapacity: StateFlow<CapacitySnapshot> = MutableStateFlow(deviceCapacityDetector.snapshot())
        .asStateFlow()

    /**
     * Per-preset measured unlock time. The picker uses this to render the
     * "Estimated unlock: ~Xms" subtitle on each preset row. Backed by the
     * shared singleton [KdfBenchmark] so refresh-from-anywhere updates are
     * visible everywhere.
     */
    val kdfBenchmarks: StateFlow<Map<KdfParams, Long>> = kdfBenchmark.cachedTimings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _isKdfMigrating = MutableStateFlow(false)
    /** True while a `kdfMigrator.migrateTo` call is in flight. */
    val isKdfMigrating: StateFlow<Boolean> = _isKdfMigrating.asStateFlow()

    private val _isKdfBenchmarking = MutableStateFlow(false)
    /** True while a `KdfBenchmark.benchmark` or `refreshBenchmark()` is in flight. */
    val isKdfBenchmarking: StateFlow<Boolean> = _isKdfBenchmarking.asStateFlow()

    init {
        // First-launch warmup: kick benchmarks for every enabled fixed preset
        // (and the currently-active one) so the picker has data to show when
        // the user opens it. Each benchmark is gated by the mutex inside
        // KdfBenchmark, so this serialises naturally and stays bounded.
        //
        // We avoid the picker UI showing "Estimating..." for the user's
        // own active preset by giving that one priority (first in the loop).
        viewModelScope.launch {
            val snapshot = deviceCapacityDetector.snapshot()
            val current = authRepository.observeActiveKdfPreset().first()
            val toMeasure = buildList {
                if (current != KdfPreset.CUSTOM) add(current)
                addAll(snapshot.enabledPresets.filter { it != current })
            }
            toMeasure.forEach { preset ->
                runCatching { kdfBenchmark.benchmarkIfMissing(preset.toKdfParams()) }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        if (mode == themeMode.value) return
        viewModelScope.launch { appPrefs.setThemeMode(mode) }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setBiometricEnabled(enabled) }
    }

    fun enableBiometricWithVerification(password: CharArray) {
        viewModelScope.launch {
            try {
                when (authRepository.unlockWithPassword(password)) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetBiometricEnable()
                        appPrefs.setBiometricEnabled(true)
                        _event.emit(SettingsEvent.BiometricEnabled)
                    }
                    is AppResult.Error -> {
                        // Singleton-scoped so navigating Security ↔ top-level Settings can't
                        // reset the count and bypass the lockout.
                        val attempts = authAttemptsStore.incrementBiometricEnable()
                        if (attempts >= MAX_BIOMETRIC_ATTEMPTS) {
                            authAttemptsStore.resetBiometricEnable()
                            lockReasonStore.set(LockReason.TooManyFailedAttempts("biometric setup"))
                            authRepository.lock()
                            _event.emit(SettingsEvent.VaultLocked)
                        } else {
                            _event.emit(
                                SettingsEvent.BiometricConfirmFailed(
                                    attemptsRemaining = MAX_BIOMETRIC_ATTEMPTS - attempts,
                                )
                            )
                        }
                    }
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun setScreenshotsEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setScreenshotsEnabled(enabled) }
    }

    fun setBackgroundLockTimeout(timeout: BackgroundLockTimeout) {
        viewModelScope.launch { appPrefs.setBackgroundLockTimeout(timeout) }
    }

    fun setInactivityLockTimeout(timeout: InactivityLockTimeout) {
        viewModelScope.launch { appPrefs.setInactivityLockTimeout(timeout) }
    }

    fun setMasterPasswordRecheckEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setMasterPasswordRecheckEnabled(enabled) }
    }

    fun setMasterPasswordRecheckInterval(interval: MasterPasswordInterval) {
        viewModelScope.launch { appPrefs.setMasterPasswordRecheckInterval(interval) }
    }

    fun setShowFavourites(show: Boolean) {
        viewModelScope.launch { appPrefs.setShowFavourites(show) }
    }

    fun setHideTopBarOnScroll(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setHideTopBarOnScroll(enabled) }
    }

    fun setNotesRenderMarkdownEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setNotesRenderMarkdownEnabled(enabled) }
    }

    fun setRecycleBinRetention(retention: RecycleBinRetention) {
        viewModelScope.launch { appPrefs.setRecycleBinRetention(retention) }
    }

    fun setRecycleBinEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setRecycleBinEnabled(enabled) }
    }

    fun setVaultFooterVisible(visible: Boolean) {
        viewModelScope.launch { appPrefs.setVaultFooterVisible(visible) }
    }

    fun setRestoreLastScreenOnUnlock(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setRestoreLastScreenOnUnlock(enabled) }
    }

    fun addTag(name: String) {
        viewModelScope.launch {
            tagRepository.addTag(Tag(name = name, color = 0xFF6200EE.toInt(), icon = ""))
        }
    }

    fun deleteTag(name: String) {
        viewModelScope.launch {
            val tag = tags.value.find { it.name == name }
            if (tag != null && !tag.isDefault) {
                val result = deleteTagUseCase(name)
                if (result is AppResult.Error) {
                    _event.emit(SettingsEvent.Error(result.message ?: "Failed to delete category"))
                }
            }
        }
    }

    /**
     * Verifies the user's master password before removing the saved PIN. Mirrors the
     * shape of [enableBiometricWithVerification]: shared singleton attempts counter so
     * navigating Security ↔ top-level Settings can't reset the lockout, three wrong
     * attempts persist a lock reason and lock the vault.
     */
    fun removePinWithVerification(password: CharArray) {
        viewModelScope.launch {
            try {
                when (authRepository.unlockWithPassword(password)) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetBiometricEnable()
                        when (val result = authRepository.resetPin()) {
                            is AppResult.Success -> _event.emit(SettingsEvent.PinRemoved)
                            is AppResult.Error -> _event.emit(
                                SettingsEvent.Error(result.message ?: "Failed to remove PIN")
                            )
                        }
                    }
                    is AppResult.Error -> {
                        val attempts = authAttemptsStore.incrementBiometricEnable()
                        if (attempts >= MAX_BIOMETRIC_ATTEMPTS) {
                            authAttemptsStore.resetBiometricEnable()
                            lockReasonStore.set(LockReason.TooManyFailedAttempts("PIN removal"))
                            authRepository.lock()
                            _event.emit(SettingsEvent.VaultLocked)
                        } else {
                            _event.emit(
                                SettingsEvent.PinRemoveConfirmFailed(
                                    attemptsRemaining = MAX_BIOMETRIC_ATTEMPTS - attempts,
                                )
                            )
                        }
                    }
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    private var deleteVaultAttempts = 0
    private val _isVerifyingDeleteVault = MutableStateFlow(false)
    val isVerifyingDeleteVault: StateFlow<Boolean> = _isVerifyingDeleteVault.asStateFlow()

    /**
     * Wipes the vault only after the user re-enters their master password. Three wrong
     * attempts lock the vault - same policy as the biometric-enable flow.
     */
    fun deleteVaultContentsWithVerification(password: CharArray) {
        viewModelScope.launch {
            try {
                _isVerifyingDeleteVault.value = true
                when (authRepository.unlockWithPassword(password)) {
                    is AppResult.Success -> {
                        deleteVaultAttempts = 0
                        when (val result = resetVaultUseCase()) {
                            is AppResult.Success -> _event.emit(SettingsEvent.VaultContentsDeleted)
                            is AppResult.Error -> _event.emit(
                                SettingsEvent.Error(result.message ?: "Failed to delete vault")
                            )
                        }
                    }
                    is AppResult.Error -> {
                        deleteVaultAttempts++
                        if (deleteVaultAttempts >= MAX_BIOMETRIC_ATTEMPTS) {
                            deleteVaultAttempts = 0
                            lockReasonStore.set(LockReason.TooManyFailedAttempts("vault deletion"))
                            authRepository.lock()
                            _event.emit(SettingsEvent.VaultLocked)
                        } else {
                            _event.emit(
                                SettingsEvent.DeleteVaultConfirmFailed(
                                    attemptsRemaining = MAX_BIOMETRIC_ATTEMPTS - deleteVaultAttempts,
                                )
                            )
                        }
                    }
                }
            } finally {
                password.fill(' ')
                _isVerifyingDeleteVault.value = false
            }
        }
    }

    fun seedData() {
        if (_isSeedingData.value) return
        viewModelScope.launch {
            _isSeedingData.value = true
            val result = seedDataUseCase()
            _isSeedingData.value = false
            when (result) {
                is AppResult.Success -> _event.emit(SettingsEvent.SeedComplete(result.data))
                is AppResult.Error -> _event.emit(SettingsEvent.Error(result.message ?: "Failed to seed data"))
            }
        }
    }

    fun seedTwoFaData() {
        if (_isSeedingData.value) return
        viewModelScope.launch {
            _isSeedingData.value = true
            val result = seedDataUseCase.seedTwoFa()
            _isSeedingData.value = false
            when (result) {
                is AppResult.Success -> _event.emit(SettingsEvent.TwoFaSeedComplete(result.data))
                is AppResult.Error -> _event.emit(SettingsEvent.Error(result.message ?: "Failed to seed 2FA data"))
            }
        }
    }

    // ── KDF preset actions ─────────────────────────────────────────────────
    //
    // Reauth + migrate flow. The Settings UI:
    //  1. Shows the picker, user selects preset P.
    //  2. Picker opens [MasterPasswordReauthDialog].
    //  3. On confirm, the dialog hands a CharArray to one of [selectPreset]
    //     or [applyCustom]. The caller passes ownership to the VM; we zero
    //     it in `finally` after [KdfMigrator] is done with it (the migrator
    //     also defensively zeroes its own copy on the way out).
    //
    // The 3-strike lockout shares the same counter as biometric-enable and
    // PIN-removal flows (the `authAttemptsStore.incrementBiometricEnable()`
    // singleton-scoped counter). Three wrong attempts across any reauth-gated
    // Settings flow trips the lockout - this is intentional: the threat is
    // someone shoulder-surfing the Settings reauth UI, regardless of which
    // specific action they were trying to hijack.

    /**
     * Re-derive the verifier under one of the four fixed presets. The picker
     * already confirmed [preset] is enabled on this device; this method does
     * NOT re-check (KdfMigrator does as defence in depth) but does refuse
     * [KdfPreset.CUSTOM] - that lives on [applyCustom].
     *
     * @param password Caller-supplied CharArray. We take ownership and zero
     *                 it in a finally block.
     */
    fun selectPreset(preset: KdfPreset, password: CharArray) {
        require(preset != KdfPreset.CUSTOM) { "Use applyCustom for CUSTOM preset" }
        applyKdfMigration(preset = preset, params = preset.toKdfParams(), password = password)
    }

    /**
     * Re-derive the verifier under user-chosen custom (m, t) params. The
     * Custom dialog already validated that `m` is within the per-device
     * `maxCustomMemoryMb` cap and `t` is in `2..16`; KdfMigrator does not
     * re-check those numeric bounds because the only callers are this method
     * (UI-gated) and tests (which set explicit values).
     *
     * @param params  KdfParams holding the chosen mCostKiB / tCost / p=1.
     * @param password Caller-supplied CharArray. We take ownership and zero
     *                 it in a finally block.
     */
    fun applyCustom(params: KdfParams, password: CharArray) {
        applyKdfMigration(preset = KdfPreset.CUSTOM, params = params, password = password)
    }

    private fun applyKdfMigration(
        preset: KdfPreset,
        params: KdfParams,
        password: CharArray,
    ) {
        viewModelScope.launch {
            // Bind the verify-copy to a named local so the `finally` below can
            // zero it deterministically. `authRepository.verifyMasterPassword`
            // makes its own internal copy and zeros THAT, but the array we hand
            // it stays in the heap until GC unless we zero it ourselves.
            val verifyCopy = password.copyOf()
            try {
                _isKdfMigrating.value = true
                // Reauth: the migrator does its own verifier check as part of
                // Phase 1a, but a verifyMasterPassword() round before the slow
                // Argon2id derivation lets us short-circuit wrong-password
                // attempts cleanly through the attempt counter / lockout path
                // without burning an Argon2id derivation under the new params.
                when (authRepository.verifyMasterPassword(verifyCopy)) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetBiometricEnable()
                        when (val res = kdfMigrator.migrateTo(params, preset, password)) {
                            is AppResult.Success -> {
                                // Eagerly schedule a fresh benchmark of the new
                                // active params so the picker subtitle updates
                                // ("Estimated unlock: ~X ms") without waiting
                                // for the next time the user opens the screen.
                                viewModelScope.launch {
                                    runCatching { kdfBenchmark.benchmark(params) }
                                }
                                _event.emit(SettingsEvent.KdfPresetApplied(preset))
                            }
                            is AppResult.Error -> _event.emit(
                                SettingsEvent.Error(
                                    res.message ?: "Could not change encryption strength"
                                )
                            )
                        }
                    }
                    is AppResult.Error -> {
                        val attempts = authAttemptsStore.incrementBiometricEnable()
                        if (attempts >= MAX_BIOMETRIC_ATTEMPTS) {
                            authAttemptsStore.resetBiometricEnable()
                            lockReasonStore.set(
                                LockReason.TooManyFailedAttempts("encryption strength change")
                            )
                            authRepository.lock()
                            _event.emit(SettingsEvent.VaultLocked)
                        } else {
                            _event.emit(
                                SettingsEvent.KdfPresetConfirmFailed(
                                    attemptsRemaining = MAX_BIOMETRIC_ATTEMPTS - attempts,
                                )
                            )
                        }
                    }
                }
            } finally {
                // KdfMigrator zeroes its own internal copy; this zeroes both
                // the VM's view of the caller's array AND the throwaway copy
                // we handed to verifyMasterPassword. Idempotent if the inner
                // callees already overwrote them.
                password.fill(' ')
                verifyCopy.fill(' ')
                _isKdfMigrating.value = false
            }
        }
    }

    /**
     * Re-runs benchmarks for every preset enabled on this device. Single
     * shared mutex inside [KdfBenchmark] serialises the work, so this is safe
     * to spam-tap.
     */
    fun refreshBenchmark() {
        if (_isKdfBenchmarking.value) return
        viewModelScope.launch {
            try {
                _isKdfBenchmarking.value = true
                val snapshot = deviceCapacityDetector.snapshot()
                snapshot.enabledPresets.forEach { preset ->
                    runCatching { kdfBenchmark.benchmark(preset.toKdfParams()) }
                }
            } finally {
                _isKdfBenchmarking.value = false
            }
        }
    }

    /**
     * One-shot benchmark for a user-chosen Custom (m, t) pair. Used by the
     * Custom dialog's "Estimate" button before the user can press "Apply".
     * Returns null on failure (OOM, timeout, native crash); the dialog maps
     * null to "Could not measure on this device" inline error.
     */
    suspend fun benchmarkCustom(params: KdfParams): Long? {
        return try {
            _isKdfBenchmarking.value = true
            kdfBenchmark.benchmark(params)
        } finally {
            _isKdfBenchmarking.value = false
        }
    }

    companion object {
        private const val MAX_BIOMETRIC_ATTEMPTS = 3
    }
}
