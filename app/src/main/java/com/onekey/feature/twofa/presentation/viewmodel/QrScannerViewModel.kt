package com.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.usecase.SaveCredentialUseCase
import com.onekey.feature.twofa.domain.OtpAuthParams
import com.onekey.feature.twofa.domain.OtpAuthUriParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

sealed class ScanState {
    data object Scanning : ScanState()
    data class Detected(val params: OtpAuthParams, val suggestedTitle: String) : ScanState()
    data object Saving : ScanState()
    data object Saved : ScanState()
    data class Error(val message: String) : ScanState()
}

@HiltViewModel
class QrScannerViewModel @Inject constructor(
    private val saveCredential: SaveCredentialUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    // Prevents duplicate processing when multiple frames detect the same QR code.
    private val processed = AtomicBoolean(false)

    fun onBarcodeDetected(rawValue: String) {
        if (!processed.compareAndSet(false, true)) return
        val params = OtpAuthUriParser.parse(rawValue)
        if (params == null) {
            processed.set(false) // not a valid otpauth URI — keep scanning
            return
        }
        _state.value = ScanState.Detected(params, buildTitle(params))
    }

    fun save(params: OtpAuthParams, title: String) {
        viewModelScope.launch {
            _state.value = ScanState.Saving
            val credential = Credential(
                id = "",
                title = title.ifBlank { buildTitle(params) },
                username = params.account,
                password = "",
                url = "",
                notes = "",
                totpSecret = params.secret,
                tags = emptyList(),
                customFields = emptyList(),
                isFavorite = false,
                createdAt = 0L,
                updatedAt = 0L,
            )
            when (val result = saveCredential(credential)) {
                is AppResult.Success -> _state.value = ScanState.Saved
                is AppResult.Error -> _state.value =
                    ScanState.Error(result.message ?: result.exception.message ?: "Save failed")
            }
        }
    }

    fun dismissDetected() {
        processed.set(false)
        _state.value = ScanState.Scanning
    }

    private fun buildTitle(params: OtpAuthParams): String = when {
        params.issuer.isNotEmpty() && params.account.isNotEmpty() ->
            "${params.issuer} — ${params.account}"
        params.issuer.isNotEmpty() -> params.issuer
        else -> params.account
    }
}
