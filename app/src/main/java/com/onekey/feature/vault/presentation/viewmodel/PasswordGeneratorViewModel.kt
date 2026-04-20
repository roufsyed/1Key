package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.PasswordConfig
import com.onekey.core.domain.model.PasswordStrength
import com.onekey.core.domain.usecase.GeneratePasswordUseCase
import com.onekey.core.security.SecureClipboardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PasswordGenState(
    val config: PasswordConfig = PasswordConfig(),
    val password: String = "",
    val strength: PasswordStrength = PasswordStrength.WEAK,
)

@HiltViewModel
class PasswordGeneratorViewModel @Inject constructor(
    private val generatePassword: GeneratePasswordUseCase,
    private val clipboardManager: SecureClipboardManager,
) : ViewModel() {

    private val _state = MutableStateFlow(PasswordGenState())
    val state: StateFlow<PasswordGenState> = _state.asStateFlow()

    private var generationJob: Job? = null

    init {
        scheduleGeneration()
    }

    fun updateConfig(config: PasswordConfig) {
        _state.update { it.copy(config = config) }
        scheduleGeneration(debounceMs = 80L)
    }

    fun regenerate() {
        scheduleGeneration()
    }

    fun copyToClipboard() {
        val pw = _state.value.password
        if (pw.isNotEmpty()) clipboardManager.copySecure("Password", pw)
    }

    // Returns the current password so the caller (sheet) can inject it into the credential form.
    fun currentPassword(): String = _state.value.password

    private fun scheduleGeneration(debounceMs: Long = 0L) {
        // All mutations are on Main.immediate — no concurrent access to generationJob.
        viewModelScope.launch(Dispatchers.Main.immediate) {
            generationJob?.cancel()
            generationJob = viewModelScope.launch(Dispatchers.Default) {
                if (debounceMs > 0L) delay(debounceMs)
                val config = _state.value.config
                val result = generatePassword(config)
                _state.update { it.copy(password = result.password, strength = result.strength) }
            }
        }
    }
}
