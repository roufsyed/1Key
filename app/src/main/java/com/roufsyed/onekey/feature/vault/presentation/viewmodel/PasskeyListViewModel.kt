package com.roufsyed.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.data.snapshot.SnapshotState
import com.roufsyed.onekey.core.di.DefaultDispatcher
import com.roufsyed.onekey.core.di.SnapshotStateFlow
import com.roufsyed.onekey.core.domain.model.PasskeySummary
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.PasskeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Passkeys tab: every passkey whose vault entry is active, sorted by site then
 * account, each labelled with the entry that owns it.
 *
 * Rows are lean [PasskeySummary] projections from [PasskeyRepository] (no key
 * material ever enters this ViewModel). The owning entry's title comes from the
 * shared vault snapshot; while the snapshot is loading or bypassed (vault above
 * the cap) the title falls back to the site name, so the tab never waits on a
 * full decrypt. The repository emits an empty list while the vault is locked,
 * which only shows for an instant because the app routes to the lock screen.
 */
@HiltViewModel
class PasskeyListViewModel @Inject constructor(
    private val passkeyRepository: PasskeyRepository,
    appPrefs: AppPreferencesRepository,
    @SnapshotStateFlow private val snapshotState: StateFlow<@JvmSuppressWildcards SnapshotState>,
    @DefaultDispatcher private val filterDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class Row(val passkey: PasskeySummary, val credentialTitle: String)

    sealed interface ListState {
        data object Loading : ListState
        data class Loaded(val rows: List<Row>) : ListState
    }

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val listState: StateFlow<ListState> = combine(passkeyRepository.observeAllActive(), snapshotState) { passkeys, snapshot ->
        val titles = (snapshot as? SnapshotState.Loaded)?.credentials?.associate { it.id to it.title }.orEmpty()
        ListState.Loaded(
            passkeys
                .sortedWith(
                    compareBy<PasskeySummary> { it.rpName.ifBlank { it.rpId }.lowercase() }
                        .thenBy { it.userName.lowercase() }
                        .thenBy { it.createdAt },
                )
                .map { Row(it, titles[it.credentialId] ?: it.rpName.ifBlank { it.rpId }) },
        )
    }
        .flowOn(filterDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ListState.Loading)

    /** Removes one passkey. The site keeps its public key; the user signs in another way and registers again. */
    fun deletePasskey(id: String) {
        viewModelScope.launch { passkeyRepository.deleteById(id) }
    }
}
