package com.onekey.feature.auth.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.usecase.SetupFromBackupUseCase
import com.onekey.core.domain.usecase.SetupMasterPasswordUseCase
import com.onekey.core.domain.usecase.UnlockVaultUseCase
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

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPrefs: AppPreferencesRepository,
    private val setupMasterPassword: SetupMasterPasswordUseCase,
    private val unlockVault: UnlockVaultUseCase,
    private val setupFromBackup: SetupFromBackupUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

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
            val result = setupMasterPassword(password)
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
            val result = unlockVault.withPassword(password)
            if (result is AppResult.Success) {
                appPrefs.setLastMasterPasswordTimestamp(System.currentTimeMillis())
            }
            _state.value = when (result) {
                is AppResult.Success -> AuthUiState.Unlocked
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Invalid password")
            }
        }
    }

    fun unlockWithPin(pin: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            _state.value = when (val result = unlockVault.withPin(pin)) {
                is AppResult.Success -> AuthUiState.Unlocked
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Invalid PIN")
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

    fun setupPin(pin: String) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            _state.value = when (val result = authRepository.setupPin(pin)) {
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
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { inp -> tmpFile.outputStream().use { inp.copyTo(it) } }
                }
                when (val result = setupFromBackup(password, tmpFile.absolutePath)) {
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
