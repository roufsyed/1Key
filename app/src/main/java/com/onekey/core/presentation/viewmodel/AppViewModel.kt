package com.onekey.core.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.usecase.PurgeExpiredRecycleBinUseCase
import com.onekey.core.domain.usecase.RestoreFromRecycleBinUseCase
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
    private val restoreFromRecycleBin: RestoreFromRecycleBinUseCase,
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

    /**
     * Restore a soft-deleted credential by id. Wired to the "Undo" action on the
     * post-delete snackbar in [com.onekey.core.presentation.navigation.OneKeyNavGraph].
     * Fire-and-forget under [viewModelScope] so the snackbar callback returns
     * immediately and the restore survives even if the user keeps navigating.
     *
     * Errors are intentionally silent: the credential simply stays in the bin if
     * the restore fails for some reason (vault locked mid-undo, row already purged
     * by retention, etc.). The user can recover it manually from the recycle-bin
     * screen if they want, so a follow-up error toast would be more noise than help.
     *
     * No conflict-resolution dialog is shown here because the just-deleted item's
     * (title, username) signature was unique at delete time. The narrow window for
     * the user to create a colliding active row before tapping Undo is acceptable.
     */
    fun undoRecycleBinDelete(credentialId: String) {
        viewModelScope.launch {
            restoreFromRecycleBin.restore(credentialId)
        }
    }
}
