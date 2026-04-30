package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.HardDeleteCredentialUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavouritesViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val deleteCredential: DeleteCredentialUseCase,
    private val hardDeleteCredential: HardDeleteCredentialUseCase,
    private val appPrefs: AppPreferencesRepository,
) : ViewModel() {

    val isRecycleBinEnabled: StateFlow<Boolean> = appPrefs.isRecycleBinEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sortOrder: StateFlow<CredentialSortOrder> = appPrefs.getCredentialSortOrder()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CredentialSortOrder.NEWEST_FIRST)

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // WhileSubscribed(5s) so the decrypted favourites don't stay hot in StateFlow.value
    // after the user navigates away. The 5s grace handles config-change subscription gaps
    // without restarting the upstream collection. Preference flows (sortOrder etc.) stay
    // Eagerly so the screen has values on first render.
    val credentials: StateFlow<List<Credential>?> = sortOrder
        .flatMapLatest { order -> credentialRepository.observeFavoritesSorted(order) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val letterIndex: StateFlow<Map<Char, Int>> = combine(credentials, sortOrder) { creds, order ->
        if (order != CredentialSortOrder.ALPHABETICAL || creds == null) emptyMap()
        else buildLetterIndex(creds.map { it.title })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setSortOrder(order: CredentialSortOrder) {
        viewModelScope.launch { appPrefs.setCredentialSortOrder(order) }
    }

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = _selectedIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // On the favourites tab every visible row is already favourited, so the action will
    // always default to "remove from favourites" — but the flag is computed the same way
    // for symmetry with TaggedCredentialListViewModel.
    val selectedAreAllFavourite: StateFlow<Boolean> =
        combine(_selectedIds, credentials) { ids, list ->
            if (ids.isEmpty() || list.isNullOrEmpty()) false
            else {
                val byId = list.associateBy { it.id }
                ids.all { id -> byId[id]?.isFavorite == true }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _event = MutableSharedFlow<CredentialListEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<CredentialListEvent> = _event.asSharedFlow()

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
}
