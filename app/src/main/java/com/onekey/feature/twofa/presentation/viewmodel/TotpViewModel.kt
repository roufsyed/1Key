package com.onekey.feature.twofa.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onekey.feature.twofa.domain.TotpGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TotpUiState(
    val code: String = "------",
    val remainingSeconds: Int = 30,
    val progress: Float = 1f,
)

@HiltViewModel
class TotpViewModel @Inject constructor(
    private val totpGenerator: TotpGenerator,
) : ViewModel() {

    private val _state = MutableStateFlow(TotpUiState())
    val state: StateFlow<TotpUiState> = _state.asStateFlow()

    fun startGenerating(base32Secret: String) {
        viewModelScope.launch {
            while (true) {
                val result = totpGenerator.generate(base32Secret)
                _state.value = TotpUiState(
                    code = result.code,
                    remainingSeconds = result.remainingSeconds,
                    progress = result.progress,
                )
                delay(1_000L)
            }
        }
    }
}
