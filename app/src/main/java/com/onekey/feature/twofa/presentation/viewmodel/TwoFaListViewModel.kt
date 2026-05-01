package com.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.OtpType
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.domain.usecase.DeleteCredentialUseCase
import com.onekey.core.domain.usecase.SaveCredentialUseCase
import com.onekey.core.security.SecureClipboardManager
import com.onekey.feature.twofa.domain.OtpGenerator
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
    // True when the credential carries data beyond the OTP enrolment itself.
    // Deleting from the 2FA screen should only clear otpParams in this case.
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
    private val otpGenerator: OtpGenerator,
    private val deleteCredential: DeleteCredentialUseCase,
    private val saveCredential: SaveCredentialUseCase,
    private val secureClipboard: SecureClipboardManager,
    appPrefs: AppPreferencesRepository,
) : ViewModel() {

    val hideTopBarOnScroll: StateFlow<Boolean> = appPrefs.isHideTopBarOnScroll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // HMAC-SHA1 work for every TOTP row, recomputed every second. flowOn keeps the
    // ticking loop and code generation off the main thread.
    // Per-second recompute loop for rotating OTP types (TOTP, STEAM). HOTP entries
    // are deliberately filtered out here as defence-in-depth — C4 splits them out at
    // the DAO layer (observeRotatingOtp / observeHotpEntries) so they never reach this
    // flow. Until that lands, the type-check below keeps a hand-edited HOTP entry from
    // accidentally rotating its code every second (which would advance the counter
    // visually without the persisted-counter increment, desyncing the user).
    val entries: StateFlow<List<TotpEntry>?> = credentialRepository.observeWithTotp()
        .transformLatest { credentials ->
            while (true) {
                emit(credentials.mapNotNull { cred ->
                    val params = cred.otpParams ?: return@mapNotNull null
                    if (params.type == OtpType.HOTP) return@mapNotNull null
                    runCatching { otpGenerator.generate(params) }.getOrNull()?.let { result ->
                        // Time-based variants always carry remainingSeconds / progress;
                        // the !! makes the invariant explicit at the boundary.
                        TotpEntry(cred, result.code, result.remainingSeconds!!, result.progress!!)
                    }
                })
                delay(TICK_MILLIS)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Routes through SecureClipboardManager so the 30s clear survives navigation. */
    fun copyCode(code: String) {
        secureClipboard.copySecure("2FA Code", code)
    }

    fun removeTotp(entry: TotpEntry) {
        viewModelScope.launch {
            if (entry.isLinkedCredential) {
                saveCredential(entry.credential.copy(otpParams = null))
            } else {
                deleteCredential(entry.credential.id)
            }
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
