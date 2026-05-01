package com.onekey.feature.twofa.presentation.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.usecase.SaveCredentialUseCase
import com.onekey.core.security.AutoLockManager
import com.onekey.core.security.VaultLockedException
import com.onekey.feature.twofa.domain.OtpAuthParams
import com.onekey.feature.twofa.domain.OtpAuthUriParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

sealed class ScanEvent {
    /** A QR code was read but it isn't an otpauth:// URI we can use. */
    data object InvalidQr : ScanEvent()
}

@HiltViewModel
class QrScannerViewModel @Inject constructor(
    private val saveCredential: SaveCredentialUseCase,
    private val autoLockManager: AutoLockManager,
) : ViewModel() {

    /**
     * Suppress the inactivity auto-lock while the QR camera preview is active.
     * Pair every call with [endCameraSession]. See
     * [AutoLockManager.acquireInactivitySuppression] for the rationale.
     */
    fun beginCameraSession() = autoLockManager.acquireInactivitySuppression()
    fun endCameraSession() = autoLockManager.releaseInactivitySuppression()

    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    // Prevents duplicate processing when multiple frames detect the same QR code.
    private val processed = AtomicBoolean(false)

    // Throttle so the InvalidQr toast doesn't fire 30×/sec while the camera keeps
    // re-detecting the same non-OTP QR. One emit per 2.5 s is enough for the user.
    private var lastInvalidEmitMs = 0L

    fun onBarcodeDetected(rawValue: String) {
        if (!processed.compareAndSet(false, true)) return
        val params = OtpAuthUriParser.parse(rawValue)
        if (params == null) {
            processed.set(false)
            emitInvalidQrThrottled()
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
                is AppResult.Error -> {
                    if (result.exception is VaultLockedException) {
                        // Vault auto-locked between scan and save. NavGraph routes to
                        // LockScreen on the next recomposition; leaving state on Saving
                        // avoids a misleading snackbar with the raw exception message.
                        return@launch
                    }
                    _state.value =
                        ScanState.Error(result.message ?: result.exception.message ?: "Save failed")
                }
            }
        }
    }

    fun dismissDetected() {
        processed.set(false)
        _state.value = ScanState.Scanning
    }

    private fun emitInvalidQrThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInvalidEmitMs < INVALID_QR_THROTTLE_MS) return
        lastInvalidEmitMs = now
        viewModelScope.launch { _events.emit(ScanEvent.InvalidQr) }
    }

    private fun buildTitle(params: OtpAuthParams): String = when {
        params.issuer.isNotEmpty() && params.account.isNotEmpty() ->
            "${params.issuer} — ${params.account}"
        params.issuer.isNotEmpty() -> params.issuer
        else -> params.account
    }

    private companion object {
        const val INVALID_QR_THROTTLE_MS = 2_500L
    }
}
