package com.onekey.feature.auth.presentation.screen

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.VpnKey
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // Delay navigation until the success animation completes (~530 ms).
    LaunchedEffect(state) {
        if (state is AuthUiState.Unlocked) {
            delay(540)
            onUnlocked()
        }
    }

    val canUseBiometric = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // Auto-trigger biometric prompt once when the screen loads with biometric enabled.
    // Keyed on both preference-backed flags so it re-evaluates after DataStore emits real values
    // (both start as false while loading). autoTriggeredBiometric prevents repeated triggers
    // if the user cancels and then the composable recomposes with the same key values.
    var autoTriggeredBiometric by remember { mutableStateOf(false) }
    LaunchedEffect(isBiometricEnabled, requiresMasterPasswordRecheck) {
        if (!autoTriggeredBiometric && isBiometricEnabled && canUseBiometric && !requiresMasterPasswordRecheck) {
            autoTriggeredBiometric = true
            showBiometricPrompt(
                context = context,
                onSuccess = { viewModel.unlockWithBiometric() },
                onError = { msg -> viewModel.setBiometricError(msg) },
            )
        }
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
            LogoSection(state = state)
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
private fun LogoSection(state: AuthUiState) {
    val textAlpha   = remember { Animatable(1f) }
    val iconAlpha   = remember { Animatable(0f) }
    val iconScale   = remember { Animatable(0.55f) }
    val keyRotation = remember { Animatable(0f) }
    val rippleScale = remember { Animatable(0f) }
    val rippleAlpha = remember { Animatable(0f) }
    val shakeOffset = remember { Animatable(0f) }

    val keyColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val shakePx = remember(density) { with(density) { 10.dp.toPx() } }

    LaunchedEffect(state) {
        when (state) {
            is AuthUiState.Unlocked -> {
                // Phase 1 (0–180ms): "1Key" text fades out while key icon scales in
                coroutineScope {
                    launch { textAlpha.animateTo(0f, tween(80, easing = LinearEasing)) }
                    launch {
                        delay(30)
                        coroutineScope {
                            launch { iconAlpha.animateTo(1f, tween(120, easing = LinearEasing)) }
                            launch { iconScale.animateTo(1f, tween(150, easing = EaseOutBack)) }
                        }
                    }
                }
                // Phase 2 (180–530ms): rotate key + radiate success ripple (concurrent)
                coroutineScope {
                    launch {
                        keyRotation.animateTo(360f, tween(220, easing = FastOutSlowInEasing))
                    }
                    launch {
                        rippleAlpha.snapTo(0.32f)
                        coroutineScope {
                            launch {
                                rippleScale.animateTo(3.0f, tween(350, easing = LinearOutSlowInEasing))
                            }
                            launch {
                                delay(50)
                                rippleAlpha.animateTo(0f, tween(300, easing = LinearEasing))
                            }
                        }
                    }
                }
            }
            is AuthUiState.Error -> {
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 400
                        0f               at 0
                        (-shakePx)       at 55
                        shakePx          at 110
                        (-shakePx * .6f) at 175
                        (shakePx * .6f)  at 235
                        (-shakePx * .3f) at 310
                        0f               at 400
                    },
                )
            }
            else -> Unit
        }
    }

    // Both composables always live in the tree — graphicsLayer alpha keeps layout stable.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.graphicsLayer { translationX = shakeOffset.value },
    ) {
        // Expanding ripple circle — starts invisible, pulses outward on success
        Box(
            modifier = Modifier
                .size(100.dp)
                .graphicsLayer {
                    scaleX = rippleScale.value
                    scaleY = rippleScale.value
                    alpha  = rippleAlpha.value
                }
                .background(keyColor, CircleShape),
        )
        // Key icon — morphs in from the text and then spins
        Icon(
            Icons.Default.VpnKey,
            contentDescription = "Vault unlocked",
            modifier = Modifier
                .size(80.dp)
                .graphicsLayer {
                    rotationZ = keyRotation.value
                    scaleX    = iconScale.value
                    scaleY    = iconScale.value
                    alpha     = iconAlpha.value
                },
            tint = keyColor,
        )
        // App name — always composed for layout stability; fades out on success
        Text(
            "1Key",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.graphicsLayer { alpha = textAlpha.value },
        )
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
            Column {
                Text(
                    "Periodic security check — please verify with your master password",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "This check ensures only you — not just someone with your unlocked phone — can access the vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
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
