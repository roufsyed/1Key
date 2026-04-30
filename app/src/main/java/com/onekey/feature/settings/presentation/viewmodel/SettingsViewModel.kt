package com.onekey.feature.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.DeleteTagUseCase
import com.onekey.core.domain.usecase.ResetVaultUseCase
import com.onekey.core.domain.usecase.SeedDataUseCase
import com.onekey.core.security.AuthAttemptsStore
import com.onekey.core.security.LockReason
import com.onekey.core.security.LockReasonStore
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

    val recycleBinRetention: StateFlow<RecycleBinRetention> = appPrefs.getRecycleBinRetention()
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecycleBinRetention.DAYS_30)

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isVaultFooterVisible: StateFlow<Boolean> = appPrefs.isVaultFooterVisible()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isRestoreLastScreenOnUnlock: StateFlow<Boolean> = appPrefs.isRestoreLastScreenOnUnlock()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleTheme() {
        viewModelScope.launch { appPrefs.setDarkTheme(!isDarkTheme.value) }
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
     * attempts lock the vault — same policy as the biometric-enable flow.
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

    companion object {
        private const val MAX_BIOMETRIC_ATTEMPTS = 3
    }
}
