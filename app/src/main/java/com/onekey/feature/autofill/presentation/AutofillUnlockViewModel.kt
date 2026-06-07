package com.onekey.feature.autofill.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.TagWithCount
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.feature.autofill.domain.HostExtractor
import com.onekey.feature.autofill.domain.PackageMatcher
import com.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [AutofillUnlockActivity].
 *
 * Responsibilities:
 *
 *  - Extracts the [ParsedFields] argument from [SavedStateHandle], surfaced
 *    as a sealed [InitialState] so the activity can finish gracefully if the
 *    extra is missing rather than crash inside the ViewModel constructor.
 *
 *  - Loads exact-host credential matches via [PackageMatcher].
 *
 *  - **Search**: holds a decrypted vault *snapshot* after unlock and filters
 *    it in-memory on every debounced keystroke. The snapshot is loaded exactly
 *    once per unlocked-vault lifetime (`getAllCredentials()` is decrypt-all
 *    and expensive - see `CredentialRepositoryImpl.toDomain`). Re-locking the
 *    vault clears the snapshot, the search results, and the matches; a
 *    subsequent re-unlock re-fetches.
 *
 *  - Surfaces a `crossHostFor: StateFlow<Credential?>` so the activity can
 *    render a confirmation pane before delivering a Dataset for a credential
 *    whose stored host does not match the form's [ParsedFields.webDomain]
 *    (or any credential when the form is a native app without a webDomain).
 *    The exact-host path A policy is preserved - the search escape hatch
 *    surfaces the user's explicit cross-origin intent before any plaintext
 *    leaves 1Key's UID.
 *
 *  - Persists `searchQuery` and `startInSearch` into [SavedStateHandle] so
 *    process-death recovery preserves user input.
 *
 * Threading invariant - **only** call the public `unlockWith*` methods on
 * `AuthViewModel` from this surface. Never the `verifyMasterPasswordForPinChange`
 * or `verifyCurrentPin` paths: those use the in-vault [AuthAttemptsStore]
 * (session-scoped, scoped to a different threat model) and would route a
 * regular unlock failure through the wrong counter.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class AutofillUnlockViewModel @Inject constructor(
    private val packageMatcher: PackageMatcher,
    private val credentialRepository: CredentialRepository,
    private val authRepository: AuthRepository,
    private val tagRepository: TagRepository,
    private val appPreferences: AppPreferencesRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** Sealed initial state - keeps the missing-extra path off the crash rails. */
    sealed class InitialState {
        data class Ready(val parsed: ParsedFields) : InitialState()
        data object Invalid : InitialState()
    }

    val initial: InitialState =
        when (val p = savedState.get<ParsedFields>(AutofillUnlockActivity.EXTRA_PARSED_FIELDS)) {
            null -> InitialState.Invalid
            else -> InitialState.Ready(p)
        }

    /**
     * `true` when the service routed the user here via the trailing "Search
     * 1Key" chip rather than the locked-vault chip. Activity uses it to land
     * directly on the search surface and to seed the query with the host.
     */
    val startInSearch: Boolean =
        savedState.get<Boolean>(AutofillUnlockActivity.EXTRA_START_IN_SEARCH) ?: false

    // ── exact-host matches ──────────────────────────────────────────────────

    sealed class MatchState {
        data object Idle : MatchState()
        data object Loading : MatchState()
        data class Loaded(val credentials: List<Credential>) : MatchState()
    }

    private val _matches = MutableStateFlow<MatchState>(MatchState.Idle)
    val matches: StateFlow<MatchState> = _matches.asStateFlow()

    // ── search ──────────────────────────────────────────────────────────────

    sealed class SearchState {
        data object Idle : SearchState()
        data object Loading : SearchState()
        data class Loaded(val credentials: List<Credential>) : SearchState()
    }

    /** Live query backed by SavedStateHandle so it survives process death. */
    val searchQuery: StateFlow<String> =
        savedState.getStateFlow(KEY_SEARCH_QUERY, "")

    /**
     * Active tag filter, `null` for "All". SavedStateHandle-backed so a
     * tag the user picked survives process death exactly like the query.
     */
    val selectedTag: StateFlow<String?> =
        savedState.getStateFlow<String?>(KEY_SELECTED_TAG, null)

    /**
     * Tags + usage counts surfaced as filter chips in the search screen.
     * Empty when the category-filter pref is off - the activity uses that as
     * its hide-the-row signal so off-by-default users see no chip strip.
     * Eagerly stated: the autofill activity is short-lived, the upstream tag
     * query is cheap, and a deterministic value at construction simplifies
     * both Compose collection and unit testing.
     */
    val availableTags: StateFlow<List<TagWithCount>> = appPreferences
        .isAutofillCategoryFilterEnabled()
        .distinctUntilChanged()
        .let { enabledFlow ->
            combine(enabledFlow, tagRepository.observeTagsWithCounts()) { on, tags ->
                if (!on) emptyList() else tags
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _searchResults = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchResults: StateFlow<SearchState> = _searchResults.asStateFlow()

    /**
     * Decrypted vault snapshot. `null` while locked or before the first load.
     * Cleared on relock so the plaintext list does not outlive the unlocked
     * vault. Search filtering reads this; the exact-host matcher does its own
     * decrypt-all (kept separate to avoid coupling its timing to ours).
     */
    private val _snapshot = MutableStateFlow<List<Credential>?>(null)

    /**
     * Set by [resolveCandidate] when the user selects a credential whose
     * stored host doesn't match the form. The activity reads this to show a
     * cross-host confirmation pane. `null` after confirmation/dismissal.
     */
    private val _crossHostFor = MutableStateFlow<Credential?>(null)
    val crossHostFor: StateFlow<Credential?> = _crossHostFor.asStateFlow()

    init {
        // Seed the search field with the form's host the first time we enter
        // search mode. Idempotent - re-creating the VM after process death
        // sees the persisted non-empty query and skips the seed.
        if (startInSearch && searchQuery.value.isEmpty()) {
            (initial as? InitialState.Ready)?.parsed?.let { p ->
                savedState[KEY_SEARCH_QUERY] = p.webDomain ?: p.packageName
            }
        }

        // Lifecycle coupling: when the vault locks (auto-lock fires, user
        // backgrounds long enough, etc.) we MUST drop the decrypted snapshot
        // and any in-flight search state. The query is preserved across
        // re-unlock so the user doesn't have to retype.
        authRepository.isUnlocked()
            .distinctUntilChanged()
            .onEach { unlocked ->
                if (!unlocked) {
                    _snapshot.value = null
                    _matches.value = MatchState.Idle
                    _searchResults.value = SearchState.Idle
                    _crossHostFor.value = null
                }
            }
            .launchIn(viewModelScope)

        // When the category-filter preference is disabled, any retained tag
        // selection is meaningless (there's no chip row to clear it from).
        // Drop it so re-enabling the pref starts fresh.
        appPreferences.isAutofillCategoryFilterEnabled()
            .distinctUntilChanged()
            .onEach { enabled ->
                if (!enabled && savedState.get<String?>(KEY_SELECTED_TAG) != null) {
                    savedState[KEY_SELECTED_TAG] = null
                }
            }
            .launchIn(viewModelScope)

        // Search pipeline: cross-product the snapshot with the debounced
        // query and the tag selection. Tag changes apply immediately - they
        // are discrete user taps, not stream-typed keystrokes. Query keeps
        // the 150ms debounce so rapid typing doesn't thrash the filter.
        val parsed = (initial as? InitialState.Ready)?.parsed
        combine(
            _snapshot,
            searchQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
            // selectedTag is a StateFlow - already distinct by contract.
            selectedTag,
        ) { snap, q, tag ->
            if (snap == null) SearchState.Idle
            else SearchState.Loaded(filterSnapshot(snap, q, parsed, tag))
        }
            .onEach { _searchResults.value = it }
            .launchIn(viewModelScope)
    }

    /** Idempotently load exact-host matches once the vault is observed unlocked. */
    fun loadMatches() {
        val ready = initial as? InitialState.Ready ?: return
        if (_matches.value is MatchState.Loaded) return
        _matches.value = MatchState.Loading
        viewModelScope.launch {
            val result = runCatching { packageMatcher.findMatches(ready.parsed) }
            _matches.value = MatchState.Loaded(result.getOrNull().orEmpty())
        }
    }

    /**
     * Idempotently load the decrypted vault snapshot for search. Called once
     * after the vault is observed unlocked. The snapshot is also cleared on
     * relock so a subsequent re-unlock re-fetches.
     */
    fun loadSnapshot() {
        if (_snapshot.value != null) return
        viewModelScope.launch {
            val r = runCatching { credentialRepository.getAllCredentials() }.getOrNull()
            val list = when (r) {
                is AppResult.Success -> r.data
                else -> emptyList()
            }
            _snapshot.value = list
        }
    }

    fun onSearchQueryChanged(q: String) {
        savedState[KEY_SEARCH_QUERY] = q
    }

    /**
     * Sets the active tag filter, or clears it (`null` ↔ "All"). The combine
     * pipeline reacts immediately - tag changes are not debounced.
     */
    fun onTagSelected(tag: String?) {
        savedState[KEY_SELECTED_TAG] = tag
    }

    /**
     * Called when the user taps a credential - either from the exact-host
     * picker or the search results. Returns `true` if the activity should
     * complete the fill immediately, `false` if a cross-host confirmation
     * must be shown first. The cross-host candidate is held in
     * [crossHostFor]; the activity routes to a confirmation pane that calls
     * [confirmCrossHost] or [cancelCrossHost].
     */
    fun resolveCandidate(credential: Credential, fromExactMatchList: Boolean): Boolean {
        val parsed = (initial as? InitialState.Ready)?.parsed ?: return true
        if (fromExactMatchList) return true
        if (isHostMatch(credential, parsed)) return true
        _crossHostFor.value = credential
        return false
    }

    fun confirmCrossHost(): Credential? {
        val c = _crossHostFor.value
        _crossHostFor.value = null
        return c
    }

    fun cancelCrossHost() {
        _crossHostFor.value = null
    }

    /**
     * Whether [credential]'s stored host matches the form's webDomain. For
     * native-app fills (no webDomain) every credential is considered
     * cross-host - there's no safe way to assert that a saved web credential
     * "belongs" to an arbitrary native app, so the confirmation step always
     * runs.
     */
    private fun isHostMatch(credential: Credential, parsed: ParsedFields): Boolean {
        val host = parsed.webDomain ?: return false
        return HostExtractor.hostOf(credential.url) == host
    }

    private fun filterSnapshot(
        snap: List<Credential>,
        rawQuery: String,
        parsed: ParsedFields?,
        selectedTag: String?,
    ): List<Credential> {
        val q = rawQuery.trim().lowercase()
        val tagPredicate: (Credential) -> Boolean = { c ->
            selectedTag == null || selectedTag in c.tags
        }
        if (q.isEmpty()) {
            // Empty query: behaviour depends on whether the user asserted a
            // tag filter. With "All" we show only a small recent-items
            // starter (limits the social-engineering surface). With a tag
            // selected the user has positively narrowed the set - show
            // everything in it, alphabetical, no preview cap.
            val active = snap.asSequence()
                .filter { it.deletedAt == null }
                .filter(tagPredicate)
            return if (selectedTag == null) {
                active.sortedByDescending { it.updatedAt }
                    .take(EMPTY_QUERY_PREVIEW)
                    .toList()
            } else {
                active.sortedBy { it.title.lowercase() }.toList()
            }
        }
        val host = parsed?.webDomain
        val matchedHost = mutableListOf<Credential>()
        val matchedSubstring = mutableListOf<Credential>()
        val matchedTitleOrUser = mutableListOf<Credential>()
        snap.forEach { c ->
            if (c.deletedAt != null) return@forEach
            if (!tagPredicate(c)) return@forEach
            val credHost = HostExtractor.hostOf(c.url)
            val hostExact = host != null && credHost == host
            val hostContains = credHost != null && credHost.contains(q)
            val titleOrUserHit = c.title.contains(q, ignoreCase = true) ||
                c.username.contains(q, ignoreCase = true)
            when {
                hostExact -> matchedHost += c
                hostContains -> matchedSubstring += c
                titleOrUserHit -> matchedTitleOrUser += c
            }
        }
        val byTitle = compareBy<Credential> { it.title.lowercase() }
        return (matchedHost.sortedWith(byTitle) +
            matchedSubstring.sortedWith(byTitle) +
            matchedTitleOrUser.sortedWith(byTitle))
            .take(SEARCH_LIMIT)
    }

    private companion object {
        const val KEY_SEARCH_QUERY = "autofill_search_query"
        const val KEY_SELECTED_TAG = "autofill_selected_tag"
        const val SEARCH_DEBOUNCE_MS = 150L
        const val SEARCH_LIMIT = 50
        const val EMPTY_QUERY_PREVIEW = 8
    }
}
