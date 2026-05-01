package com.onekey.feature.twofa.presentation.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.twofa.presentation.viewmodel.TotpViewModel

@Composable
fun TotpWidget(
    secret: String,
    viewModel: TotpViewModel = hiltViewModel(),
) {
    LaunchedEffect(secret) { viewModel.startGenerating(secret) }

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
                text = state.code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
