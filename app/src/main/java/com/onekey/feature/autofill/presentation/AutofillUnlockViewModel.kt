package com.onekey.feature.autofill.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.feature.autofill.domain.HostExtractor
import com.onekey.feature.autofill.domain.PackageMatcher
import com.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
 *    and expensive — see `CredentialRepositoryImpl.toDomain`). Re-locking the
 *    vault clears the snapshot, the search results, and the matches; a
 *    subsequent re-unlock re-fetches.
 *
 *  - Surfaces a `crossHostFor: StateFlow<Credential?>` so the activity can
 *    render a confirmation pane before delivering a Dataset for a credential
 *    whose stored host does not match the form's [ParsedFields.webDomain]
 *    (or any credential when the form is a native app without a webDomain).
 *    The exact-host path A policy is preserved — the search escape hatch
 *    surfaces the user's explicit cross-origin intent before any plaintext
 *    leaves 1Key's UID.
 *
 *  - Persists `pendingComplete`, `searchQuery`, and `startInSearch` into
 *    [SavedStateHandle] so process-death recovery preserves user input.
 *
 * Threading invariant — **only** call the public `unlockWith*` methods on
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
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** Sealed initial state — keeps the missing-extra path off the crash rails. */
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

    /**
     * `pendingComplete` survives process death via SavedStateHandle. Kept for
     * downstream observers; today no auto-pick path consumes it.
     */
    var pendingComplete: Boolean
        get() = savedState.get<Boolean>(KEY_PENDING_COMPLETE) ?: false
        set(value) { savedState[KEY_PENDING_COMPLETE] = value }

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
        // search mode. Idempotent — re-creating the VM after process death
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

        // Search pipeline: cross-product the snapshot with the debounced
        // query. Skips when locked (snapshot is null). 150ms is the same feel
        // as the vault search.
        val parsed = (initial as? InitialState.Ready)?.parsed
        combine(
            _snapshot,
            searchQuery.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(),
        ) { snap, q ->
            if (snap == null) SearchState.Idle
            else SearchState.Loaded(filterSnapshot(snap, q, parsed))
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
     * Called when the user taps a credential — either from the exact-host
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
     * cross-host — there's no safe way to assert that a saved web credential
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
    ): List<Credential> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) {
            // Empty query: a small "starter" list of recent items so the user
            // sees something useful. We don't expose the whole vault by
            // default — that would be the social-engineering surface.
            return snap
                .filter { it.deletedAt == null }
                .sortedByDescending { it.updatedAt }
                .take(EMPTY_QUERY_PREVIEW)
        }
        val host = parsed?.webDomain
        val matchedHost = mutableListOf<Credential>()
        val matchedSubstring = mutableListOf<Credential>()
        val matchedTitleOrUser = mutableListOf<Credential>()
        snap.forEach { c ->
            if (c.deletedAt != null) return@forEach
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
        const val KEY_PENDING_COMPLETE = "autofill_pending_complete"
        const val KEY_SEARCH_QUERY = "autofill_search_query"
        const val SEARCH_DEBOUNCE_MS = 150L
        const val SEARCH_LIMIT = 50
        const val EMPTY_QUERY_PREVIEW = 8
    }
}
