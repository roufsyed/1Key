package com.onekey.feature.auth.presentation.screen

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel

@Composable
fun LockScreen(
    viewModel: AuthViewModel,
    onUnlocked: () -> Unit,
    onSetupPin: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPinSetup by viewModel.isPinSetup.collectAsStateWithLifecycle()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state) {
        if (state is AuthUiState.Unlocked) onUnlocked()
    }

    val canUseBiometric = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // imePadding on the outer Box so keyboard pushes content up rather than hiding buttons
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("1Key", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isPinSetup) "Enter PIN to unlock" else "Enter master password to unlock",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))

            if (isPinSetup) {
                PinUnlockSection(
                    state = state,
                    onPinSubmit = { viewModel.unlockWithPin(it) },
                    onFallbackToPassword = { viewModel.clearError() },
                )
            } else {
                PasswordUnlockSection(
                    state = state,
                    onPasswordSubmit = { viewModel.unlockWithPassword(it) },
                )
            }

            val errorMessage = (state as? AuthUiState.Error)?.message
            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (isBiometricEnabled && canUseBiometric) {
                Spacer(Modifier.height(16.dp))
                IconButton(
                    onClick = {
                        showBiometricPrompt(
                            context = context,
                            onSuccess = { viewModel.unlockWithBiometric() },
                            onError = { msg -> viewModel.setBiometricError(msg) },
                        )
                    },
                    enabled = state !is AuthUiState.Loading,
                ) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = "Biometric unlock",
                        modifier = Modifier.size(40.dp),
                    )
                }
                Text(
                    "Use biometric",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PinUnlockSection(
    state: AuthUiState,
    onPinSubmit: (String) -> Unit,
    onFallbackToPassword: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val isLoading = state is AuthUiState.Loading

    // Clear PIN after a wrong attempt so user can retry cleanly.
    LaunchedEffect(state) {
        if (state is AuthUiState.Error) pin = ""
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(6) { index ->
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (index < pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(14.dp),
                ) {}
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { new ->
                if (new.length <= 6 && new.all { it.isDigit() }) {
                    pin = new
                    if (new.length == 6) onPinSubmit(new)
                }
            },
            label = { Text("PIN") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (pin.isNotEmpty()) onPinSubmit(pin) }),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(200.dp),
            enabled = !isLoading,
            singleLine = true,
        )
        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onFallbackToPassword, enabled = !isLoading) {
            Text("Forgot PIN? Use master password")
        }
    }
}

@Composable
private fun PasswordUnlockSection(
    state: AuthUiState,
    onPasswordSubmit: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Master Password") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (password.isNotEmpty()) onPasswordSubmit(password.toCharArray())
            }),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state !is AuthUiState.Loading,
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (password.isNotEmpty()) onPasswordSubmit(password.toCharArray()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = password.isNotEmpty() && state !is AuthUiState.Loading,
        ) {
            if (state is AuthUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Unlock")
            }
        }
    }
}

private fun showBiometricPrompt(
    context: android.content.Context,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val activity = context as? FragmentActivity ?: return
    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(code: Int, msg: CharSequence) {
            if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                onError(msg.toString())
            }
        }
        override fun onAuthenticationFailed() = onError("Biometric not recognized")
    })
    BiometricPrompt.PromptInfo.Builder()
        .setTitle("Biometric Unlock")
        .setSubtitle("Use biometric to access 1Key")
        .setNegativeButtonText("Use PIN / Password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
        .also { prompt.authenticate(it) }
}
