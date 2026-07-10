package com.roufsyed.onekey.feature.twofa.presentation.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.usecase.SaveCredentialUseCase
import com.roufsyed.onekey.core.security.AutoLockManager
import com.roufsyed.onekey.core.security.VaultLockedException
import com.roufsyed.onekey.feature.twofa.domain.OtpAuthUriParser
import com.roufsyed.onekey.feature.twofa.domain.ParsedOtpAuthUri
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

    /**
     * The camera read a valid `otpauth://` URI. [parsed] carries the full generation
     * params (algorithm/digits/period/counter/type, including auto-detected Steam)
     * plus issuer/account; downstream save uses [parsed.params] verbatim, so a
     * SHA-256 / 8-digit / Steam QR persists faithfully without any defaults applied.
     */
    data class Detected(val parsed: ParsedOtpAuthUri, val suggestedTitle: String) : ScanState()

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
        val parsed = OtpAuthUriParser.parse(rawValue)
        if (parsed == null) {
            processed.set(false)
            emitInvalidQrThrottled()
            return
        }
        _state.value = ScanState.Detected(parsed, buildTitle(parsed))
    }

    fun save(parsed: ParsedOtpAuthUri, title: String) {
        viewModelScope.launch {
            _state.value = ScanState.Saving
            val credential = Credential(
                id = "",
                title = title.ifBlank { buildTitle(parsed) },
                username = parsed.account,
                password = "",
                url = "",
                notes = "",
                // Persist the full parsed params (algorithm/digits/period/counter/type)
                // - saving as defaultTotp would silently drop SHA-256, 8-digit, 60s,
                // HOTP, and Steam QRs into a vanilla SHA-1/30s/6 entry that yields
                // wrong codes against the issuer.
                otpParams = parsed.params,
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

    private fun buildTitle(parsed: ParsedOtpAuthUri): String = when {
        parsed.issuer.isNotEmpty() && parsed.account.isNotEmpty() ->
            "${parsed.issuer} - ${parsed.account}"
        parsed.issuer.isNotEmpty() -> parsed.issuer
        else -> parsed.account
    }

    private companion object {
        const val INVALID_QR_THROTTLE_MS = 2_500L
    }
}
