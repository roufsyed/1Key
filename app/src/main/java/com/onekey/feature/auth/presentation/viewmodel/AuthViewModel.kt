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
            _state.value = AuthUiState.Loading
            _state.value = when (val result = authRepository.unlockWithBiometric()) {
                is AppResult.Success -> AuthUiState.Unlocked
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Biometric unlock failed")
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
