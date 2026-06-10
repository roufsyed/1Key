package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.data.snapshot.SnapshotCredential
import com.onekey.core.data.snapshot.SnapshotState
import com.onekey.core.di.DefaultDispatcher
import com.onekey.core.di.SnapshotStateFlow
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.HardDeleteCredentialUseCase
import com.onekey.core.domain.usecase.RestoreFromRecycleBinUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Favourites tab ViewModel.
 *
 * Derives the displayed list from the shared
 * [com.onekey.core.data.snapshot.VaultSnapshotStore] via [SnapshotStateFlow],
 * filtering to `isFavorite = true` and applying the user's
 * [CredentialSortOrder] in-memory. The lean [SnapshotCredential] projection
 * means no password / notes / OTP secret ever enters this VM's state.
 *
 * **State surface.** [listState] is a [StateFlow] of [CredentialListState]:
 *
 *  - [CredentialListState.Locked]   while the snapshot is locked. The
 *    snapshot store's synchronous lock hook releases its plaintext list
 *    reference before [com.onekey.core.security.VaultKeyHolder.lock] returns;
 *    this VM has no VM-local list plaintext to clear, only the selection set
 *    (handled in [init]).
 *  - [CredentialListState.Loading]  during the first decrypt pass or while
 *    the cipher migrator is rewriting legacy rows. UI shows a spinner; the
 *    empty-state copy is not used in this branch.
 *  - [CredentialListState.Loaded]   filtered + sorted lean projection ready.
 *    An empty list with the standard "no favourites yet" empty-state is a
 *    valid Loaded value.
 *  - [CredentialListState.Bypassed] vault size exceeds the snapshot cap. UI
 *    surfaces the "vault too large" pane and does NOT fall back to the
 *    legacy paged decrypt-all path (which would re-introduce per-row
 *    plaintext residency PR4 is removing).
 *
 * `SharingStarted.WhileSubscribed(5_000)` keeps the filter+sort body from
 * running while the tab is off-screen; plaintext residency is governed by
 * the always-live snapshot store, not by this StateFlow's collector count.
 * The 5 s grace absorbs configuration-change re-subscription gaps without
 * restarting upstream.
 */
