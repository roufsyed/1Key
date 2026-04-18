package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.GetPagedCredentialsUseCase
import com.onekey.feature.vault.presentation.screen.TAG_ALL
import com.onekey.feature.vault.presentation.screen.TAG_FAVORITES
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class TaggedCredentialListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPagedCredentials: GetPagedCredentialsUseCase,
    private val credentialRepository: CredentialRepository,
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
            // TAG_ALL uses filterTag = "" which matches all credentials.
            val filterTag = if (rawTag == TAG_ALL) "" else rawTag
            getPagedCredentials("", filterTag)
                .cachedIn(viewModelScope)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())
        }
    }
}
