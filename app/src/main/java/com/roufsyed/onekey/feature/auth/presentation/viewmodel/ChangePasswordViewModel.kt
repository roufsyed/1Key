package com.roufsyed.onekey.feature.auth.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class ChangePasswordUiState {
    data object Idle : ChangePasswordUiState()
    data object Loading : ChangePasswordUiState()
    data object Success : ChangePasswordUiState()
    data class Error(val message: String) : ChangePasswordUiState()
}

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ChangePasswordUiState>(ChangePasswordUiState.Idle)
    val state: StateFlow<ChangePasswordUiState> = _state.asStateFlow()

    fun changePassword(oldPassword: CharArray, newPassword: CharArray) {
        viewModelScope.launch {
            _state.value = ChangePasswordUiState.Loading
            // Two PBKDF2 derivations (verify old + derive new verifier) on Default so
            // the loading state paints and the button doesn't freeze.
            val result = withContext(Dispatchers.Default) {
                authRepository.changePassword(oldPassword, newPassword)
            }
            _state.value = when (result) {
                is AppResult.Success -> ChangePasswordUiState.Success
                is AppResult.Error -> ChangePasswordUiState.Error(result.message ?: "Failed to change password")
            }
        }
    }

    fun clearError() {
        if (_state.value is ChangePasswordUiState.Error) _state.value = ChangePasswordUiState.Idle
    }
}
