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
import com.onekey.core.security.LockReason
import com.onekey.core.security.LockReasonStore
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
    /** Three wrong PINs in a row — LockScreen forces the master-password fallback. */
    data object PinAttemptsExhausted : AuthEvent()
    /** Settings → Change PIN: current PIN matched. Advance to "enter new PIN". */
    data object CurrentPinVerified : AuthEvent()
    /** Settings → Change PIN: wrong current PIN, [remaining] attempts left this session. */
    data class CurrentPinFailed(val remaining: Int) : AuthEvent()
    /** Settings → Change PIN: 3 wrong current PINs. Soft cap — vault stays unlocked, but
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

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    private var pinAttemptsRemaining = MAX_PIN_ATTEMPTS
    private var biometricAttemptsRemaining = MAX_BIOMETRIC_ATTEMPTS
    // Local counter for the in-vault Settings→Change PIN current-PIN verification.
    // Distinct from pinAttemptsRemaining (which gates LockScreen unlocks) because the
    // user is already authenticated when this is in play — different threat shape.
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
            // Two PBKDF2 derivations (~600-1600ms total) — keep them off Main so the
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
            _state.value = AuthUiState.Loading
            // PBKDF2 verifier check (~300-800ms) — keep off Main so the button properly
            // shows its spinner and disables on first tap, instead of freezing the UI
            // and leaving the button looking unresponsive on subsequent retries.
            val result = withContext(Dispatchers.Default) { unlockVault.withPassword(password) }
            if (result is AppResult.Success) {
                appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
                // A successful master-password unlock proves possession; release the
                // biometric block that was set by the failed-attempts auto-lock.
                lockReasonStore.clear()
            }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.Unlocked
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Invalid password")
            }
        }
    }

    fun unlockWithPin(pin: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            // PBKDF2 derivation runs on Default; state mutation resumes on Main.
            val result = withContext(Dispatchers.Default) { unlockVault.withPin(pin) }
            when (result) {
                is AppResult.Success -> {
                    pinAttemptsRemaining = MAX_PIN_ATTEMPTS
                    _state.value = AuthUiState.Unlocked
                }
                is AppResult.Error -> {
                    pinAttemptsRemaining--
                    if (pinAttemptsRemaining <= 0) {
                        pinAttemptsRemaining = MAX_PIN_ATTEMPTS
                        // Same lockout shape as the biometric-setup and vault-deletion flows
                        // in SettingsViewModel: reset the counter, persist a lock reason
                        // (DataStore-backed so it survives force-stop / swipe-from-recents
                        // and blocks biometric on next entry), then re-lock as a defensive
                        // no-op (vault is already locked here, but keeps the pattern uniform).
                        lockReasonStore.set(LockReason.TooManyFailedPinAttempts)
                        authRepository.lock()
                        _events.emit(AuthEvent.PinAttemptsExhausted)
                        _state.value = AuthUiState.Error(
                            "Too many wrong PINs — please use your master password."
                        )
                    } else {
                        _state.value = AuthUiState.Error(
                            if (pinAttemptsRemaining == 1)
                                "Wrong PIN — 1 attempt remaining."
                            else
                                "Wrong PIN — $pinAttemptsRemaining attempts remaining."
                        )
                    }
                }
            }
        }
    }

    fun unlockWithBiometric() {
        viewModelScope.launch {
            // Defensive — if a stale BiometricPrompt completes after we've already locked
            // out for too-many-failures, refuse the unlock. The button is hidden on
            // LockScreen when lockReason is set, but the in-flight prompt can still fire.
            if (lockReasonStore.reason.value != null) {
                _state.value = AuthUiState.Error(
                    "Use your master password — biometric is paused after recent failures."
                )
                return@launch
            }
            _state.value = AuthUiState.Loading
            _state.value = when (val result = authRepository.unlockWithBiometric()) {
                is AppResult.Success -> {
                    biometricAttemptsRemaining = MAX_BIOMETRIC_ATTEMPTS
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
            // still-visible BiometricPrompt are noise — the user is in master-password-only
            // mode and the count is meaningless. Don't decrement past the threshold.
            if (lockReasonStore.reason.value != null) return@launch

            biometricAttemptsRemaining--
            if (biometricAttemptsRemaining <= 0) {
                biometricAttemptsRemaining = MAX_BIOMETRIC_ATTEMPTS
                lockReasonStore.set(LockReason.TooManyFailedBiometricAttempts)
                authRepository.lock()
                _state.value = AuthUiState.Error(
                    "Too many wrong biometric attempts — please use your master password."
                )
            } else {
                _state.value = AuthUiState.Error(
                    if (biometricAttemptsRemaining == 1)
                        "Wrong biometric — 1 attempt remaining."
                    else
                        "Wrong biometric — $biometricAttemptsRemaining attempts remaining."
                )
            }
        }
    }

    /**
     * Settings → Change PIN, step 0: confirms the user knows the current PIN before
     * we let them change it. Pure verification — no vault key touched. Local-counter
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
     * current-PIN check. Mirrors removePinWithVerification's lockout shape — uses the
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
                // unlockWithPassword sets the vault key on success — but we're already
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
     * master password, and imports the credentials — all in one step for onboarding.
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
