package com.roufsyed.onekey.feature.twofa.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roufsyed.onekey.core.domain.model.OtpParams
import com.roufsyed.onekey.feature.twofa.presentation.viewmodel.TotpViewModel

/**
 * Embedded rotating-OTP display for time-based variants (TOTP, STEAM). Renders
 * the current code in the existing 32sp monospace style with a 36dp countdown
 * ring, matching the look used in the 2FA list rows.
 *
 * HOTP entries take a separate "Generate next code" UI in the 2FA list (added in
 * C5) and must NOT be passed to this widget - [TotpViewModel.startGenerating]
 * defensively bails on HOTP, but the caller is expected to gate on
 * `params.type in TOTP, STEAM` for clarity.
 *
 * Code formatting goes through [formatOtpCode], which renders Steam Guard's
 * 5-character codes as a single contiguous block (`RXKBC`) and applies 4-digit
 * grouping for 7- and 8-digit TOTP codes - keeping every OTP surface consistent.
 */
@Composable
fun TotpWidget(
    params: OtpParams,
    viewModel: TotpViewModel = hiltViewModel(),
) {
    LaunchedEffect(params) { viewModel.startGenerating(params) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("2FA Code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatOtpCode(params, state.code),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 32.sp,
                    letterSpacing = 4.sp,
                ),
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = if (state.remainingSeconds <= 5)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                )
                Text("${state.remainingSeconds}s", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
