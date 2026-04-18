package com.onekey.feature.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.ResetVaultUseCase
import com.onekey.core.domain.usecase.SeedDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SettingsEvent {
    data object PinReset : SettingsEvent()
    data object VaultReset : SettingsEvent()
    data class SeedComplete(val count: Int) : SettingsEvent()
    data class Error(val message: String) : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val appPrefs: AppPreferencesRepository,
    private val authRepository: AuthRepository,
    private val resetVaultUseCase: ResetVaultUseCase,
    private val seedDataUseCase: SeedDataUseCase,
) : ViewModel() {

    private val _isSeedingData = MutableStateFlow(false)
    val isSeedingData: StateFlow<Boolean> = _isSeedingData.asStateFlow()

    private val _event = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<SettingsEvent> = _event.asSharedFlow()

    val tags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isDarkTheme: StateFlow<Boolean> = appPrefs.isDarkTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isBiometricEnabled: StateFlow<Boolean> = appPrefs.isBiometricEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isPinSetup: StateFlow<Boolean> = authRepository.isPinSetup()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isScreenshotsEnabled: StateFlow<Boolean> = appPrefs.isScreenshotsEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun toggleTheme() {
        viewModelScope.launch { appPrefs.setDarkTheme(!isDarkTheme.value) }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setBiometricEnabled(enabled) }
    }

    fun setScreenshotsEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setScreenshotsEnabled(enabled) }
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
                tagRepository.deleteTag(name)
            }
        }
    }

    fun resetPin() {
        viewModelScope.launch {
            when (val result = authRepository.resetPin()) {
                is AppResult.Success -> _event.emit(SettingsEvent.PinReset)
                is AppResult.Error -> _event.emit(SettingsEvent.Error(result.message ?: "Failed to reset PIN"))
            }
        }
    }

    fun resetVault() {
        viewModelScope.launch {
            when (val result = resetVaultUseCase()) {
                is AppResult.Success -> _event.emit(SettingsEvent.VaultReset)
                is AppResult.Error -> _event.emit(SettingsEvent.Error(result.message ?: "Failed to reset vault"))
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
}
