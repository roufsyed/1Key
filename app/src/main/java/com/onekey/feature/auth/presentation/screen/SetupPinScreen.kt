package com.onekey.feature.auth.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupPinScreen(
    viewModel: AuthViewModel,
    onPinSet: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) }
    var showMismatch by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is AuthUiState.SetupComplete) {
            viewModel.clearError()
            onPinSet()
        }
    }

    LaunchedEffect(showMismatch) {
        if (showMismatch) {
            delay(1_500)
            confirmPin = ""
            showMismatch = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == 0) "Set PIN" else "Confirm PIN") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step == 1) {
                            step = 0
                            confirmPin = ""
                            showMismatch = false
                        } else {
                            onBack()
                        }
                    }) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (step == 0) "Enter a 6-digit PIN" else "Confirm your PIN",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your PIN unlocks access to the vault key stored in Android KeyStore. The PIN digits themselves are never saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            val currentPin = if (step == 0) pin else confirmPin
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { index ->
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (index < currentPin.length) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(14.dp),
                    ) {}
                }
            }
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = currentPin,
                onValueChange = { new ->
                    if (new.length <= 6 && new.all { it.isDigit() }) {
                        if (step == 0) {
                            pin = new
                            if (new.length == 6) step = 1
                        } else {
                            confirmPin = new
                            if (new.length == 6) {
                                if (pin == new) viewModel.setupPin(pin)
                                else showMismatch = true
                            }
                        }
                    }
                },
                label = { Text("PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(200.dp),
                enabled = state !is AuthUiState.Loading,
                singleLine = true,
            )

            if (showMismatch) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "PINs do not match",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state is AuthUiState.Error) {
                Spacer(Modifier.height(12.dp))
                Text(
                    (state as AuthUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state is AuthUiState.Loading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}
