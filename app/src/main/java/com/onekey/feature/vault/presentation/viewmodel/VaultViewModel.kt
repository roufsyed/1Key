package com.onekey.feature.vault.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.GetPagedCredentialsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class VaultUiState(
    val searchQuery: String = "",
    val selectedTag: String = "",
    val tags: List<Tag> = emptyList(),
    val credentialCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val getPagedCredentials: GetPagedCredentialsUseCase,
    private val tagRepository: TagRepository,
    private val credentialRepository: CredentialRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedTag = MutableStateFlow("")

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    val credentials: StateFlow<PagingData<Credential>> =
        combine(
            searchQuery.debounce(300),
            selectedTag,
        ) { query, tag -> query to tag }
            .distinctUntilChanged()
            .flatMapLatest { (query, tag) -> getPagedCredentials(query, tag) }
            .cachedIn(viewModelScope)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

    val favorites: StateFlow<List<Credential>> = credentialRepository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        tagRepository.observeTags()
            .onEach { tags -> _uiState.update { it.copy(tags = tags) } }
            .launchIn(viewModelScope)

        combine(searchQuery, selectedTag) { q, t -> q to t }
            .onEach { (q, t) -> _uiState.update { it.copy(searchQuery = q, selectedTag = t) } }
            .launchIn(viewModelScope)
    }

    fun onSearchQueryChanged(query: String) { searchQuery.value = query }
    fun onTagSelected(tag: String) { selectedTag.value = if (selectedTag.value == tag) "" else tag }
    fun clearFilters() { searchQuery.value = ""; selectedTag.value = "" }
}
