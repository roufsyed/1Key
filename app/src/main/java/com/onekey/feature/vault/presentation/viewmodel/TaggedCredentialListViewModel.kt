package com.onekey.feature.vault.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.usecase.GetPagedCredentialsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class TaggedCredentialListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPagedCredentials: GetPagedCredentialsUseCase,
) : ViewModel() {

    val tagName: String = savedStateHandle.get<String>("tagName") ?: ""

    val credentials: StateFlow<PagingData<Credential>> =
        getPagedCredentials("", tagName)
            .cachedIn(viewModelScope)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())
}
