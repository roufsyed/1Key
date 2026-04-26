package com.onekey.feature.auth.presentation.screen

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.R
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

private val PinSuccessColor = Color(0xFF4CAF50)
private val AppIconBlue = Color(0xFF1A56DB)
private val PremiumMorphEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

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
    val successMorphProgress = remember { Animatable(0f) }
    val lockScreenExitProgress = remember { Animatable(0f) }

    // Run logo success first, then morph to app blue before transitioning.
    LaunchedEffect(state) {
        if (state is AuthUiState.Unlocked) {
            successMorphProgress.snapTo(0f)
            lockScreenExitProgress.snapTo(0f)
            delay(430)
            coroutineScope {
                launch {
                    successMorphProgress.animateTo(
                        targetValue = 0.66f,
                        animationSpec = tween(durationMillis = 540, easing = LinearOutSlowInEasing),
                    )
                    successMorphProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 460, easing = PremiumMorphEasing),
                    )
                }
                launch {
                    delay(230)
                    lockScreenExitProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 860, easing = PremiumMorphEasing),
                    )
                }
            }
            delay(90)
            onUnlocked()
        } else {
            successMorphProgress.snapTo(0f)
            lockScreenExitProgress.snapTo(0f)
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

    val titleText = "Welcome\nback."
    val subtitleText = if (requiresMasterPasswordRecheck || !isPinSetup) {
        "Type your master password to open the vault."
    } else {
        "Enter your PIN to open the vault."
    }
    val errorMessage = (state as? AuthUiState.Error)?.message

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                )
            )
            .imePadding(),
    ) {
        val density = LocalDensity.current
        val horizontalPadding = if (maxWidth < 400.dp) 24.dp else 32.dp
        val topSpacing = (maxHeight * 0.13f).coerceIn(44.dp, 120.dp)
        val heroToFormSpacing = (maxHeight * 0.20f).coerceIn(56.dp, 220.dp)
        val exitTravelPx = remember(maxHeight, density) { with(density) { maxHeight.toPx() * 1.08f } }
        val exitProgress = lockScreenExitProgress.value

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -exitTravelPx * exitProgress
                    alpha = 1f - (0.24f * exitProgress)
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(topSpacing))
            LogoSection(
                state = state,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(start = 8.dp),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                titleText,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = MaterialTheme.typography.displayMedium.lineHeight,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                subtitleText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                textAlign = TextAlign.Start,
            )

            Spacer(Modifier.height(heroToFormSpacing))

            if (requiresMasterPasswordRecheck) {
                MasterPasswordRecheckBanner()
                Spacer(Modifier.height(14.dp))
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (isBiometricEnabled && canUseBiometric && !requiresMasterPasswordRecheck) {
                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        showBiometricPrompt(
                            context = context,
                            onSuccess = { viewModel.unlockWithBiometric() },
                            onError = { msg -> viewModel.setBiometricError(msg) },
                        )
                    },
                    enabled = state !is AuthUiState.Loading,
                ) {
                    Spacer(Modifier.width(8.dp))
                    Text("Use fingerprint", style = MaterialTheme.typography.titleSmall)
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        SuccessMorphOverlay(
            progress = successMorphProgress.value,
            color = AppIconBlue,
        )
    }
}

@Composable
private fun SuccessMorphOverlay(
    progress: Float,
    color: Color,
) {
    if (progress <= 0f) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val easedProgress = PremiumMorphEasing.transform(progress.coerceIn(0f, 1f))
        val maxRadius = hypot(size.width.toDouble(), size.height.toDouble()).toFloat() * 1.1f
        val radius = maxRadius * easedProgress
        val origin = Offset(
            x = size.width * 0.17f,
            y = size.height * 0.24f,
        )

        drawCircle(
            color = color.copy(alpha = 0.82f + (0.16f * easedProgress)),
            radius = radius,
            center = origin,
        )

        if (progress > 0.72f) {
            val fillAlpha = ((progress - 0.72f) / 0.28f).coerceIn(0f, 1f)
            drawRect(color = color.copy(alpha = fillAlpha))
        }
    }
}

@Composable
private fun LogoSection(
    state: AuthUiState,
    modifier: Modifier = Modifier,
) {
    val iconScale   = remember { Animatable(2f) }
    val keyRotation = remember { Animatable(2f) }
    val rippleScale = remember { Animatable(2f) }
    val rippleAlpha = remember { Animatable(0f) }
    val shakeOffset = remember { Animatable(0f) }

    val keyColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val shakePx = remember(density) { with(density) { 10.dp.toPx() } }

    LaunchedEffect(state) {
        when (state) {
            is AuthUiState.Unlocked -> {
                coroutineScope {
                    launch { iconScale.animateTo(1.22f, tween(150, easing = EaseOutBack)) }
                    launch { delay(0); iconScale.animateTo(2f, tween(130, easing = FastOutSlowInEasing)) }
                }
                coroutineScope {
                    launch {
                        keyRotation.animateTo(360f, tween(260, easing = FastOutSlowInEasing))
                    }
                    launch {
                        rippleAlpha.snapTo(0.20f)
                        coroutineScope {
                            launch {
                                rippleScale.animateTo(2.8f, tween(340, easing = LinearOutSlowInEasing))
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

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier.graphicsLayer { translationX = shakeOffset.value },
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = rippleScale.value
                    scaleY = rippleScale.value
                    alpha = rippleAlpha.value
                }
                .background(keyColor, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    rotationZ = keyRotation.value
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                }
                .background(AppIconBlue, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_lockscreen_key_foreground),
                contentDescription = "1Key app icon",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun MasterPasswordRecheckBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp),
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
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            ),
        )
        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = onFallbackToPassword,
            enabled = !isLoading,
            modifier = Modifier.align(Alignment.End),
        ) {
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
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
            ),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (password.isNotEmpty()) onPasswordSubmit(password.toCharArray()) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = password.isNotEmpty() && state !is AuthUiState.Loading,
            shape = RoundedCornerShape(18.dp),
        ) {
            if (state is AuthUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Unlock vault")
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
        .setSubtitle("Use biometric to unlock vault")
        .setNegativeButtonText("Use master password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
        .also { prompt.authenticate(it) }
}