@HiltViewModel
class FavouritesViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val deleteCredential: DeleteCredentialUseCase,
    private val hardDeleteCredential: HardDeleteCredentialUseCase,
    private val restoreFromRecycleBin: RestoreFromRecycleBinUseCase,
    private val appPrefs: AppPreferencesRepository,
    @SnapshotStateFlow private val snapshotState: StateFlow<@JvmSuppressWildcards SnapshotState>,
    @DefaultDispatcher private val filterDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sortOrder: StateFlow<CredentialSortOrder> = appPrefs.getCredentialSortOrder()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CredentialSortOrder.NEWEST_FIRST)

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val listState: StateFlow<CredentialListState> = combine(snapshotState, sortOrder) { snapshot, order ->
        when (snapshot) {
            is SnapshotState.Locked -> CredentialListState.Locked
            is SnapshotState.Loading -> CredentialListState.Loading
            is SnapshotState.Bypassed -> CredentialListState.Bypassed
            is SnapshotState.Loaded -> {
                val filtered = snapshot.credentials.filter { it.isFavorite }
                CredentialListState.Loaded(filtered.sortedFor(order))
            }
        }
    }
        .flowOn(filterDispatcher)
        .distinctUntilChanged()
        // Initial value matches the snapshot store's resting state. Locked
        // (not Loading) so a cold subscribe does not flash a spinner over an
        // already-warm snapshot.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CredentialListState.Locked)

    /**
     * Alphabet-jump index. Non-empty only while the list is `Loaded` AND
     * sort is `ALPHABETICAL`. Runs on the filter dispatcher to keep the
     * scan off Main even at the snapshot cap.
     */
    val letterIndex: StateFlow<Map<Char, Int>> = combine(listState, sortOrder) { state, order ->
        if (order != CredentialSortOrder.ALPHABETICAL || state !is CredentialListState.Loaded) emptyMap()
        else buildLetterIndex(state.credentials.map { it.title })
    }
        .flowOn(filterDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

    fun setSortOrder(order: CredentialSortOrder) {
        viewModelScope.launch { appPrefs.setCredentialSortOrder(order) }
    }

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = _selectedIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Drives the selection-mode favourite icon. On this tab every visible
     * row is favourited so the action always defaults to "remove from
     * favourites", but the flag is computed the same way as on
     * [TaggedCredentialListViewModel] for symmetry across the two surfaces.
     */
    val selectedAreAllFavourite: StateFlow<Boolean> =
        combine(_selectedIds, listState) { ids, state ->
            if (ids.isEmpty() || state !is CredentialListState.Loaded || state.credentials.isEmpty()) false
            else {
                val byId: Map<String, SnapshotCredential> = state.credentials.associateBy { it.id }
                ids.all { id -> byId[id]?.isFavorite == true }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    private val _event = MutableSharedFlow<CredentialListEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<CredentialListEvent> = _event.asSharedFlow()

    init {
        // Selection plaintext (just credential ids - not sensitive) must
        // clear on lock or bypass so a re-unlock starts fresh. Subscribe
        // to snapshotState directly (not listState): listState is
        // WhileSubscribed, so wiring this observer through it would silently
        // counterfeit a collector and defeat the unsubscription contract.
        // snapshotState is always hot from the store.
        snapshotState
            .onEach { s ->
                if (s is SnapshotState.Locked || s is SnapshotState.Bypassed) {
                    _selectedIds.value = emptySet()
                }
            }
            .launchIn(viewModelScope)
    }

    fun toggleSelection(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * Restore the credentials that were soft-deleted by the most recent
     * bulk action. Wired to the "Undo" action on the post-bulk-delete
     * snackbar. Fire-and-forget under [viewModelScope]; failures are
     * silently dropped (the row simply stays in the bin and the user can
     * recover it manually from the recycle-bin screen). Matches the
     * single-id undo pattern in [com.onekey.core.presentation.viewmodel.AppViewModel.undoRecycleBinDelete].
     */
    fun undoBulkDelete(ids: List<String>) {
        viewModelScope.launch {
            ids.forEach { restoreFromRecycleBin.restore(it) }
        }
    }

    /** Soft-delete: moves selected to recycle bin. */
    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value.toList()
            _selectedIds.value = emptySet()
            val results = ids
                .map { id -> async { id to deleteCredential(id) } }
                .awaitAll()
            val succeeded = results.filter { it.second is AppResult.Success }.map { it.first }
            val failures = results.size - succeeded.size
            if (succeeded.isNotEmpty()) {
                _event.emit(CredentialListEvent.DeleteCompleted(DeleteKind.SOFT, succeeded))
            }
            if (failures > 0) {
                _event.emit(CredentialListEvent.DeleteError(failures))
            }
        }
    }

    /** Hard-delete: skips the recycle bin and removes selected immediately. */
    fun deleteSelectedNow() {
        viewModelScope.launch {
            val ids = _selectedIds.value.toList()
            _selectedIds.value = emptySet()
            val results = ids
                .map { id -> async { id to hardDeleteCredential(id) } }
                .awaitAll()
            val succeeded = results.filter { it.second is AppResult.Success }.map { it.first }
            val failures = results.size - succeeded.size
            if (succeeded.isNotEmpty()) {
                _event.emit(CredentialListEvent.DeleteCompleted(DeleteKind.HARD, succeeded))
            }
            if (failures > 0) {
                _event.emit(CredentialListEvent.DeleteError(failures))
            }
        }
    }

    fun setFavouriteOnSelected(makeFavourite: Boolean) {
        viewModelScope.launch {
            val ids = _selectedIds.value.toList()
            _selectedIds.value = emptySet()
            val failures = ids
                .map { id -> async { credentialRepository.toggleFavorite(id, makeFavourite) } }
                .awaitAll()
                .count { it is AppResult.Error }
            val updated = ids.size - failures
            if (updated > 0) {
                _event.emit(CredentialListEvent.FavouriteUpdated(updated, makeFavourite))
            }
            if (failures > 0) {
                _event.emit(CredentialListEvent.FavouriteError(failures))
            }
        }
    }
}

/**
 * Project-once ALPHABETICAL sort: `O(n)` `.lowercase()` calls instead of the
 * `O(n log n)` cascade that a naive `compareBy { it.title.lowercase() }`
 * would invoke during the sort. Other orders use the lean comparator.
 */
internal fun List<SnapshotCredential>.sortedFor(order: CredentialSortOrder): List<SnapshotCredential> =
    if (order == CredentialSortOrder.ALPHABETICAL) {
        map { it to it.title.lowercase() }
            .sortedBy { it.second }
            .map { it.first }
    } else {
        sortedWith(order.snapshotComparator())
    }
