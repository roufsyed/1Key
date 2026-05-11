package com.onekey.feature.autofill.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.Credential
import com.onekey.feature.autofill.domain.PackageMatcher
import com.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [AutofillUnlockActivity]. Responsible for:
 *
 *  - Extracting the [ParsedFields] argument from [SavedStateHandle], surfaced
 *    as a sealed [InitialState] so the activity can finish gracefully if the
 *    extra is missing rather than crash inside the ViewModel constructor.
 *
 *  - Loading credential matches in the background once the vault is
 *    unlocked, exposing them as a [StateFlow] so the activity can render
 *    progressive states (loading -> matches | empty).
 *
 *  - Tracking a `pendingComplete` flag that's persisted into the saved-state
 *    bundle so a process-death-mid-unlock can auto-resolve once the activity
 *    is recreated with the vault already unlocked.
 */
@HiltViewModel
class AutofillUnlockViewModel @Inject constructor(
    private val packageMatcher: PackageMatcher,
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

    private val _matches = MutableStateFlow<MatchState>(MatchState.Idle)
    val matches: StateFlow<MatchState> = _matches.asStateFlow()

    /**
     * `pendingComplete` survives process death via SavedStateHandle so an
     * activity recreated mid-unlock can detect "I had a fill request in
     * flight" and auto-complete once the vault is observed as unlocked.
     */
    var pendingComplete: Boolean
        get() = savedState.get<Boolean>(KEY_PENDING_COMPLETE) ?: false
        set(value) { savedState[KEY_PENDING_COMPLETE] = value }

    fun loadMatches() {
        val ready = initial as? InitialState.Ready ?: return
        if (_matches.value is MatchState.Loaded) return
        _matches.value = MatchState.Loading
        viewModelScope.launch {
            val result = runCatching { packageMatcher.findMatches(ready.parsed) }
            _matches.value = MatchState.Loaded(result.getOrNull().orEmpty())
        }
    }

    sealed class MatchState {
        data object Idle : MatchState()
        data object Loading : MatchState()
        data class Loaded(val credentials: List<Credential>) : MatchState()
    }

    private companion object {
        const val KEY_PENDING_COMPLETE = "autofill_pending_complete"
    }
}
