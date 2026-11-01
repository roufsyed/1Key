package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.di.DefaultDispatcher
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Passkeys tab: every passkey whose vault entry is active, sorted by site then
 * account. Rows are lean [PasskeySummary] projections from [PasskeyRepository]
 * (no key material ever enters this ViewModel). The repository emits an empty
 * list while the vault is locked, which only shows for an instant because the
 * app routes to the lock screen. Opening and deleting happen in the owning
 * entry's detail screen, so this ViewModel has no actions.
 */
@HiltViewModel
class PasskeyListViewModel @Inject constructor(
    passkeyRepository: PasskeyRepository,
    appPrefs: AppPreferencesRepository,
    @DefaultDispatcher sortDispatcher: CoroutineDispatcher,
) : ViewModel() {

    sealed interface ListState {
        data object Loading : ListState
        data class Loaded(val passkeys: List<PasskeySummary>) : ListState
    }

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val listState: StateFlow<ListState> = passkeyRepository.observeAllActive()
        .map { passkeys ->
            ListState.Loaded(
                passkeys.sortedWith(
                    compareBy<PasskeySummary> { it.rpName.ifBlank { it.rpId }.lowercase() }
                        .thenBy { it.userName.lowercase() }
                        .thenBy { it.createdAt },
                ),
            )
        }
        .flowOn(sortDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ListState.Loading)
}
