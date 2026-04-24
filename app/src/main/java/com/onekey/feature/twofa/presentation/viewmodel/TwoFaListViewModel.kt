package com.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.feature.twofa.domain.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TotpEntry(
    val credential: Credential,
    val code: String,
    val remainingSeconds: Int,
    val progress: Float,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TwoFaListViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val totpGenerator: TotpGenerator,
    private val deleteCredential: DeleteCredentialUseCase,
) : ViewModel() {

    val entries: StateFlow<List<TotpEntry>> = credentialRepository.observeWithTotp()
        .transformLatest { credentials ->
            while (true) {
                emit(credentials.mapNotNull { cred ->
                    val secret = cred.totpSecret ?: return@mapNotNull null
                    runCatching { totpGenerator.generate(secret) }.getOrNull()?.let { result ->
                        TotpEntry(cred, result.code, result.remainingSeconds, result.progress)
                    }
                })
                delay(1_000L)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteEntry(id: String) {
        viewModelScope.launch { deleteCredential(id) }
    }
}
