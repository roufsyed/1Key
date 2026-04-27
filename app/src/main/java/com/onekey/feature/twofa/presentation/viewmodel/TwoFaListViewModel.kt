package com.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.SaveCredentialUseCase
import com.onekey.feature.twofa.domain.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
) {
    // True when the credential contains data beyond the TOTP secret itself.
    // Deleting from the 2FA screen should only clear totpSecret in this case.
    val isLinkedCredential: Boolean =
        credential.password.isNotEmpty()
            || credential.notes.isNotEmpty()
            || credential.url.isNotEmpty()
            || credential.customFields.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TwoFaListViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val totpGenerator: TotpGenerator,
    private val deleteCredential: DeleteCredentialUseCase,
    private val saveCredential: SaveCredentialUseCase,
    appPrefs: AppPreferencesRepository,
) : ViewModel() {

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // HMAC-SHA1 work for every TOTP row, recomputed every second. flowOn keeps the
    // ticking loop and code generation off the main thread.
    val entries: StateFlow<List<TotpEntry>?> = credentialRepository.observeWithTotp()
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
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun removeTotp(entry: TotpEntry) {
        viewModelScope.launch {
            if (entry.isLinkedCredential) {
                saveCredential(entry.credential.copy(totpSecret = null))
            } else {
                deleteCredential(entry.credential.id)
            }
        }
    }
}
