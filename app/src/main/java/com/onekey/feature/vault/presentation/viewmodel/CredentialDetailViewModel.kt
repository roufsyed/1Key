package com.onekey.feature.vault.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.repository.CredentialHistoryRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.repository.TagRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.GetCredentialUseCase
import com.onekey.core.domain.usecase.SaveCredentialUseCase
import com.onekey.core.security.SecureClipboardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
sealed class CredentialDetailUiState {
    data object Loading : CredentialDetailUiState()
    data class Success(val credential: Credential, val isEditing: Boolean = false) : CredentialDetailUiState()
    data object Saved : CredentialDetailUiState()
    data object Deleted : CredentialDetailUiState()
    data class Error(val message: String) : CredentialDetailUiState()
}

@HiltViewModel
class CredentialDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCredential: GetCredentialUseCase,
    private val saveCredential: SaveCredentialUseCase,
    private val deleteCredential: DeleteCredentialUseCase,
    private val credentialRepository: CredentialRepository,
    private val historyRepository: CredentialHistoryRepository,
    private val tagRepository: TagRepository,
    private val clipboard: SecureClipboardManager,
) : ViewModel() {

    private val credentialId: String? = savedStateHandle.get<String>("credentialId")
        ?.takeIf { it != "new" }

    private val initialTag: String = savedStateHandle.get<String>("initialTag") ?: ""

    private val _uiState = MutableStateFlow<CredentialDetailUiState>(CredentialDetailUiState.Loading)
    val uiState: StateFlow<CredentialDetailUiState> = _uiState.asStateFlow()

    val availableTags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: Flow<List<CredentialHistoryEntry>> = credentialId
        ?.let { id -> historyRepository.observeHistory(id) }
        ?: flowOf(emptyList())

    init {
        if (credentialId == null) {
            _uiState.value = CredentialDetailUiState.Success(emptyCredential(), isEditing = true)
        } else {
            viewModelScope.launch {
                getCredential(credentialId)
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { credential ->
                        _uiState.update { state ->
                            // Preserve terminal states — DB updates must not override Saved/Deleted.
                            when (state) {
                                is CredentialDetailUiState.Loading -> CredentialDetailUiState.Success(credential)
                                is CredentialDetailUiState.Success -> state.copy(credential = credential)
                                else -> state
                            }
                        }
                    }
            }
        }
    }

    fun startEditing() {
        _uiState.update { if (it is CredentialDetailUiState.Success) it.copy(isEditing = true) else it }
    }

    fun save(credential: Credential) {
        viewModelScope.launch {
            val existing = (_uiState.value as? CredentialDetailUiState.Success)?.credential
            if (existing != null && existing.id.isNotBlank()) {
                historyRepository.snapshotCredential(existing)
            }
            when (val result = saveCredential(credential)) {
                is AppResult.Success -> _uiState.value = CredentialDetailUiState.Saved
                is AppResult.Error -> _uiState.value = CredentialDetailUiState.Error(result.message ?: "Save failed")
            }
        }
    }

    fun delete() {
        val id = credentialId ?: return
        viewModelScope.launch {
            when (val result = deleteCredential(id)) {
                is AppResult.Success -> _uiState.value = CredentialDetailUiState.Deleted
                is AppResult.Error -> _uiState.value = CredentialDetailUiState.Error(result.message ?: "Delete failed")
            }
        }
    }

    fun toggleFavorite() {
        val state = _uiState.value as? CredentialDetailUiState.Success ?: return
        val id = credentialId ?: return
        viewModelScope.launch {
            val result = credentialRepository.toggleFavorite(id, !state.credential.isFavorite)
            if (result is AppResult.Error) {
                _uiState.value = CredentialDetailUiState.Error(result.message ?: "Failed to update favourite")
            }
        }
    }

    fun addTag(name: String) {
        viewModelScope.launch {
            tagRepository.addTag(Tag(name = name, color = 0xFF6200EE.toInt(), icon = ""))
        }
    }

    fun copyPassword(password: String) = clipboard.copySecure("Password", password)
    fun copyUsername(username: String) = clipboard.copySecure("Username", username)

    private fun emptyCredential() = Credential(
        id = "", title = "", username = "", password = "", url = "",
        notes = "", totpSecret = null,
        tags = if (initialTag.isNotEmpty()) listOf(initialTag) else emptyList(),
        customFields = emptyList(), createdAt = 0L, updatedAt = 0L,
    )
}
