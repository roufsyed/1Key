package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.TagWithCount
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val credentialRepository: CredentialRepository,
) : ViewModel() {

    val tagCounts: StateFlow<List<TagWithCount>> = tagRepository.observeTags()
        .flatMapLatest { tags ->
            if (tags.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    tags.map { tag ->
                        credentialRepository.observeCountForTag(tag.name)
                            .map { count -> TagWithCount(tag, count) }
                    }
                ) { array -> array.toList() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = credentialRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val favoriteCount: StateFlow<Int> = credentialRepository.observeFavoriteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
