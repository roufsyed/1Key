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
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaggedCredentialListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val deleteCredential: DeleteCredentialUseCase,
    private val appPrefs: AppPreferencesRepository,
) : ViewModel() {

    private val rawTag: String = savedStateHandle.get<String>("tagName") ?: ""

    val displayName: String = when (rawTag) {
        TAG_ALL       -> "All Items"
        TAG_FAVORITES -> "Favorites"
        else          -> rawTag
    }

    val sortOrder: StateFlow<CredentialSortOrder> = appPrefs.getCredentialSortOrder()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CredentialSortOrder.NEWEST_FIRST)

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _event = MutableSharedFlow<CredentialListEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<CredentialListEvent> = _event.asSharedFlow()

    fun toggleSelection(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

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
}

internal fun buildLetterIndex(titles: List<String>): Map<Char, Int> {
    val index = mutableMapOf<Char, Int>()
    titles.forEachIndexed { i, title ->
        val ch = title.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
        if (ch !in index) index[ch] = i
    }
    return index
}
