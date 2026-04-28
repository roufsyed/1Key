package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.HardDeleteCredentialUseCase
import com.onekey.feature.vault.presentation.screen.TAG_ALL
import com.onekey.feature.vault.presentation.screen.TAG_FAVORITES
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CredentialListEvent {
    data class DeleteError(val count: Int) : CredentialListEvent()
    data class FavouriteUpdated(val count: Int, val markedAs: Boolean) : CredentialListEvent()
    data class FavouriteError(val count: Int) : CredentialListEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaggedCredentialListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val deleteCredential: DeleteCredentialUseCase,
    private val hardDeleteCredential: HardDeleteCredentialUseCase,
    private val appPrefs: AppPreferencesRepository,
) : ViewModel() {

    val rawTag: String = savedStateHandle.get<String>("tagName") ?: ""

    val displayName: String = when (rawTag) {
        TAG_ALL       -> "All Items"
        TAG_FAVORITES -> "Favorites"
        else          -> rawTag
    }

    val sortOrder: StateFlow<CredentialSortOrder> = appPrefs.getCredentialSortOrder()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CredentialSortOrder.NEWEST_FIRST)

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val credentials: StateFlow<List<Credential>?> = sortOrder
        .flatMapLatest { order ->
            when (rawTag) {
                TAG_FAVORITES -> credentialRepository.observeFavoritesSorted(order)
                else -> {
                    val filterTag = if (rawTag == TAG_ALL) "" else rawTag
                    credentialRepository.observeCredentials("", filterTag, order)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val letterIndex: StateFlow<Map<Char, Int>> = combine(credentials, sortOrder) { creds, order ->
        if (order != CredentialSortOrder.ALPHABETICAL || creds == null) emptyMap()
        else buildLetterIndex(creds.map { it.title })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setSortOrder(order: CredentialSortOrder) {
        viewModelScope.launch { appPrefs.setCredentialSortOrder(order) }
    }

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = _selectedIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Drives the selection-mode favourite icon: when all selected are already
    // favourited the action becomes "remove from favourites", otherwise it adds.
    val selectedAreAllFavourite: StateFlow<Boolean> =
        combine(_selectedIds, credentials) { ids, list ->
            if (ids.isEmpty() || list.isNullOrEmpty()) false
            else {
                val byId = list.associateBy { it.id }
                ids.all { id -> byId[id]?.isFavorite == true }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

internal fun buildLetterIndex(titles: List<String>): Map<Char, Int> {
    val index = mutableMapOf<Char, Int>()
    titles.forEachIndexed { i, title ->
        val ch = title.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
        if (ch !in index) index[ch] = i
    }
    return index
}
