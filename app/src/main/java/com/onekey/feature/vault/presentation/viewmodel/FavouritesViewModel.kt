package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
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
    private val appPrefs: AppPreferencesRepository,
) : ViewModel() {

    val sortOrder: StateFlow<CredentialSortOrder> = appPrefs.getCredentialSortOrder()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CredentialSortOrder.NEWEST_FIRST)

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
