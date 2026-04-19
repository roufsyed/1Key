package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.GetPagedCredentialsUseCase
import com.onekey.feature.vault.presentation.screen.TAG_ALL
import com.onekey.feature.vault.presentation.screen.TAG_FAVORITES
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CredentialListEvent {
    data class DeleteError(val count: Int) : CredentialListEvent()
}

@HiltViewModel
class TaggedCredentialListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPagedCredentials: GetPagedCredentialsUseCase,
    private val credentialRepository: CredentialRepository,
    private val deleteCredential: DeleteCredentialUseCase,
) : ViewModel() {

    private val rawTag: String = savedStateHandle.get<String>("tagName") ?: ""

    val displayName: String = when (rawTag) {
        TAG_ALL       -> "All Items"
        TAG_FAVORITES -> "Favorites"
        else          -> rawTag
    }

    val credentials: StateFlow<PagingData<Credential>> = when (rawTag) {
        TAG_FAVORITES ->
            credentialRepository.observeFavoritesPaged()
                .cachedIn(viewModelScope)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

        else -> {
            val filterTag = if (rawTag == TAG_ALL) "" else rawTag
            getPagedCredentials("", filterTag)
                .cachedIn(viewModelScope)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())
        }
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
