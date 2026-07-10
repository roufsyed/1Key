package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.data.snapshot.SnapshotCredential
import com.roufsyed.onekey.core.data.snapshot.SnapshotState
import com.roufsyed.onekey.core.di.SnapshotStateFlow
import com.roufsyed.onekey.core.domain.model.TagWithCount
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Home-screen ViewModel for the main vault.
 *
 * **Search.** `searchResults` is a [StateFlow] of [SearchState] derived from the
 * shared [com.roufsyed.onekey.core.data.snapshot.VaultSnapshotStore] - not from a per-VM
 * paged query. The store keeps a hot, lean-projection decrypted list while the
 * vault is unlocked, so the first keystroke after unlock filters an already-warm
 * list rather than triggering a cold Room invalidation + per-row HKDF + AES-GCM
 * decrypt pass (which produced the user-visible "search returns empty until
 * All Items is visited once" symptom).
 *
 * Filtering is a case-insensitive `title.contains` match sorted newest-first
 * by `createdAt`. Tag filtering and alternate sort orders are out of scope for
 * the top-bar search; the tag-specific surface lives in
 * `TaggedCredentialListViewModel`.
 *
 * The 150 ms debounce matches `AutofillUnlockViewModel.SEARCH_DEBOUNCE_MS` so the
 * two search surfaces feel identical on rapid typing.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class VaultViewModel @Inject constructor(
    tagRepository: TagRepository,
    credentialRepository: CredentialRepository,
    @SnapshotStateFlow snapshotState: StateFlow<@JvmSuppressWildcards SnapshotState>,
    appPrefs: AppPreferencesRepository,
) : ViewModel() {

    /**
     * Sealed state for the top-bar search results pane.
     *
     *  - [Idle]      - query is blank, OR vault is locked. UI shows the
     *                  "Type to search credentials…" hint.
     *  - [Loading]   - snapshot is still completing its first decrypt pass
     *                  (or the cipher migrator is rewriting legacy rows). UI
     *                  shows a spinner; never "No results".
     *  - [Loaded]    - filtered list ready. Empty list + non-blank query
     *                  renders the "No results for …" empty state.
     *  - [Bypassed]  - vault size exceeds the snapshot cap. Fast in-memory
     *                  search is disabled; UI prompts the user to browse via
     *                  All Items. A paged SQL fallback can be wired here in a
     *                  later PR; deferred because real vaults rarely exceed
     *                  the cap.
     */
    sealed class SearchState {
        data object Idle : SearchState()
        data object Loading : SearchState()
        data class Loaded(val credentials: List<SnapshotCredential>) : SearchState()
        data object Bypassed : SearchState()
    }

    val tagCounts: StateFlow<List<TagWithCount>> = tagRepository.observeTagsWithCounts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isVaultFooterVisible: StateFlow<Boolean> = appPrefs.isVaultFooterVisible()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val totalCount: StateFlow<Int> = credentialRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val favoriteCount: StateFlow<Int> = credentialRepository.observeFavoriteCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val recycleBinCount: StateFlow<Int> = credentialRepository.observeRecycleBinCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchResults: StateFlow<SearchState> = combine(
        snapshotState,
        _searchQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
    ) { snapshot, q ->
        if (q.isBlank()) {
            SearchState.Idle
        } else when (snapshot) {
            is SnapshotState.Locked -> SearchState.Idle
            is SnapshotState.Loading -> SearchState.Loading
            is SnapshotState.Bypassed -> SearchState.Bypassed
            is SnapshotState.Loaded -> SearchState.Loaded(
                snapshot.credentials.asSequence()
                    .filter { it.title.contains(q, ignoreCase = true) }
                    .sortedByDescending { it.createdAt }
                    .toList()
            )
        }
    }
        // Coalesce unrelated upstream re-fires: a save/toggle on a row that
        // doesn't match the active query produces a fresh `Loaded(snapshot...)`
        // upstream, but the filter output is structurally equal to the
        // previous one. Data-class equality on `Loaded` + `List` +
        // `SnapshotCredential` makes this free.
        .distinctUntilChanged()
        // `WhileSubscribed(5_000)` (not Eagerly): while the user is on
        // Settings, the lock screen, or another tab, no Compose collector is
        // alive - the filter+sort body should not run for every Room write.
        // 5 s grace covers configuration changes (rotation). Initial value
        // is `Idle`, which is what an unobserved bar should report anyway.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SearchState.Idle)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
