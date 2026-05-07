package com.onekey.feature.vault.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.GetCredentialUseCase
import com.onekey.core.domain.usecase.HardDeleteCredentialUseCase
import com.onekey.core.domain.usecase.RestoreFromRecycleBinUseCase
import com.onekey.core.domain.usecase.SaveCredentialUseCase
import com.onekey.core.security.AutoLockManager
import com.onekey.core.security.SecureClipboardManager
import com.onekey.core.security.VaultLockedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
sealed class CredentialDetailUiState {
    data object Loading : CredentialDetailUiState()
    data class Success(val credential: Credential, val isEditing: Boolean = false) : CredentialDetailUiState()
    data object Saved : CredentialDetailUiState()
    data object Deleted : CredentialDetailUiState()
    data class Error(val message: String) : CredentialDetailUiState()
}

@HiltViewModel
class CredentialDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCredential: GetCredentialUseCase,
    private val saveCredential: SaveCredentialUseCase,
    private val deleteCredential: DeleteCredentialUseCase,
    private val hardDeleteCredential: HardDeleteCredentialUseCase,
    private val restoreFromBin: RestoreFromRecycleBinUseCase,
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
    private val tagRepository: TagRepository,
    private val secureClipboard: SecureClipboardManager,
    private val autoLockManager: AutoLockManager,
    appPrefs: AppPreferencesRepository,
) : ViewModel() {

    /**
     * Suppress the inactivity auto-lock while a camera-based flow (OCR scanner,
     * 2FA QR scanner) is active. Camera previews don't generate touch events,
     * so the idle timer would otherwise lock the vault mid-scan. Pair every
     * call with [endCameraSession]; the underlying counter clamps at zero so
     * an unmatched end is a no-op rather than corrupting timer state.
     *
     * The background timer is intentionally NOT suppressed here — turning the
     * screen off mid-scan should still lock the vault.
     */
    fun beginCameraSession() = autoLockManager.acquireInactivitySuppression()
    fun endCameraSession() = autoLockManager.releaseInactivitySuppression()

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _pendingRestoreConflict = MutableStateFlow<RestoreConflict?>(null)
    val pendingRestoreConflict: StateFlow<RestoreConflict?> = _pendingRestoreConflict.asStateFlow()

    private val credentialId: String? = savedStateHandle.get<String>("credentialId")
        ?.takeIf { it != "new" }

    private val initialTag: String = savedStateHandle.get<String>("initialTag") ?: ""

    private val initialType: CredentialType =
        CredentialType.fromNameOrDefault(savedStateHandle.get<String>("initialType"))

    private val _uiState = MutableStateFlow<CredentialDetailUiState>(CredentialDetailUiState.Loading)
    val uiState: StateFlow<CredentialDetailUiState> = _uiState.asStateFlow()

    /**
     * Frozen snapshot of `accessed_at` taken on the first non-null emission of
     * this screen's credential — what the UI shows under "Last accessed" on
     * the Details panel. Display-only, deliberately decoupled from the live
     * credential in [uiState] so that:
     *
     *   1. The user sees their *previous* access time (the whole point of the
     *      field). Bumping accessed_at on view would otherwise make the
     *      displayed value race forward to "now" on every open.
     *   2. Edits / saves round-trip the *live* credential, which carries the
     *      post-bump accessed_at. Keeping the frozen value off the credential
     *      means a save can't accidentally roll the timestamp back to the
     *      moment the screen opened.
     *
     * Stays null for the new-credential flow (no id to read) and during the
     * brief window before the first emission lands.
     */
    private val _displayAccessedAt = MutableStateFlow<Long?>(null)
    val displayAccessedAt: StateFlow<Long?> = _displayAccessedAt.asStateFlow()

    val availableTags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: Flow<List<CredentialHistoryEntry>> = credentialId
        ?.let { id -> historyRepository.observeHistory(id) }
        ?: flowOf(emptyList())

    init {
        if (credentialId == null) {
            _uiState.value = CredentialDetailUiState.Success(emptyCredential(), isEditing = true)
        } else {
            viewModelScope.launch {
                // Single Room subscription drives both the live UI state and
                // the one-shot accessed_at snapshot. The capture branch fires
                // exactly once — on the first emission with a non-null
                // credential — so the bump's own re-emission can't overwrite
                // the snapshot, and a missing/deleted row simply leaves the
                // snapshot null (UI hides "Last accessed" in that case).
                //
                // includingDeleted is intentional: a navigated-to recycle-bin
                // item still renders (with a restore banner) instead of
                // hanging on Loading.
                var hasCapturedAccess = false
                getCredential.includingDeleted(credentialId)
                    .distinctUntilChanged()
                    .collect { credential ->
                        if (credential != null && !hasCapturedAccess) {
                            hasCapturedAccess = true
                            // Order matters: set the display snapshot BEFORE
                            // the UI state transitions to Success so the very
                            // first frame of the Details panel already has
                            // its "Last accessed" value to render. Avoids a
                            // one-frame "Last accessed: —" flicker.
                            _displayAccessedAt.value = credential.accessedAt
                            // Fire-and-forget under the outer launch so the
                            // bump cancels cleanly if the user navigates
                            // away. The DAO's WHERE clause silently no-ops
                            // for soft-deleted rows.
                            launch { credentialRepository.markAccessed(credentialId) }
                        }
                        _uiState.update { state ->
                            when {
                                credential == null -> CredentialDetailUiState.Error("Credential not found")
                                state is CredentialDetailUiState.Loading ->
                                    CredentialDetailUiState.Success(credential)
                                state is CredentialDetailUiState.Success ->
                                    state.copy(credential = credential)
                                // Preserve terminal states — DB updates must not override Saved/Deleted.
                                else -> state
                            }
                        }
                    }
            }
        }
    }

    fun startEditing() {
        _uiState.update { if (it is CredentialDetailUiState.Success) it.copy(isEditing = true) else it }
    }

    fun save(credential: Credential) {
        viewModelScope.launch {
            val existing = (_uiState.value as? CredentialDetailUiState.Success)?.credential
            if (existing != null && existing.id.isNotBlank()) {
                historyRepository.snapshotCredential(existing)
            }
            when (val result = saveCredential(credential)) {
                is AppResult.Success -> _uiState.value = CredentialDetailUiState.Saved
                is AppResult.Error -> {
                    if (result.exception is VaultLockedException) {
                        // Vault auto-locked between editor open and Save tap. Don't surface
                        // the cryptic "Vault is locked" message — NavGraph's
                        // LaunchedEffect(isUnlocked) routes to LockScreen on the next
                        // recomposition. Leaving uiState on Success(isEditing=true) means
                        // that with restoreLastScreenOnUnlock enabled, the user lands back
                        // on this editor with their typed values still in rememberSaveable.
                        return@launch
                    }
                    _uiState.value = CredentialDetailUiState.Error(result.message ?: "Save failed")
                }
            }
        }
    }

    /** Soft-delete: moves credential to recycle bin (recoverable for 30 days). */
    fun delete() {
        val id = credentialId ?: return
        viewModelScope.launch {
            when (val result = deleteCredential(id)) {
                is AppResult.Success -> _uiState.value = CredentialDetailUiState.Deleted
                is AppResult.Error -> _uiState.value = CredentialDetailUiState.Error(result.message ?: "Delete failed")
            }
        }
    }

    /** Hard-delete: skips the recycle bin and removes the credential immediately. */
    fun deleteNow() {
        val id = credentialId ?: return
        viewModelScope.launch {
            when (val result = hardDeleteCredential(id)) {
                is AppResult.Success -> _uiState.value = CredentialDetailUiState.Deleted
                is AppResult.Error -> _uiState.value = CredentialDetailUiState.Error(result.message ?: "Delete failed")
            }
        }
    }

    /**
     * Restore the currently-displayed bin credential. Detects conflicts with active items
     * (same trimmed title + username) and exposes [pendingRestoreConflict] for the UI to
     * show a merge / keep-both choice. Same flow as the recycle-bin screen.
     */
    fun restore() {
        val current = (_uiState.value as? CredentialDetailUiState.Success)?.credential ?: return
        viewModelScope.launch {
            val conflict = restoreFromBin.findConflict(current)
            if (conflict != null) {
                _pendingRestoreConflict.value = RestoreConflict(current, conflict)
                return@launch
            }
            val result = restoreFromBin.restore(current.id)
            if (result is AppResult.Error) {
                _uiState.value = CredentialDetailUiState.Error(result.message ?: "Restore failed")
            }
            // On success the observe flow re-emits with deletedAt = null; the banner hides itself.
        }
    }

    fun resolveRestoreByMerging() {
        val conflict = _pendingRestoreConflict.value ?: return
        viewModelScope.launch {
            val result = restoreFromBin.mergeInto(conflict.existing, conflict.binItem)
            _pendingRestoreConflict.value = null
            if (result is AppResult.Error) {
                _uiState.value = CredentialDetailUiState.Error(result.message ?: "Merge failed")
            } else {
                // The bin item was permanently removed during merge — pop back to the prior screen.
                _uiState.value = CredentialDetailUiState.Deleted
            }
        }
    }

    fun resolveRestoreByKeepingBoth() {
        val conflict = _pendingRestoreConflict.value ?: return
        viewModelScope.launch {
            val result = restoreFromBin.restore(conflict.binItem.id)
            _pendingRestoreConflict.value = null
            if (result is AppResult.Error) {
                _uiState.value = CredentialDetailUiState.Error(result.message ?: "Restore failed")
            }
        }
    }

    fun cancelRestoreConflict() {
        _pendingRestoreConflict.value = null
    }

    /** Routes through SecureClipboardManager for sensitive auto-clear + sensitivity flag. */
    fun copyUsername() {
        val cred = (_uiState.value as? CredentialDetailUiState.Success)?.credential ?: return
        if (cred.username.isNotEmpty()) {
            secureClipboard.copySecure("Username", cred.username)
            bumpAccessedAt(cred.id)
        }
    }

    fun copyPassword() {
        val cred = (_uiState.value as? CredentialDetailUiState.Success)?.credential ?: return
        if (cred.password.isNotEmpty()) {
            secureClipboard.copySecure("Password", cred.password)
            bumpAccessedAt(cred.id)
        }
    }

    /**
     * Records that the user actually used this credential. Skipped for
     * unsaved-new entries (blank id) and silently skipped at the SQL layer
     * for soft-deleted ones. Fire-and-forget — a failed bump shouldn't
     * abort the copy itself, the user already has the value on the
     * clipboard.
     */
    private fun bumpAccessedAt(id: String) {
        if (id.isBlank()) return
        viewModelScope.launch { credentialRepository.markAccessed(id) }
    }

    data class RestoreConflict(val binItem: Credential, val existing: Credential)

    fun toggleFavorite() {
        val state = _uiState.value as? CredentialDetailUiState.Success ?: return
        val id = credentialId ?: return
        viewModelScope.launch {
            val result = credentialRepository.toggleFavorite(id, !state.credential.isFavorite)
            if (result is AppResult.Error) {
                _uiState.value = CredentialDetailUiState.Error(result.message ?: "Failed to update favourite")
            }
        }
    }

    fun addTag(name: String) {
        viewModelScope.launch {
            tagRepository.addTag(Tag(name = name, color = 0xFF6200EE.toInt(), icon = ""))
        }
    }

    private fun emptyCredential() = Credential(
        id = "", title = "", username = "", password = "", url = "",
        notes = "", otpParams = null,
        tags = if (initialTag.isNotEmpty()) listOf(initialTag) else emptyList(),
        customFields = emptyList(), createdAt = 0L, updatedAt = 0L,
        type = initialType,
    )
}
