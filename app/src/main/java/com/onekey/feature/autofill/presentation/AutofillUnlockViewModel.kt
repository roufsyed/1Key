package com.onekey.feature.autofill.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.data.snapshot.SnapshotCredential
import com.onekey.core.data.snapshot.SnapshotState
import com.onekey.core.di.DefaultDispatcher
import com.onekey.core.di.SnapshotStateFlow
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.TagWithCount
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.feature.autofill.domain.HostExtractor
import com.onekey.feature.autofill.domain.PackageMatcher
import com.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
 *  - **Search**: derives results from the shared [com.onekey.core.data.snapshot.VaultSnapshotStore]
 *    via [SnapshotStateFlow]. The store keeps a hot, lean-projection decrypted
 *    list while the vault is unlocked; the first keystroke after unlock filters
 *    an already-warm list instead of triggering a per-screen decrypt-all.
 *    [SearchState] distinguishes Locked / Loading / Loaded(empty) / Bypassed so
 *    the picker never flashes "no results" during the transient first-decrypt
 *    window, and surfaces a distinct message for vaults larger than
 *    [com.onekey.core.data.snapshot.VaultSnapshotStore.SNAPSHOT_CAP] rather than
 *    silently showing zero. Search results are [SnapshotCredential]:
 *    title/username/url/tags only. Password, notes, OTP secret, and custom
 *    fields are fetched by id at Dataset-delivery time in the activity, never
 *    held in VM state.
 *
 *  - Surfaces a `crossHostFor: StateFlow<SnapshotCredential?>` so the activity
 *    can render a confirmation pane before delivering a Dataset for a credential
 *    whose stored host does not match the form's [ParsedFields.webDomain] (or
 *    any credential when the form is a native app without a webDomain). The
 *    exact-host path A policy is preserved: the search escape hatch surfaces
 *    the user's explicit cross-origin intent before any plaintext leaves
 *    1Key's UID.
 *
 *  - Persists `searchQuery`, `selectedTag`, and `startInSearch` into
 *    [SavedStateHandle] so process-death recovery preserves user input.
 *
 * **Lock-clear (asymmetric, security-relevant)**: the *snapshot* path is
 * cleared synchronously by [com.onekey.core.data.snapshot.VaultSnapshotStore]'s
 * lock hook before [com.onekey.core.security.VaultKeyHolder.lock] returns, so
 * `searchResults` collapses to Idle on its next combine emission (microseconds,
 * Eagerly subscribed). The *matches* path holds full [Credential] plaintext
 * (password / notes / OTP secret) from [PackageMatcher] and is cleared
 * **asynchronously** by this VM's own [authRepository] observer. Same timing
 * as pre-PR2; a future PR converges [PackageMatcher] onto the snapshot to
 * close this asymmetry. The same auth observer also clears `_crossHostFor`
 * (lean projection, no passwords; microsecond-scale async clear of
 * title/username/url PII).
 *
 * Threading invariant: **only** call the public `unlockWith*` methods on
 * `AuthViewModel` from this surface. Never the `verifyMasterPasswordForPinChange`
 * or `verifyCurrentPin` paths: those use the in-vault [com.onekey.core.security.AuthAttemptsStore]
 * (session-scoped, scoped to a different threat model) and would route a
 * regular unlock failure through the wrong counter.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class AutofillUnlockViewModel @Inject constructor(
    private val packageMatcher: PackageMatcher,
    private val authRepository: AuthRepository,
    @SnapshotStateFlow private val snapshotState: StateFlow<@JvmSuppressWildcards SnapshotState>,
    private val tagRepository: TagRepository,
    private val appPreferences: AppPreferencesRepository,
    @DefaultDispatcher private val filterDispatcher: CoroutineDispatcher,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** Sealed initial state: keeps the missing-extra path off the crash rails. */
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

    /**
     * Sealed state for the search results pane.
     *
     *  - [Idle]     : query is blank, OR vault is locked. UI shows the
     *                 starter hint.
     *  - [Loading]  : snapshot is still completing its first decrypt pass
     *                 (or the cipher migrator is rewriting legacy rows). UI
     *                 shows a spinner; never "No credentials match".
     *  - [Loaded]   : filtered [SnapshotCredential] list ready. Empty list
     *                 plus a non-blank query renders the "No matches" empty
     *                 state.
     *  - [Bypassed] : vault size exceeds the snapshot cap. Fast in-memory
     *                 search is disabled; UI prompts the user to open the
     *                 main app.
     */
    sealed class SearchState {
        data object Idle : SearchState()
        data object Loading : SearchState()
        data class Loaded(val credentials: List<SnapshotCredential>) : SearchState()
        data object Bypassed : SearchState()
    }

    /** Live query backed by SavedStateHandle so it survives process death. */
    val searchQuery: StateFlow<String> =
        savedState.getStateFlow(KEY_SEARCH_QUERY, "")

    /**
     * Active tag filter, `null` for "All". SavedStateHandle-backed so a tag
     * the user picked survives process death exactly like the query.
     */
    val selectedTag: StateFlow<String?> =
        savedState.getStateFlow<String?>(KEY_SELECTED_TAG, null)

    /**
     * Tags + usage counts surfaced as filter chips in the search screen.
     * Empty when the category-filter pref is off: the activity uses that as
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

    /**
     * Cross-host pending candidate. Set by [resolveSearchCandidate] when the
     * user picks a search result whose stored host does not match the form.
     * Lean projection: title/username/url/tags only. The Dataset-delivery
     * fetch happens in the activity at confirm time via
     * `credentialRepository.getCredential(id)`, so the password never enters
     * VM state.
     */
    private val _crossHostFor = MutableStateFlow<SnapshotCredential?>(null)
    val crossHostFor: StateFlow<SnapshotCredential?> = _crossHostFor.asStateFlow()

    init {
        // Seed the search field with the form's host the first time we enter
        // search mode. Idempotent: re-creating the VM after process death
        // sees the persisted non-empty query and skips the seed.
        if (startInSearch && searchQuery.value.isEmpty()) {
            (initial as? InitialState.Ready)?.parsed?.let { p ->
                savedState[KEY_SEARCH_QUERY] = p.webDomain ?: p.packageName
            }
        }

        // VM-local plaintext clear on lock. The shared snapshot is cleared
        // synchronously by VaultSnapshotStore's hook BEFORE keyHolder.lock()
        // returns; that path handles searchResults via the combine below
        // (snapshot transitions to Locked, combine yields SearchState.Idle).
        // Here we cover the two surfaces the snapshot store does NOT own:
        //   * `_matches` holds full Credential plaintext from PackageMatcher
        //     (password, notes, OTP secret) until that VM is converged onto
        //     the snapshot in a follow-up PR.
        //   * `_crossHostFor` holds a SnapshotCredential PII reference whose
        //     Compose subscriber may otherwise retain it past lock.
        // Both clears are async (one viewModelScope dispatcher hop). The
        // asymmetry vs the snapshot's sync clear is documented at class level.
        authRepository.isUnlocked()
            .distinctUntilChanged()
            .onEach { unlocked ->
                if (!unlocked) {
                    _matches.value = MatchState.Idle
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
    }

    /**
     * Derived search results: a three-way combine of the shared snapshot
     * state, the debounced query, and the discrete tag selection.
     *
     * Declared after [init] so the SavedStateHandle seed runs first; the
     * first debounced emission will then see the seeded query rather than
     * the empty string.
     *
     * Why `SharingStarted.Eagerly` (not WhileSubscribed): the autofill
     * activity is short-lived (singleInstance + finish on onNewIntent).
     * WhileSubscribed(N) would (a) flash empty results when the cross-host
     * pane re-mounts SearchSurface after N elapses, and (b) potentially
     * retain the last `Loaded(...)` reference past a lock if the collector
     * pauses, opening a PII residency gap. Eagerly matches the prior
     * `.launchIn(viewModelScope)` semantics on a hot StateFlow.
     *
     * Why `.flowOn(filterDispatcher)` before `.stateIn`: at vault sizes
     * approaching SNAPSHOT_CAP, [filterSnapshot] parses thousands of URIs
     * per keystroke via [HostExtractor.hostOf]. Keep that off Main. The
     * dispatcher is injected (`@DefaultDispatcher`) so unit tests can
     * substitute a test dispatcher and `runTest`-virtual time propagates
     * across the `flowOn` boundary.
     */
    val searchResults: StateFlow<SearchState> = combine(
        snapshotState,
        searchQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
        selectedTag,
    ) { snapshot, q, tag ->
        when (snapshot) {
            is SnapshotState.Locked -> SearchState.Idle
            is SnapshotState.Loading -> SearchState.Loading
            is SnapshotState.Bypassed -> SearchState.Bypassed
            is SnapshotState.Loaded -> {
                val parsed = (initial as? InitialState.Ready)?.parsed
                SearchState.Loaded(filterSnapshot(snapshot.credentials, q, parsed, tag))
            }
        }
    }
        .flowOn(filterDispatcher)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchState.Idle)

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

    fun onSearchQueryChanged(q: String) {
        savedState[KEY_SEARCH_QUERY] = q
    }

    /**
     * Sets the active tag filter, or clears it (`null` is "All"). The combine
     * pipeline reacts immediately: tag changes are not debounced.
     */
    fun onTagSelected(tag: String?) {
        savedState[KEY_SELECTED_TAG] = tag
    }

    /**
     * Called when the user taps a credential in the search results list.
     * Returns `true` if the activity should fetch the full [Credential] and
     * deliver the Dataset immediately, `false` if the cross-host confirmation
     * pane must run first. The pending candidate is held in [crossHostFor].
     *
     * Exact-host picker taps do NOT go through this method: the activity
     * calls the fill callback directly because [PackageMatcher.findMatches]
     * has already enforced exact-host policy at match time.
     */
    fun resolveSearchCandidate(candidate: SnapshotCredential): Boolean {
        val parsed = (initial as? InitialState.Ready)?.parsed ?: return true
        if (isHostMatch(candidate.url, parsed)) return true
        _crossHostFor.value = candidate
        return false
    }

    /**
     * Read-without-clear: returns the staged candidate so the activity can
     * fetch the full [Credential] by id. The pending state intentionally
     * remains set so [CrossHostConfirmSurface] stays mounted (with its
     * `fetching` spinner) until the activity tears down on Dataset delivery.
     * Clearing here would unmount the pane immediately, briefly exposing
     * [SearchSurface] underneath while the fetch is in flight.
     */
    fun confirmCrossHost(): SnapshotCredential? = _crossHostFor.value

    fun cancelCrossHost() {
        _crossHostFor.value = null
    }

    /**
     * Whether [credentialUrl]'s host matches the form's webDomain. For
     * native-app fills (no webDomain) every credential is treated as
     * cross-host: there's no safe way to assert that a saved web credential
     * "belongs" to an arbitrary native app, so the confirmation step always
     * runs.
     */
    private fun isHostMatch(credentialUrl: String, parsed: ParsedFields): Boolean {
        val host = parsed.webDomain ?: return false
        return HostExtractor.hostOf(credentialUrl) == host
    }

    /**
     * Filter a snapshot slice on the active query + tag.
     *
     * The snapshot is active-only by [SnapshotState.Loaded] contract
     * ([com.onekey.core.data.snapshot.VaultSnapshotStore] applies
     * `deleted_at IS NULL` upstream), so no soft-delete filter is needed here.
     */
    private fun filterSnapshot(
        snap: List<SnapshotCredential>,
        rawQuery: String,
        parsed: ParsedFields?,
        selectedTag: String?,
    ): List<SnapshotCredential> {
        val q = rawQuery.trim().lowercase()
        val tagPredicate: (SnapshotCredential) -> Boolean = { c ->
            selectedTag == null || selectedTag in c.tags
        }
        if (q.isEmpty()) {
            // Empty query: behaviour depends on whether the user asserted a
            // tag filter. With "All" we show only a small recent-items
            // starter (limits the social-engineering surface). With a tag
            // selected the user has positively narrowed the set: show
            // everything in it, alphabetical, no preview cap.
            val active = snap.asSequence().filter(tagPredicate)
            return if (selectedTag == null) {
                active.sortedByDescending { it.updatedAt }
                    .take(EMPTY_QUERY_PREVIEW)
                    .toList()
            } else {
                active.sortedBy { it.title.lowercase() }.toList()
            }
        }
        val host = parsed?.webDomain
        val matchedHost = mutableListOf<SnapshotCredential>()
        val matchedSubstring = mutableListOf<SnapshotCredential>()
        val matchedTitleOrUser = mutableListOf<SnapshotCredential>()
        snap.forEach { c ->
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
        val byTitle = compareBy<SnapshotCredential> { it.title.lowercase() }
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
