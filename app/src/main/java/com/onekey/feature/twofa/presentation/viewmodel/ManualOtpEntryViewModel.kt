package com.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType
import com.onekey.core.domain.usecase.SaveCredentialUseCase
import com.onekey.core.security.VaultLockedException
import com.onekey.feature.twofa.domain.OtpAuthUriParser
import com.onekey.feature.twofa.domain.OtpGenerator
import com.onekey.feature.twofa.domain.OtpSecretValidator
import com.onekey.feature.twofa.domain.ParsedOtpAuthUri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the "Enter manually" 2FA add flow opened from the FAB picker.
 *
 * Kept distinct from [QrScannerViewModel] for clean separation of concerns:
 * the QR VM holds camera lifecycle (suppression begin/end, one-shot detected
 * state), neither of which apply here. Sharing one VM would silently acquire
 * inactivity-suppression on every manual edit, complicating the rest of the
 * lock graph.
 *
 * The save path mirrors [QrScannerViewModel]: same `SaveCredentialUseCase`
 * call, same `VaultLockedException` swallow-and-let-NavGraph-route pattern,
 * same final `Saved` terminal state. Validation lives in domain helpers
 * ([OtpSecretValidator] and [OtpAuthUriParser]) so this VM stays a thin
 * orchestration layer over those rules.
 */
@HiltViewModel
class ManualOtpEntryViewModel @Inject constructor(
    private val saveCredential: SaveCredentialUseCase,
    private val otpGenerator: OtpGenerator,
) : ViewModel() {

    sealed class State {
        data object Editing : State()
        data object Saving : State()
        data object Saved : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Editing)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Validate a raw user-typed secret. Pure delegation; here so the sheet
     * doesn't need to know which validator to call or how to thread the
     * generator through.
     */
    fun validateSecret(input: String): OtpSecretValidator.Result =
        OtpSecretValidator.validate(input, otpGenerator)

    /**
     * If [input] is an `otpauth://` URI, parse it. Returns null otherwise.
     * The sheet uses this to detect a pasted URI in the secret field and
     * auto-fill all the other fields including the Advanced section, then
     * collapses the secret field back to the raw base32 value.
     */
    fun parseAsUri(input: String): ParsedOtpAuthUri? =
        if (input.trimStart().startsWith(URI_SCHEME_PREFIX)) OtpAuthUriParser.parse(input.trim())
        else null

    /**
     * Persist a new credential carrying the supplied OTP params. The caller
     * has already run validation (sheet's Save button is disabled until then),
     * so by the time we build [Credential] we trust the inputs.
     *
     * Steam detection: if [issuer] equals "Steam" (case-insensitive) and the
     * user picked TOTP, we auto-promote to [OtpType.STEAM]. This is the manual
     * counterpart to [OtpAuthUriParser]'s Steam auto-detection - users who type
     * `Steam` as the issuer get the right code shape without a separate toggle.
     * HOTP entries don't get the promotion (Steam doesn't use counter-based codes).
     */
    fun save(issuer: String, account: String, params: OtpParams) {
        viewModelScope.launch {
            _state.value = State.Saving
            val resolvedParams = if (
                params.type == OtpType.TOTP &&
                issuer.equals(STEAM_ISSUER, ignoreCase = true)
            ) {
                params.copy(type = OtpType.STEAM)
            } else {
                params
            }
            val credential = Credential(
                id = "",
                title = buildTitle(issuer, account),
                username = account,
                password = "",
                url = "",
                notes = "",
                otpParams = resolvedParams,
                tags = emptyList(),
                customFields = emptyList(),
                isFavorite = false,
                createdAt = 0L,
                updatedAt = 0L,
            )
            when (val result = saveCredential(credential)) {
                is AppResult.Success -> _state.value = State.Saved
                is AppResult.Error -> {
                    if (result.exception is VaultLockedException) {
                        // Vault auto-locked between sheet open and Save tap. NavGraph
                        // routes to LockScreen on the next recomposition; leaving state
                        // on Saving avoids surfacing a confusing message before the
                        // route happens.
                        return@launch
                    }
                    _state.value = State.Error(result.message ?: "Save failed")
                }
            }
        }
    }

    private fun buildTitle(issuer: String, account: String): String =
        if (account.isNotEmpty()) "$issuer - $account" else issuer

    private companion object {
        const val URI_SCHEME_PREFIX = "otpauth://"
        const val STEAM_ISSUER = "Steam"
    }
}
