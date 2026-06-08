package com.onekey.core.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.usecase.PurgeExpiredRecycleBinUseCase
import com.onekey.core.presentation.animation.UnlockTransitionPhase
import com.onekey.feature.sync.domain.SyncEngine
import com.onekey.feature.sync.domain.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    appPrefs: AppPreferencesRepository,
    private val purgeExpiredRecycleBin: PurgeExpiredRecycleBinUseCase,
    private val syncEngine: SyncEngine,
) : ViewModel() {

    val isUnlocked: StateFlow<Boolean> = authRepository.isUnlocked()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isShowFavourites: StateFlow<Boolean> = appPrefs.isShowFavourites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isRestoreLastScreenOnUnlock: StateFlow<Boolean> = appPrefs.isRestoreLastScreenOnUnlock()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _unlockPhase =
        MutableStateFlow<UnlockTransitionPhase>(UnlockTransitionPhase.Idle)
    val unlockPhase: StateFlow<UnlockTransitionPhase> = _unlockPhase.asStateFlow()

    /**
     * Sync chip state for the top-bar feedback. Republished directly from
     * [SyncEngine.state] - the engine is the source of truth for the actual
     * transitions; this VM just hoists the flow so Compose can observe it
     * via `collectAsStateWithLifecycle()`.
     */
    val syncChipState: StateFlow<SyncState> = syncEngine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SyncState.Idle)

    init {
        // Vault locking mid-transition (auto-lock, manual lock) must drop the curtain so it
        // doesn't linger on top of the LockScreen the user is being navigated back to.
        viewModelScope.launch {
            isUnlocked.collect { unlocked ->
                if (!unlocked) _unlockPhase.value = UnlockTransitionPhase.Idle
            }
        }
        // On every unlock, purge recycle-bin items past their 30-day retention window.
        // We don't surface failures - the bin keeps the items around until next unlock.
        viewModelScope.launch {
            isUnlocked.collect { unlocked ->
                if (unlocked) purgeExpiredRecycleBin()
            }
        }
    }

    fun dismissSyncChip() = syncEngine.dismissChip()

    fun beginUnlockMorph() {
        if (_unlockPhase.value !is UnlockTransitionPhase.Idle) return
        _unlockPhase.value = UnlockTransitionPhase.Expanding
    }

    fun markUnlockMorphHeld() {
        if (_unlockPhase.value !is UnlockTransitionPhase.Expanding) return
        _unlockPhase.value = UnlockTransitionPhase.Held
    }

    fun requestDismissUnlockMorph() {
        if (_unlockPhase.value !is UnlockTransitionPhase.Held) return
        _unlockPhase.value = UnlockTransitionPhase.Dismissing
    }

    fun resetUnlockMorph() {
        _unlockPhase.value = UnlockTransitionPhase.Idle
    }
}
