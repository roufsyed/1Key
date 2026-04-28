package com.onekey.feature.vault.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.EmptyRecycleBinUseCase
import com.onekey.core.domain.usecase.PurgeFromRecycleBinUseCase
import com.onekey.core.domain.usecase.RestoreCredentialUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class RecycleBinItem(
    val credential: Credential,
    /** Days until auto-purge. `null` when the user disabled auto-clear. */
    val daysUntilPurge: Int?,
)

@Immutable
sealed class RecycleBinEvent {
    data class Restored(val title: String) : RecycleBinEvent()
    data class Purged(val title: String) : RecycleBinEvent()
    data class Emptied(val count: Int) : RecycleBinEvent()
    data class Error(val message: String) : RecycleBinEvent()
}

@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    repository: CredentialRepository,
    appPrefs: AppPreferencesRepository,
    private val restoreCredential: RestoreCredentialUseCase,
    private val purgeFromBin: PurgeFromRecycleBinUseCase,
    private val emptyBin: EmptyRecycleBinUseCase,
) : ViewModel() {

    val retention: StateFlow<RecycleBinRetention> = appPrefs.getRecycleBinRetention()
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecycleBinRetention.DAYS_30)

    val items: StateFlow<List<RecycleBinItem>> = combine(
        repository.observeRecycleBin(),
        retention,
    ) { creds, ret ->
        creds.map { it.toBinItem(ret.millis) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _events = Channel<RecycleBinEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun restore(item: RecycleBinItem) {
        viewModelScope.launch {
            _isWorking.value = true
            when (val result = restoreCredential(item.credential.id)) {
                is AppResult.Success -> _events.send(RecycleBinEvent.Restored(item.credential.title))
                is AppResult.Error -> _events.send(RecycleBinEvent.Error(result.message ?: "Restore failed"))
            }
            _isWorking.value = false
        }
    }

    fun purge(item: RecycleBinItem) {
        viewModelScope.launch {
            _isWorking.value = true
            when (val result = purgeFromBin(item.credential.id)) {
                is AppResult.Success -> _events.send(RecycleBinEvent.Purged(item.credential.title))
                is AppResult.Error -> _events.send(RecycleBinEvent.Error(result.message ?: "Delete failed"))
            }
            _isWorking.value = false
        }
    }

    fun emptyAll() {
        viewModelScope.launch {
            _isWorking.value = true
            when (val result = emptyBin()) {
                is AppResult.Success -> _events.send(RecycleBinEvent.Emptied(result.data))
                is AppResult.Error -> _events.send(RecycleBinEvent.Error(result.message ?: "Empty failed"))
            }
            _isWorking.value = false
        }
    }

    private fun Credential.toBinItem(retentionMs: Long?): RecycleBinItem {
        if (retentionMs == null) return RecycleBinItem(credential = this, daysUntilPurge = null)
        val deleted = deletedAt ?: System.currentTimeMillis()
        val ageMs = System.currentTimeMillis() - deleted
        val remainingMs = (retentionMs - ageMs).coerceAtLeast(0L)
        val daysLeft = ((remainingMs + DAY_MS - 1) / DAY_MS).toInt()
        return RecycleBinItem(credential = this, daysUntilPurge = daysLeft)
    }

    companion object {
        private const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    }
}
