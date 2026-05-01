package com.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType
import com.onekey.feature.twofa.domain.OtpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the per-credential TOTP / Steam display embedded in
 * [com.onekey.feature.twofa.presentation.screen.TotpWidget].
 *
 * `remainingSeconds` and `progress` are kept non-nullable here because the widget
 * only renders for time-based variants (the caller in CredentialDetailScreen gates
 * on `params.type in TOTP, STEAM`). HOTP entries take a different UI path — the
 * 2FA list's "Generate next code" button (added in C5) — and never reach this VM.
 */
data class TotpUiState(
    val code: String = "------",
    val remainingSeconds: Int = 30,
    val progress: Float = 1f,
)

@HiltViewModel
class TotpViewModel @Inject constructor(
    private val otpGenerator: OtpGenerator,
) : ViewModel() {

    private val _state = MutableStateFlow(TotpUiState())
    val state: StateFlow<TotpUiState> = _state.asStateFlow()

    /**
     * Tracks the in-flight generation job so re-keying the widget (different
     * credential, edited secret) cancels the prior loop instead of stacking
     * coroutines that race to set `_state`. Without this, a quick edit + revisit
     * would leak one ticking coroutine per (re)entry until the VM is cleared.
     */
    private var tickerJob: Job? = null

    fun startGenerating(params: OtpParams) {
        // HOTP must never reach the rotating UI — the widget's caller in C2 gates
        // on type, but this defensive bail keeps the VM correct on its own if a
        // future caller forgets. Without it we'd silently advance the HOTP code
        // every second and never persist the counter, desyncing the user.
        if (params.type == OtpType.HOTP) return

        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                val result = runCatching { otpGenerator.generate(params) }.getOrNull()
                if (result != null) {
                    _state.value = TotpUiState(
                        code = result.code,
                        // Time-based variants always populate these; the !! makes the
                        // structural invariant explicit at the boundary.
                        remainingSeconds = result.remainingSeconds!!,
                        progress = result.progress!!,
                    )
                }
                delay(TICK_MILLIS)
            }
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
