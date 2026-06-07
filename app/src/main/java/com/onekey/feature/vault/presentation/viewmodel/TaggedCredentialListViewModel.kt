package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
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
import com.onekey.feature.vault.presentation.screen.TAG_ALL
import com.onekey.feature.vault.presentation.screen.TAG_FAVORITES
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CredentialListEvent {
    data class DeleteError(val count: Int) : CredentialListEvent()
    data class FavouriteUpdated(val count: Int, val markedAs: Boolean) : CredentialListEvent()
    data class FavouriteError(val count: Int) : CredentialListEvent()
}

/**
 * ViewModel for the tag-scoped list screen. Routes the shared
 * [com.onekey.core.data.snapshot.VaultSnapshotStore] through a tag predicate,
 * a debounced search query, and the user's [CredentialSortOrder] to produce
 * a [CredentialListState] consumed by `TaggedCredentialListScreen`.
 *
 * Tag routing (resolved once at construction from `savedStateHandle["tagName"]`):
 *
 *  - `TAG_ALL` or empty string : no filter (entire active vault).
 *  - `TAG_FAVORITES`           : `c.isFavorite` predicate.
 *  - any other string          : exact-match `rawTag in c.tags`. Exact-equal
 *    via [List.contains] is strictly safer than the legacy SQL JSON-LIKE
 *    pattern (no chance a tag "Bank" picks up a credential tagged "Banking").
 *
 * Search uses `title.contains(q, ignoreCase = true)` with a 150 ms debounce.
 * Username matching is intentionally NOT included to match
 * `VaultViewModel.searchResults` (the main-vault search). The autofill VM's
 * broader title+username + host match lives there because its UX problem is
 * different.
 *
 * The `searchQuery` is persisted in [SavedStateHandle] under a `rawTag`-scoped
 * key so per-tag queries survive process death without cross-tag leakage.
 * Same on-disk PII trade-off as `AutofillUnlockViewModel`'s `KEY_SEARCH_QUERY`
 * persistence; documented here for parity.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class TaggedCredentialListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val deleteCredential: DeleteCredentialUseCase,
    private val hardDeleteCredential: HardDeleteCredentialUseCase,
    private val appPrefs: AppPreferencesRepository,
    @SnapshotStateFlow private val snapshotState: StateFlow<@JvmSuppressWildcards SnapshotState>,
    @DefaultDispatcher private val filterDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val rawTag: String = savedStateHandle.get<String>("tagName") ?: ""

    val displayName: String = when (rawTag) {
        TAG_ALL       -> "All Items"
        TAG_FAVORITES -> "Favorites"
        else          -> rawTag
    }

    /** Built once at construction so the combine pipeline does not re-resolve. */
    private val tagPredicate: (SnapshotCredential) -> Boolean = when (rawTag) {
        TAG_FAVORITES -> { c -> c.isFavorite }
        TAG_ALL, ""   -> { _ -> true }
        else          -> { c -> rawTag in c.tags }
    }

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sortOrder: StateFlow<CredentialSortOrder> = appPrefs.getCredentialSortOrder()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CredentialSortOrder.NEWEST_FIRST)

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // SavedStateHandle key is rawTag-scoped so a query typed under one tag
    // doesn't surface when the user navigates to a sibling tag. Same disk
    // PII trade-off as AutofillUnlockViewModel.KEY_SEARCH_QUERY; documented
    // at class level.
    private val searchQueryKey: String = "${KEY_SEARCH_QUERY_PREFIX}_$rawTag"

    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow(searchQueryKey, "")

    fun setSearchQuery(q: String) {
        savedStateHandle[searchQueryKey] = q
    }

    val listState: StateFlow<CredentialListState> = combine(
        snapshotState,
        sortOrder,
        searchQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
    ) { snapshot, order, q ->
        when (snapshot) {
            is SnapshotState.Locked -> CredentialListState.Locked
            is SnapshotState.Loading -> CredentialListState.Loading
            is SnapshotState.Bypassed -> CredentialListState.Bypassed
            is SnapshotState.Loaded -> {
                val tagFiltered = snapshot.credentials.filter(tagPredicate)
                val queryFiltered = if (q.isBlank()) tagFiltered
                    else tagFiltered.filter { it.title.contains(q, ignoreCase = true) }
                CredentialListState.Loaded(queryFiltered.sortedFor(order))
            }
        }
    }
        .flowOn(filterDispatcher)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CredentialListState.Locked)

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
     * Drives the selection-mode favourite icon: when all selected are already
     * favourited the action becomes "remove from favourites", otherwise it adds.
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
        // Mirror FavouritesViewModel: selection ids clear on snapshot Locked
        // or Bypassed. Subscribe to snapshotState directly (not listState,
        // which is WhileSubscribed and would silently keep this VM as a
        // "subscriber" indefinitely).
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

    /** Soft-delete: moves selected to recycle bin. */
    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value.toList()
            _selectedIds.value = emptySet()
            val failures = ids
                .map { id -> async { deleteCredential(id) } }
                .awaitAll()
                .count { it is AppResult.Error }
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
            val failures = ids
                .map { id -> async { hardDeleteCredential(id) } }
                .awaitAll()
                .count { it is AppResult.Error }
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

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val KEY_SEARCH_QUERY_PREFIX = "tagged_search_query"
    }
}

internal fun buildLetterIndex(titles: List<String>): Map<Char, Int> {
    val index = mutableMapOf<Char, Int>()
    titles.forEachIndexed { i, title ->
        val ch = title.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
        if (ch !in index) index[ch] = i
    }
    return index
}
