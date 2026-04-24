package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.TagWithCount
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.GetPagedCredentialsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val credentialRepository: CredentialRepository,
    private val getPagedCredentials: GetPagedCredentialsUseCase,
) : ViewModel() {

    val tagCounts: StateFlow<List<TagWithCount>> = tagRepository.observeTagsWithCounts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalCount: StateFlow<Int> = credentialRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val favoriteCount: StateFlow<Int> = credentialRepository.observeFavoriteCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Empty query → empty results so the composable can show a "type to search" hint.
    val searchResults: Flow<PagingData<Credential>> = _searchQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(PagingData.empty())
            else getPagedCredentials(q)
        }
        .cachedIn(viewModelScope)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
