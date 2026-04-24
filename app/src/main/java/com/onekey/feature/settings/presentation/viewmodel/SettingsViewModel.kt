package com.onekey.feature.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.LockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.DeleteTagUseCase
import com.onekey.core.domain.usecase.ResetVaultUseCase
import com.onekey.core.domain.usecase.SeedDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SettingsEvent {
    data object PinReset : SettingsEvent()
    data object VaultContentsDeleted : SettingsEvent()
    data class SeedComplete(val count: Int) : SettingsEvent()
    data class Error(val message: String) : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val appPrefs: AppPreferencesRepository,
    private val authRepository: AuthRepository,
    private val deleteTagUseCase: DeleteTagUseCase,
    private val resetVaultUseCase: ResetVaultUseCase,
    private val seedDataUseCase: SeedDataUseCase,
) : ViewModel() {

    private val _isSeedingData = MutableStateFlow(false)
    val isSeedingData: StateFlow<Boolean> = _isSeedingData.asStateFlow()

    private val _event = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<SettingsEvent> = _event.asSharedFlow()

    val tags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isDarkTheme: StateFlow<Boolean> = appPrefs.isDarkTheme()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isBiometricEnabled: StateFlow<Boolean> = appPrefs.isBiometricEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isPinSetup: StateFlow<Boolean> = authRepository.isPinSetup()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isScreenshotsEnabled: StateFlow<Boolean> = appPrefs.isScreenshotsEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val lockTimeout: StateFlow<LockTimeout> = appPrefs.getLockTimeout()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LockTimeout.IMMEDIATE)

    val isMasterPasswordRecheckEnabled: StateFlow<Boolean> = appPrefs.isMasterPasswordRecheckEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val masterPasswordRecheckInterval: StateFlow<MasterPasswordInterval> =
        appPrefs.getMasterPasswordRecheckInterval()
            .stateIn(viewModelScope, SharingStarted.Eagerly, MasterPasswordInterval.HOURS_48)

    val isShowFavourites: StateFlow<Boolean> = appPrefs.isShowFavourites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleTheme() {
        viewModelScope.launch { appPrefs.setDarkTheme(!isDarkTheme.value) }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setBiometricEnabled(enabled) }
    }

    fun setScreenshotsEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setScreenshotsEnabled(enabled) }
    }

    fun setLockTimeout(timeout: LockTimeout) {
        viewModelScope.launch { appPrefs.setLockTimeout(timeout) }
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

    fun resetPin() {
        viewModelScope.launch {
            when (val result = authRepository.resetPin()) {
                is AppResult.Success -> _event.emit(SettingsEvent.PinReset)
                is AppResult.Error -> _event.emit(SettingsEvent.Error(result.message ?: "Failed to reset PIN"))
            }
        }
    }

    fun deleteVaultContents() {
        viewModelScope.launch {
            when (val result = resetVaultUseCase()) {
                is AppResult.Success -> _event.emit(SettingsEvent.VaultContentsDeleted)
                is AppResult.Error -> _event.emit(SettingsEvent.Error(result.message ?: "Failed to delete vault"))
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
