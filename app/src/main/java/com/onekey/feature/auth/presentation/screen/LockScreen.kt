package com.onekey.feature.auth.presentation.screen

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay

private val PinSuccessColor = Color(0xFF4CAF50)

@Composable
fun LockScreen(
    viewModel: AuthViewModel,
    onUnlocked: () -> Unit,
    onSetupPin: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPinSetup by viewModel.isPinSetup.collectAsStateWithLifecycle()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val requiresMasterPasswordRecheck by viewModel.requiresMasterPasswordRecheck.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Delay navigation by enough time for the success pulse animation to complete (260 ms).
    LaunchedEffect(state) {
        if (state is AuthUiState.Unlocked) {
            delay(280)
            onUnlocked()
        }
    }

    val canUseBiometric = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

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
                if (requiresMasterPasswordRecheck || !isPinSetup) "Enter master password to unlock"
                else "Enter PIN to unlock",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))

            if (requiresMasterPasswordRecheck) {
                MasterPasswordRecheckBanner()
                Spacer(Modifier.height(20.dp))
                PasswordUnlockSection(
                    state = state,
                    onPasswordSubmit = { viewModel.unlockWithPassword(it) },
                )
            } else if (isPinSetup) {
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

            if (isBiometricEnabled && canUseBiometric && !requiresMasterPasswordRecheck) {
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
private fun MasterPasswordRecheckBanner() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp).padding(top = 2.dp),
            )
            Text(
                "Periodic security check — please verify with your master password",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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

    val density = LocalDensity.current
    val shakePx = remember(density) { with(density) { 16.dp.toPx() } }

    val shakeOffset = remember { Animatable(0f) }
    val dotScale  = remember { Animatable(1f) }

    // Dot fill color: green on success, red on error, primary otherwise.
    val dotFilledColor by animateColorAsState(
        targetValue = when (state) {
            is AuthUiState.Unlocked -> PinSuccessColor
            is AuthUiState.Error    -> MaterialTheme.colorScheme.error
            else                    -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 150),
        label = "dotFilledColor",
    )

    LaunchedEffect(state) {
        when (state) {
            is AuthUiState.Error -> {
                // Damped left-right shake — dots stay red+filled during shake, clear afterwards.
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 460
                        0f              at 0
                        -shakePx        at 65
                        shakePx         at 130
                        (-shakePx * .70f) at 195
                        ( shakePx * .70f) at 260
                        (-shakePx * .40f) at 325
                        ( shakePx * .40f) at 390
                        0f              at 460
                    }
                )
                pin = ""  // Clear only after the shake so dots stay visible while shaking
            }
            is AuthUiState.Unlocked -> {
                // Success pulse: quick scale up then back to normal (completes in ~260 ms)
                dotScale.animateTo(1.18f, tween(130))
                dotScale.animateTo(1.00f, tween(130))
            }
            else -> Unit
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.graphicsLayer {
                translationX = shakeOffset.value
                scaleX = dotScale.value
                scaleY = dotScale.value
            },
        ) {
            repeat(6) { index ->
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (index < pin.length) dotFilledColor
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

    val density = LocalDensity.current
    val shakePx = remember(density) { with(density) { 16.dp.toPx() } }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(state) {
        if (state is AuthUiState.Error) {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 460
                    0f              at 0
                    -shakePx        at 65
                    shakePx         at 130
                    (-shakePx * .70f) at 195
                    ( shakePx * .70f) at 260
                    (-shakePx * .40f) at 325
                    ( shakePx * .40f) at 390
                    0f              at 460
                }
            )
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { translationX = shakeOffset.value },
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Master Password") },
            visualTransformation = if (showPassword) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (password.isNotEmpty()) onPasswordSubmit(password.toCharArray())
            }),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                    )
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
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onSuccess()
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                    code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    onError(msg.toString())
                }
            }
            override fun onAuthenticationFailed() = Unit
        }
    )
    BiometricPrompt.PromptInfo.Builder()
        .setTitle("Biometric Unlock")
        .setSubtitle("Use biometric to access 1Key")
        .setNegativeButtonText("Use PIN / Password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
        .also { prompt.authenticate(it) }
}
