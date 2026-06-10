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
import com.onekey.core.domain.repository.NotesDisplayPrefsRepository
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Discriminator for the post-delete snackbar. SOFT is a recycle-bin move (recoverable),
 * HARD is the row vanishing for good. The NavGraph picks the wording from this.
 */
enum class DeleteKind { SOFT, HARD }

@Immutable
sealed class CredentialDetailUiState {
    data object Loading : CredentialDetailUiState()
    data class Success(val credential: Credential, val isEditing: Boolean = false) : CredentialDetailUiState()
    // Only emitted for brand-new credentials. The screen reads [newCredentialId]
    // and navigates to credential/{newCredentialId}, replacing the "new" entry on
    // the back stack so system-back goes to the list. Existing-credential edits
    // skip this state entirely - the view-model flips Success.isEditing back to
    // false in place, keeping the user on the same screen / same nav entry.
    data class Saved(val newCredentialId: String) : CredentialDetailUiState()
    /**
     * Carries the [credentialId] so the host can offer an Undo action on
     * SOFT deletes. The id is also set for HARD deletes for symmetry; the
     * host ignores it because there is nothing to restore.
     */
    data class Deleted(val kind: DeleteKind, val credentialId: String) : CredentialDetailUiState()
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
    private val appPrefs: AppPreferencesRepository,
    private val notesDisplayPrefs: NotesDisplayPrefsRepository,
) : ViewModel() {

    /**
     * Suppress the inactivity auto-lock while a camera-based flow (OCR scanner,
     * 2FA QR scanner) is active. Camera previews don't generate touch events,
     * so the idle timer would otherwise lock the vault mid-scan. Pair every
     * call with [endCameraSession]; the underlying counter clamps at zero so
     * an unmatched end is a no-op rather than corrupting timer state.
     *
     * The background timer is intentionally NOT suppressed here - turning the
     * screen off mid-scan should still lock the vault.
     */
    fun beginCameraSession() = autoLockManager.acquireInactivitySuppression()
    fun endCameraSession() = autoLockManager.releaseInactivitySuppression()

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Global master switch from Settings. When `false`, every credential's
     * notes field renders as plain text regardless of per-credential overrides.
     * Default `true` mirrors [AppPreferencesRepository.isNotesRenderMarkdownEnabled]
     * so the first frame of a freshly-opened credential does not flash
     * plain-text before the disk value arrives.
     */
    val isNotesMarkdownEnabled: StateFlow<Boolean> = appPrefs.isNotesRenderMarkdownEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Credential IDs the user has explicitly toggled to plain-source mode via
     * the overflow menu. Eagerly so a credential whose toggle is already on disk
     * never reads as `false` on the first composition. Empty-set initial value
     * is correct because absence-from-set is the "not in plain-source mode"
     * encoding and the disk emission overwrites it as soon as the DataStore
     * read completes.
     */
    val plainSourceCredentialIds: StateFlow<Set<String>> =
        notesDisplayPrefs.observeIdsInPlainSourceMode()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * Credential IDs already auto-flipped to plain-source on first view post-import.
     * Membership is sticky across process restarts so [maybeAutoFlip] is a no-op
     * after the first call per credential.
     */
    val autoFlippedCredentialIds: StateFlow<Set<String>> =
        notesDisplayPrefs.observeAutoFlippedIds()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * One-shot transient events for the notes-display surface (Snackbar copy,
     * size-cap banner signal, blocked-link toasts). `extraBufferCapacity = 1`
     * with `DROP_OLDEST` so an emit racing the screen-level collector still
     * delivers the most recent message rather than blocking
     * [viewModelScope.launch] callers like [maybeAutoFlip].
     */
    private val _notesEvent = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notesEvent: SharedFlow<String> = _notesEvent.asSharedFlow()

    val clipboardCountdown: StateFlow<Int?> = secureClipboard.countdown

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
     * this screen's credential - what the UI shows under "Last accessed" on
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
                // exactly once - on the first emission with a non-null
                // credential - so the bump's own re-emission can't overwrite
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
                            // one-frame "Last accessed: -" flicker.
                            _displayAccessedAt.value = credential.accessedAt
                            // Fire-and-forget under the outer launch so the
                            // bump cancels cleanly if the user navigates
                            // away. The DAO's WHERE clause silently no-ops
                            // for soft-deleted rows.
                            launch { credentialRepository.markAccessed(credentialId) }
                        }
                        _uiState.update { state ->
                            when {
                                // Preserve terminal states - DB updates must not override
                                // Saved/Deleted. Critical for hard-delete: deleteNow() sets
                                // Deleted, the row vanishes, and this flow emits a final
                                // credential=null. Without this ordering, the null branch
                                // would flip Deleted to Error("Credential not found") before
                                // the screen's LaunchedEffect dispatches onDeleted(),
                                // producing a "credential not found" flash mid-navigation.
                                state is CredentialDetailUiState.Deleted -> state
                                state is CredentialDetailUiState.Saved -> state
                                credential == null -> CredentialDetailUiState.Error("Credential not found")
                                state is CredentialDetailUiState.Loading ->
                                    CredentialDetailUiState.Success(credential)
                                state is CredentialDetailUiState.Success ->
                                    state.copy(credential = credential)
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
            // Pre-generate the id for new credentials so we know what to navigate to after save.
            // CredentialRepositoryImpl.toEntity() also generates if blank, but it doesn't
            // surface the generated id back to callers - so we generate here, keep it, and
            // hand the same id to both the repo and the post-save nav callback.
            val wasNew = credential.id.isBlank()
            val toSave = if (wasNew) credential.copy(id = UUID.randomUUID().toString()) else credential
            when (val result = saveCredential(toSave)) {
                is AppResult.Success -> {
                    if (wasNew) {
                        _uiState.value = CredentialDetailUiState.Saved(toSave.id)
                    } else {
                        // Flip to view mode in place. The Room observer at init{} will re-emit
                        // the canonical row immediately and its state.copy(credential = ...)
                        // branch preserves isEditing = false.
                        _uiState.update { state ->
                            when (state) {
                                is CredentialDetailUiState.Success -> state.copy(
                                    credential = toSave,
                                    isEditing = false,
                                )
                                else -> CredentialDetailUiState.Success(
                                    credential = toSave,
                                    isEditing = false,
                                )
                            }
                        }
                    }
                }
                is AppResult.Error -> {
                    if (result.exception is VaultLockedException) {
                        // Vault auto-locked between editor open and Save tap. Don't surface
                        // the cryptic "Vault is locked" message - NavGraph's
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
                is AppResult.Success -> _uiState.value = CredentialDetailUiState.Deleted(DeleteKind.SOFT, id)
                is AppResult.Error -> _uiState.value = CredentialDetailUiState.Error(result.message ?: "Delete failed")
            }
        }
    }

    /** Hard-delete: skips the recycle bin and removes the credential immediately. */
    fun deleteNow() {
        val id = credentialId ?: return
        viewModelScope.launch {
            when (val result = hardDeleteCredential(id)) {
                is AppResult.Success -> _uiState.value = CredentialDetailUiState.Deleted(DeleteKind.HARD, id)
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
                // The bin item was permanently removed during merge - pop back to the prior
                // screen. HARD because the row is gone for good after the merge subsumes it.
                _uiState.value = CredentialDetailUiState.Deleted(DeleteKind.HARD, conflict.binItem.id)
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
     * for soft-deleted ones. Fire-and-forget - a failed bump shouldn't
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

    /**
     * Flip the per-credential notes view between rendered markdown and plain
     * source. Driven by the overflow-menu item on the detail screen. No-ops
     * for a blank id (the new-credential flow has no persistent ID to key
     * against). The read-then-write race is acceptable because this is the
     * only writer for the user-toggled path; a double tap simply lands on
     * the second value, which is the user's most recent intent.
     */
    fun toggleViewMode(credentialId: String) {
        if (credentialId.isBlank()) return
        viewModelScope.launch {
            val currentlyPlain = credentialId in plainSourceCredentialIds.value
            notesDisplayPrefs.setPlainSource(credentialId, !currentlyPlain)
        }
    }

    /**
     * First-view-post-import heuristic. Imported credentials whose notes were
     * created in another app likely use markdown-shaped text that wasn't
     * meant for inline rendering, so the design defaults them to plain-source
     * mode on first view. Idempotent after the first call per credential -
     * the [autoFlippedCredentialIds] sentinel prevents re-flipping if the
     * user has since manually toggled back to rendered mode.
     *
     * Ordering matters: persist the plain-source flag first, then the
     * auto-flipped sentinel, then emit the Snackbar. This way the UI sees
     * the plain-source state before the user notices the toast, and a
     * crash between the two writes leaves the heuristic re-runnable rather
     * than silently consumed.
     *
     * @param importedAt epoch-ms from [Credential.importedAt]; `null` for
     *   manually-entered credentials, which skip the flip.
     */
    fun maybeAutoFlip(credentialId: String, importedAt: Long?) {
        if (credentialId.isBlank() || importedAt == null) return
        if (credentialId in autoFlippedCredentialIds.value) return
        viewModelScope.launch {
            notesDisplayPrefs.setPlainSource(credentialId, true)
            notesDisplayPrefs.markAutoFlipped(credentialId)
            _notesEvent.tryEmit(
                "Imported notes shown as source. Tap the overflow menu to render as markdown."
            )
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
