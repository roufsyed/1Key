package com.onekey.feature.auth.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.usecase.SetupMasterPasswordUseCase
import com.onekey.core.domain.usecase.UnlockVaultUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    val isSetupComplete: StateFlow<Boolean> = authRepository.isSetupComplete()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isUnlocked: StateFlow<Boolean> = authRepository.isUnlocked()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isPinSetup: StateFlow<Boolean> = authRepository.isPinSetup()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isBiometricEnabled: StateFlow<Boolean> = appPrefs.isBiometricEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setup(password: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            _state.value = when (val result = setupMasterPassword(password)) {
                is AppResult.Success -> AuthUiState.SetupComplete
                is AppResult.Error -> AuthUiState.Error(result.message ?: "Setup failed")
            }
        }
    }

    fun unlockWithPassword(password: CharArray) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            _state.value = when (val result = unlockVault.withPassword(password)) {
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

    fun clearError() { if (_state.value is AuthUiState.Error) _state.value = AuthUiState.Idle }
}
