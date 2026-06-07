package com.onekey.feature.auth.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.BiometricUnlockGate
import com.onekey.core.domain.usecase.SetupFromBackupUseCase
import com.onekey.core.domain.usecase.SetupMasterPasswordUseCase
import com.onekey.core.domain.usecase.UnlockVaultUseCase
import com.onekey.core.security.AuthAttemptsStore
import com.onekey.core.security.AutoLockManager
import com.onekey.core.security.BiometricAttemptTracker
import com.onekey.core.security.LockReason
import com.onekey.core.security.LockReasonStore
import com.onekey.core.security.PasswordAttemptTracker
import com.onekey.core.security.PinAttemptTracker
import com.onekey.core.security.PinAttemptTracker.Companion.lockoutDurationMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@Immutable
sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Loading : AuthUiState()
    data object Unlocked : AuthUiState()
    data class Error(val message: String) : AuthUiState()
    data object SetupComplete : AuthUiState()
}

sealed class AuthEvent {
    /** Three wrong PINs in a row - LockScreen forces the master-password fallback. */
    data object PinAttemptsExhausted : AuthEvent()
    /** Settings → Change PIN: current PIN matched. Advance to "enter new PIN". */
    data object CurrentPinVerified : AuthEvent()
    /** Settings → Change PIN: wrong current PIN, [remaining] attempts left this session. */
    data class CurrentPinFailed(val remaining: Int) : AuthEvent()
    /** Settings → Change PIN: 3 wrong current PINs. Soft cap - vault stays unlocked, but
     * the PIN field disables and the user is pointed at the Forgot-PIN escape hatch. */
    data object CurrentPinExhausted : AuthEvent()
    /** Settings → Change PIN → Forgot PIN: master password matched. Skip current-PIN
     * verification and go straight to "enter new PIN". */
    data object MasterPasswordVerifiedForPinChange : AuthEvent()
    /** Settings → Change PIN → Forgot PIN: wrong master password. */
    data class PinChangeMasterPasswordFailed(val remaining: Int) : AuthEvent()
    /** Settings → Change PIN → Forgot PIN: 3 wrong master passwords (across all sensitive
     * verify flows in this session). Vault is locked, user is bounced to LockScreen. */
    data object PinChangeVaultLocked : AuthEvent()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPrefs: AppPreferencesRepository,
    private val setupMasterPassword: SetupMasterPasswordUseCase,
    private val unlockVault: UnlockVaultUseCase,
    private val setupFromBackup: SetupFromBackupUseCase,
    private val lockReasonStore: LockReasonStore,
    private val autoLockManager: AutoLockManager,
    private val authAttemptsStore: AuthAttemptsStore,
    private val passwordAttemptTracker: PasswordAttemptTracker,
    private val pinAttemptTracker: PinAttemptTracker,
    private val biometricAttemptTracker: BiometricAttemptTracker,
) : ViewModel() {

    fun notifyPickerLaunched() { autoLockManager.suppressForPicker() }
    fun notifyPickerDone() { autoLockManager.clearPickerSuppression() }

    val lockReason: StateFlow<LockReason?> = lockReasonStore.reason
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Single atomic source for the auto-biometric decision so the two underlying prefs
     * (`biometric_enabled`, `lock_reason_context`) can never be observed in a transient
     * mismatched state during cold-start DataStore hydration.
     */
    val biometricUnlockGate: StateFlow<BiometricUnlockGate> = appPrefs.getBiometricUnlockGate()
        .stateIn(viewModelScope, SharingStarted.Eagerly, BiometricUnlockGate(false, false))

    /**
     * Epoch-ms when the current master-password lockout expires, or null if no lockout
     * is in effect. May be in the past once the window has elapsed - the UI compares
     * against [System.currentTimeMillis] to decide whether to show the countdown.
     */
    val passwordLockoutUntilMs: StateFlow<Long?> = passwordAttemptTracker.lockoutUntilMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Same shape as [passwordLockoutUntilMs] but for the PIN. Persisted across process
     * death via [PinAttemptTracker], so a swipe-from-recents between attempts cannot
     * reset the counter. The LockScreen consumes this to disable the PIN field and
     * surface a countdown while the lockout window is active.
     */
    val pinLockoutUntilMs: StateFlow<Long?> = pinAttemptTracker.lockoutUntilMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    /**
     * "X biometric attempts remaining" surface for the UI. Reads through
     * [BiometricAttemptTracker] (DataStore-backed) so the count is shared
     * across LockScreen and the autofill activities - preventing the
     * "rotate between surfaces to triple the budget" attack that an
     * in-memory ViewModel field would allow.
     */
    val biometricAttemptsRemaining: StateFlow<Int> = biometricAttemptTracker.failureCount
        .map { (BiometricAttemptTracker.MAX_FAILURES - it).coerceAtLeast(0) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BiometricAttemptTracker.MAX_FAILURES)

    // Local counter for the in-vault Settings→Change PIN current-PIN verification.
    // The vault is already unlocked here so the threat shape is different from
    // LockScreen PIN entry - a session-scoped counter is sufficient. (LockScreen
    // PIN attempts are tracked persistently via PinAttemptTracker.)
    private var currentPinAttemptsRemaining = MAX_CURRENT_PIN_ATTEMPTS

    val isSetupComplete: StateFlow<Boolean> = authRepository.isSetupComplete()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isUnlocked: StateFlow<Boolean> = authRepository.isUnlocked()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isPinSetup: StateFlow<Boolean> = authRepository.isPinSetup()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isBiometricEnabled: StateFlow<Boolean> = appPrefs.isBiometricEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // True when the user must re-enter their master password regardless of PIN/biometric state.
    val requiresMasterPasswordRecheck: StateFlow<Boolean> = combine(
        appPrefs.isMasterPasswordRecheckEnabled(),
        appPrefs.getMasterPasswordRecheckInterval(),
        appPrefs.getLastMasterPasswordTimestamp(),
    ) { enabled, interval, lastTimestamp ->
        enabled && (System.currentTimeMillis() - lastTimestamp) >= interval.millis
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setup(password: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            // Two PBKDF2 derivations (~600-1600ms total) - keep them off Main so the
            // Loading spinner actually paints and the button visibly transitions.
            val result = withContext(Dispatchers.Default) { setupMasterPassword(password) }
            if (result is AppResult.Success) {
                appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
            }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.SetupComplete
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Setup failed")
            }
        }
    }

    fun unlockWithPassword(password: CharArray) {
        viewModelScope.launch {
            // Defense-in-depth: honor the lockout even if called programmatically while
            // the UI button is disabled. This stops automated callers from bypassing
            // the per-attempt Argon2id cost by retrying before the window expires.
            //
            // Read DataStore directly - the [passwordLockoutUntilMs] StateFlow lags
            // a concurrent [recordFailure] DataStore commit (the StateFlow collector
            // runs on `viewModelScope`, which is one dispatcher hop behind the write).
            // A fresh `.first()` on the underlying Flow guarantees a current value
            // with no race window.
            val lockoutUntil = passwordAttemptTracker.lockoutUntilMs.first()
            if (lockoutUntil != null && System.currentTimeMillis() < lockoutUntil) {
                password.fill(' ')
                val remainingSecs = ((lockoutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1L)
                _state.value = AuthUiState.Error("Too many failed attempts. Try again in ${remainingSecs}s.")
                return@launch
            }

            _state.value = AuthUiState.Loading
            // Argon2id/PBKDF2 verifier check (~300-800ms) - keep off Main so the button
            // properly shows its spinner instead of freezing the UI on first tap.
            val result = withContext(Dispatchers.Default) { unlockVault.withPassword(password) }
            when (result) {
                is AppResult.Success -> {
                    passwordAttemptTracker.reset()
                    // Master password is the canonical "user has proved identity" signal.
                    // Reset the PIN AND biometric trackers too so the user isn't carrying
                    // lockout state forward into the next session.
                    pinAttemptTracker.reset()
                    biometricAttemptTracker.reset()
                    appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                    // Successful master-password proof: release the biometric block set
                    // by a prior too-many-failures auto-lock.
                    lockReasonStore.clear()
                }
                is AppResult.Error -> {
                    passwordAttemptTracker.recordFailure()
                }
            }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.Unlocked
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Invalid password")
            }
        }
    }

    fun unlockWithPin(pin: CharArray) {
        viewModelScope.launch {
            // Defense-in-depth lockout check. The LockScreen UI also disables the PIN
            // field while the lockout window is active, but the repository must refuse
            // independently - otherwise an automated caller (or a future code path that
            // bypasses the UI) could brute-force at full Argon2id throughput.
            //
            // Read DataStore directly via `.first()` - the [pinLockoutUntilMs]
            // StateFlow lags a concurrent [recordFailure] commit, which would let a
            // fast retry slip through the gap on a fresh-per-Activity VM whose
            // StateFlow seed is `null` until the first upstream emission lands.
            val lockoutUntil = pinAttemptTracker.lockoutUntilMs.first()
            if (lockoutUntil != null && System.currentTimeMillis() < lockoutUntil) {
                pin.fill(' ')
                val remainingSecs = ((lockoutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1L)
                _state.value = AuthUiState.Error("Too many wrong PINs. Try again in ${remainingSecs}s.")
                return@launch
            }

            _state.value = AuthUiState.Loading
            val result = withContext(Dispatchers.Default) { unlockVault.withPin(pin) }
            when (result) {
                is AppResult.Success -> {
                    pinAttemptTracker.reset()
                    _state.value = AuthUiState.Unlocked
                }
                is AppResult.Error -> {
                    val cumulative = pinAttemptTracker.recordFailure()
                    if (cumulative >= MAX_PIN_ATTEMPTS) {
                        // Persistent across process death (via PinAttemptTracker) AND
                        // forces the master-password fallback for the rest of the session
                        // (via LockReason). The two layers compose: the tracker ensures
                        // brute-force can't bypass the limit by killing the app; the lock
                        // reason ensures the user is bounced to master password until they
                        // prove identity that way (which clears both).
                        lockReasonStore.set(LockReason.TooManyFailedPinAttempts)
                        authRepository.lock()
                        _events.emit(AuthEvent.PinAttemptsExhausted)
                        // Compute the lockout duration directly from the just-returned
                        // cumulative count rather than reading pinLockoutUntilMs.value -
                        // the StateFlow may briefly lag the DataStore commit, which would
                        // make the message say "please use your master password" without
                        // a countdown even when one is in effect. Using `cumulative`
                        // gives a deterministic, race-free message.
                        val lockoutMs = lockoutDurationMs(cumulative)
                        val remainingSecs = if (lockoutMs != null) (lockoutMs / 1000L).coerceAtLeast(1L) else 0L
                        _state.value = AuthUiState.Error(
                            if (remainingSecs > 0) "Too many wrong PINs. Try again in ${remainingSecs}s, or use your master password."
                            else "Too many wrong PINs - please use your master password."
                        )
                    } else {
                        val remaining = MAX_PIN_ATTEMPTS - cumulative
                        _state.value = AuthUiState.Error(
                            if (remaining == 1) "Wrong PIN - 1 attempt remaining."
                            else "Wrong PIN - $remaining attempts remaining."
                        )
                    }
                }
            }
        }
    }

    /**
     * Unlocks the vault using a successful biometric authentication that the caller
     * has already obtained via [androidx.biometric.BiometricPrompt].
     *
     * Threat-model note - **biometric is a SOFT GATE**, not a cryptographic one.
     * The wrap key in `AuthRepositoryImpl.unlockWithBiometric` is wrapped with
     * `setUserAuthenticationRequired(false)` (`CryptoManager.kt`), which means the
     * Keystore will unwrap it any time the calling process can load it - no
     * `CryptoObject` is bound to the BiometricPrompt. We rely on the prompt's UX
     * confirmation, the lock-reason gate, and the [BiometricAttemptTracker] to
     * approximate the security a hardware-bound key would provide.
     *
     * Future upgrade: if this ever moves to `setUserAuthenticationRequired(true)`
     * plus a `BiometricPrompt.CryptoObject`, callers MUST also handle
     * [java.security.KeyPermanentlyInvalidatedException], which the OS throws when
     * the user enrols a new fingerprint/face after vault setup. Until then, that
     * exception cannot fire on the current path and no catch is needed.
     */
    fun unlockWithBiometric() {
        viewModelScope.launch {
            // Defensive - if a stale BiometricPrompt completes after we've already locked
            // out for too-many-failures, refuse the unlock. The button is hidden on
            // LockScreen when lockReason is set, but the in-flight prompt can still fire.
            //
            // Use [LockReasonStore.latest] (DataStore-direct read), NOT `reason.value`
            // (the StateFlow). The store's `set` is suspend and commits to DataStore;
            // the StateFlow collector on `appScope` propagates the new value
            // *asynchronously*. A concurrent [recordBiometricFailure] that just hit
            // the 3-strike threshold could land in DataStore before this method runs
            // but not yet be visible in `reason.value` - leaving a race window where
            // a fast biometric success unlocks the vault despite the just-set reason.
            // `.latest()` closes that window.
            if (lockReasonStore.latest() != null) {
                _state.value = AuthUiState.Error(
                    "Use your master password - biometric is paused after recent failures."
                )
                return@launch
            }
            _state.value = AuthUiState.Loading
            _state.value = when (val result = authRepository.unlockWithBiometric()) {
                is AppResult.Success -> {
                    biometricAttemptTracker.reset()
                    AuthUiState.Unlocked
                }
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Biometric unlock failed")
            }
        }
    }

    /**
     * Called by LockScreen on each `onAuthenticationFailed` from the BiometricPrompt
     * (wrong finger / wrong face). Mirrors the PIN exhaustion shape: count down, surface
     * an "X remaining" message that the user sees when they dismiss the prompt, on zero
     * persist a lock reason, force the master-password fallback, and reset the counter.
     */
    fun recordBiometricFailure() {
        viewModelScope.launch {
            // Once we've already escalated to a lock reason, additional failures from a
            // still-visible BiometricPrompt are noise - the user is in master-password-only
            // mode and the count is meaningless. Don't decrement past the threshold.
            // Read DataStore directly for the same reason `unlockWithBiometric` does.
            if (lockReasonStore.latest() != null) return@launch

            val cumulative = biometricAttemptTracker.recordFailure()
            if (cumulative >= BiometricAttemptTracker.MAX_FAILURES) {
                // Set the lock reason BEFORE locking so any concurrent reader of
                // [LockReasonStore.latest] post-lock sees the new value. Both
                // primitives are DataStore-backed and `set` is suspend, so this
                // sequence is race-free for downstream readers.
                //
                // Do NOT reset the tracker here. Matches PinAttemptTracker semantics:
                // the persistent record of "user crossed the threshold" must survive
                // until a successful master-password proof clears it. Resetting at
                // threshold would let a forced restart show "3 attempts remaining"
                // again and (with the lock-reason gating) silently waste real budget
                // on every retry burst.
                lockReasonStore.set(LockReason.TooManyFailedBiometricAttempts)
                authRepository.lock()
                _state.value = AuthUiState.Error(
                    "Too many wrong biometric attempts - please use your master password."
                )
            } else {
                val remaining = BiometricAttemptTracker.MAX_FAILURES - cumulative
                _state.value = AuthUiState.Error(
                    if (remaining == 1)
                        "Wrong biometric - 1 attempt remaining."
                    else
                        "Wrong biometric - $remaining attempts remaining."
                )
            }
        }
    }

    /**
     * Settings → Change PIN, step 0: confirms the user knows the current PIN before
     * we let them change it. Pure verification - no vault key touched. Local-counter
     * lockout (3 attempts) that disables the PIN field for this session but keeps the
     * vault unlocked, since the user is already authenticated to the vault.
     */
    fun verifyCurrentPin(pin: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = withContext(Dispatchers.Default) { authRepository.verifyPin(pin) }
            _state.value = AuthUiState.Idle
            when (result) {
                is AppResult.Success -> {
                    currentPinAttemptsRemaining = MAX_CURRENT_PIN_ATTEMPTS
                    _events.emit(AuthEvent.CurrentPinVerified)
                }
                is AppResult.Error -> {
                    currentPinAttemptsRemaining--
                    if (currentPinAttemptsRemaining <= 0) {
                        currentPinAttemptsRemaining = MAX_CURRENT_PIN_ATTEMPTS
                        _events.emit(AuthEvent.CurrentPinExhausted)
                    } else {
                        _events.emit(AuthEvent.CurrentPinFailed(currentPinAttemptsRemaining))
                    }
                }
            }
        }
    }

    /**
     * Settings → Change PIN → Forgot PIN: verify the master password to bypass the
     * current-PIN check. Mirrors removePinWithVerification's lockout shape - uses the
     * shared AuthAttemptsStore so navigating Security ↔ top-level Settings can't reset
     * the count, three wrong attempts persist a lock reason and lock the vault.
     */
    fun verifyMasterPasswordForPinChange(password: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                val result = withContext(Dispatchers.Default) {
                    authRepository.unlockWithPassword(password)
                }
                // unlockWithPassword sets the vault key on success - but we're already
                // inside an unlocked vault, so it's a no-op replacement. We don't want
                // _state to cascade to Unlocked here, so flip back to Idle explicitly.
                _state.value = AuthUiState.Idle
                when (result) {
                    is AppResult.Success -> {
                        authAttemptsStore.resetBiometricEnable()
                        _events.emit(AuthEvent.MasterPasswordVerifiedForPinChange)
                    }
                    is AppResult.Error -> {
                        val attempts = authAttemptsStore.incrementBiometricEnable()
                        if (attempts >= MAX_BIOMETRIC_ATTEMPTS) {
                            authAttemptsStore.resetBiometricEnable()
                            lockReasonStore.set(LockReason.TooManyFailedAttempts("PIN change"))
                            authRepository.lock()
                            _events.emit(AuthEvent.PinChangeVaultLocked)
                        } else {
                            _events.emit(
                                AuthEvent.PinChangeMasterPasswordFailed(MAX_BIOMETRIC_ATTEMPTS - attempts)
                            )
                        }
                    }
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun setupPin(pin: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val result = withContext(Dispatchers.Default) { authRepository.setupPin(pin) }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.SetupComplete
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Failed to set PIN")
            }
        }
    }

    fun lock() {
        viewModelScope.launch { authRepository.lock() }
    }

    fun setBiometricError(message: String) { _state.value = AuthUiState.Error(message) }

    fun clearError() { if (_state.value is AuthUiState.Error) _state.value = AuthUiState.Idle }

    private companion object {
        const val MAX_PIN_ATTEMPTS = 3
        const val MAX_BIOMETRIC_ATTEMPTS = 3
        const val MAX_CURRENT_PIN_ATTEMPTS = 3
    }

    /**
     * Decrypts an encrypted 1Key backup, creates the vault using the backup password as the
     * master password, and imports the credentials - all in one step for onboarding.
     *
     * The [password] CharArray is zeroed inside [setupFromBackup] (by AuthRepository) and again
     * in the finally block as a safety net.
     */
    fun restoreFromEncryptedBackup(uri: Uri, password: CharArray, context: Context) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            val tmpFile = File(context.cacheDir, "restore.1key")
            try {
                // Detect a missing or revoked URI explicitly so the user sees a clean message
                // instead of a cryptic decryption failure further down the pipeline.
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
                        input.use { inp -> tmpFile.outputStream().use { os -> inp.copyTo(os) } }
                        true
                    }.getOrDefault(false)
                }
                if (!copied) {
                    _state.value = AuthUiState.Error(
                        "Couldn't read the backup file. It may have been moved, deleted, or the app no longer has permission."
                    )
                    return@launch
                }
                when (val result = withContext(Dispatchers.Default) {
                    setupFromBackup(password, tmpFile.absolutePath)
                }) {
                    is AppResult.Success -> {
                        appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                        _state.value = AuthUiState.SetupComplete
                    }
                    is AppResult.Error ->
                        _state.value = AuthUiState.Error(result.message ?: "Restore failed")
                }
            } finally {
                withContext(Dispatchers.IO) { tmpFile.delete() }
                password.fill(' ')
            }
        }
    }
}
