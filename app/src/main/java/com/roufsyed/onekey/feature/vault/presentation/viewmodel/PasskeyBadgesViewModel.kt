package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Which vault entries hold at least one passkey, for the small key badge on
 * list rows. A separate ViewModel rather than a field on each list ViewModel
 * so the vault, Favourites and category lists share one subscription shape
 * without each growing a passkey dependency. Lean summaries only; no key
 * material passes through here.
 */
@HiltViewModel
class PasskeyBadgesViewModel @Inject constructor(
    passkeyRepository: PasskeyRepository,
) : ViewModel() {

    val credentialIdsWithPasskeys: StateFlow<Set<String>> = passkeyRepository.observeAllActive()
        .map { passkeys -> passkeys.mapTo(HashSet()) { it.credentialId } as Set<String> }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())
}
