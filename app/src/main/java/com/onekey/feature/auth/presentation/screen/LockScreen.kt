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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.onekey.core.presentation.animation.AppIconBlue
import com.onekey.core.presentation.animation.PremiumMorphEasing
import com.onekey.core.presentation.animation.UnlockTransitionPhase
import com.onekey.core.presentation.animation.UnlockTransitionTimings
import com.onekey.core.presentation.util.rememberCanUseBiometric
import com.onekey.core.presentation.viewmodel.AppViewModel
import com.onekey.core.security.LockReason
import com.onekey.feature.auth.presentation.viewmodel.AuthEvent
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val PinSuccessColor = Color(0xFF4CAF50)

@Composable
fun LockScreen(
    viewModel: AuthViewModel,
    appViewModel: AppViewModel,
    onUnlocked: () -> Unit,
    onSetupPin: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPinSetup by viewModel.isPinSetup.collectAsStateWithLifecycle()
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val requiresMasterPasswordRecheck by viewModel.requiresMasterPasswordRecheck.collectAsStateWithLifecycle()
    val lockReason by viewModel.lockReason.collectAsStateWithLifecycle()
    // Atomic snapshot of (biometric enabled, lock reason set) — read together from the same
    // DataStore Preferences object so the auto-trigger never fires in the brief cold-start
    // window where one of the two flat flows has updated and the other hasn't.
    val biometricUnlockGate by viewModel.biometricUnlockGate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lockScreenExitProgress = remember { Animatable(0f) }

    // While a lock reason is active the user must enter their master password — biometric
    // and PIN are both held back until they prove possession that way. The store-backed
    // `lockReason` only clears after a successful unlockWithPassword.
    val biometricBlockedByLockReason = lockReason != null
    // Local dialog dismissal so OK closes the dialog without releasing the biometric block.
    var lockReasonDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lockReason) { if (lockReason == null) lockReasonDismissed = false }

    // Local fallback flag — flipped by the "Forgot PIN? Use master password" button or
    // when the VM signals exhausted PIN attempts. While true, the password section
    // renders even though a PIN exists. rememberSaveable so config changes don't bounce
    // the user back to PIN mid-fallback.
    var forcePasswordFallback by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.PinAttemptsExhausted -> { forcePasswordFallback = true }
            }
        }
    }

    // Logo celebration plays first; then we hand the morph off to the app-root UnlockOverlay
    // so it can sit above the Scaffold and bottom nav while navigation completes.
    LaunchedEffect(state) {
        if (state is AuthUiState.Unlocked) {
            lockScreenExitProgress.snapTo(0f)
            delay(UnlockTransitionTimings.LOGO_CELEBRATION_DELAY_MS)
            appViewModel.beginUnlockMorph()
            coroutineScope {
                launch {
                    delay(230)
                    lockScreenExitProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 860, easing = PremiumMorphEasing),
                    )
                }
                launch {
                    appViewModel.unlockPhase.first { it is UnlockTransitionPhase.Held }
                    delay(UnlockTransitionTimings.POST_HELD_NAV_BUFFER_MS)
                    onUnlocked()
                }
            }
        } else {
            lockScreenExitProgress.snapTo(0f)
            appViewModel.resetUnlockMorph()
        }
    }

    val canUseBiometric = rememberCanUseBiometric()

    // Auto-trigger biometric prompt once when the screen loads with biometric enabled.
    // Uses biometricUnlockGate (single Preferences read) to avoid a cold-start race where
    // `biometricEnabled` would flip true in one Compose snapshot while `lockReasonSet`
    // hadn't yet — which previously let the prompt sneak in during a too-many-attempts lock.
    // rememberSaveable so a rotation/config-change mid-LockScreen does not reset the
    // flag and re-fire the auto biometric prompt while the user may already be typing.
    var autoTriggeredBiometric by rememberSaveable { mutableStateOf(false) }
    // Held so we can dismiss the BiometricPrompt programmatically — specifically when the
    // too-many-failures lockout fires mid-prompt. Without this, the prompt stays on screen
    // accepting attempts the app would refuse anyway. Not saveable: a rotation rebuilds
    // the prompt naturally via the auto-trigger LaunchedEffect.
    var activeBiometricPrompt by remember { mutableStateOf<BiometricPrompt?>(null) }
    LaunchedEffect(lockReason) {
        if (lockReason != null) {
            activeBiometricPrompt?.cancelAuthentication()
            activeBiometricPrompt = null
        }
    }
    LaunchedEffect(biometricUnlockGate, requiresMasterPasswordRecheck) {
        if (!autoTriggeredBiometric &&
            biometricUnlockGate.biometricEnabled &&
            !biometricUnlockGate.lockReasonSet &&
            canUseBiometric &&
            !requiresMasterPasswordRecheck
        ) {
            autoTriggeredBiometric = true
            activeBiometricPrompt = showBiometricPrompt(
                context = context,
                onSuccess = { viewModel.unlockWithBiometric() },
                onError = { msg -> viewModel.setBiometricError(msg) },
                onAuthFailed = { viewModel.recordBiometricFailure() },
            )
        }
    }

    val titleText = "Welcome\nback."
    val mustUsePassword = requiresMasterPasswordRecheck || biometricBlockedByLockReason
    val subtitleText = when {
        biometricBlockedByLockReason ->
            "Enter your master password to re-enable biometric unlock."
        mustUsePassword || !isPinSetup ->
            "Type your master password to open the vault."
        else ->
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
                    .padding(start = 16.dp),
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

            if (mustUsePassword) {
                if (requiresMasterPasswordRecheck) MasterPasswordRecheckBanner()
                Spacer(Modifier.height(14.dp))
                PasswordUnlockSection(
                    state = state,
                    onPasswordSubmit = { viewModel.unlockWithPassword(it) },
                )
            } else if (isPinSetup && !forcePasswordFallback) {
                PinUnlockSection(
                    state = state,
                    onPinSubmit = { pin -> viewModel.unlockWithPin(pin.toCharArray()) },
                    onFallbackToPassword = {
                        forcePasswordFallback = true
                        viewModel.clearError()
                    },
                )
            } else {
                PasswordUnlockSection(
                    state = state,
                    onPasswordSubmit = { viewModel.unlockWithPassword(it) },
                )
                if (isPinSetup && forcePasswordFallback && !mustUsePassword) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        onClick = {
                            forcePasswordFallback = false
                            viewModel.clearError()
                        },
                    ) {
                        Text("Use PIN instead")
                    }
                }
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

            if (isBiometricEnabled && canUseBiometric && !mustUsePassword) {
                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        activeBiometricPrompt = showBiometricPrompt(
                            context = context,
                            onSuccess = { viewModel.unlockWithBiometric() },
                            onError = { msg -> viewModel.setBiometricError(msg) },
                            onAuthFailed = { viewModel.recordBiometricFailure() },
                        )
                    },
                    enabled = state !is AuthUiState.Loading,
                ) {
                    Spacer(Modifier.width(8.dp))
                    Text("Use biometric", style = MaterialTheme.typography.titleSmall)
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
    }

    if (lockReason != null && !lockReasonDismissed) {
        val message = when (val r = lockReason) {
            is LockReason.TooManyFailedAttempts ->
                "Three wrong master-password attempts during ${r.context}. " +
                    "That could mean someone other than you was trying to get in, " +
                    "so we've paused biometric unlock to keep your data safe. " +
                    "Enter your master password to confirm it's really you — " +
                    "biometric will work as usual on your next unlock."
            LockReason.TooManyFailedPinAttempts ->
                "Three wrong PIN attempts. That could mean someone other than you was " +
                    "trying to get in, so we've paused biometric unlock to keep your data safe. " +
                    "Enter your master password to confirm it's really you — biometric will " +
                    "work as usual on your next unlock."
            LockReason.TooManyFailedBiometricAttempts ->
                "Three failed biometric attempts. That could mean someone other than you " +
                    "was trying to get in, so we've paused biometric unlock to keep your data " +
                    "safe. Enter your master password to confirm it's really you — biometric " +
                    "will work as usual on your next unlock."
            null -> ""
        }
        AlertDialog(
            onDismissRequest = { /* non-dismissible — user must acknowledge */ },
            icon = {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Vault Locked") },
            text = {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                // Dismissing the dialog here is local-only: the lockReason store stays set
                // until a successful unlockWithPassword clears it, so biometric stays paused.
                Button(onClick = { lockReasonDismissed = true }) { Text("OK") }
            },
        )
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
            modifier = Modifier.align(Alignment.CenterHorizontally),
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
    onAuthFailed: () -> Unit = {},
): BiometricPrompt? {
    val activity = context as? FragmentActivity ?: return null
    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                onSuccess()
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                // ERROR_CANCELED fires when the host calls cancelAuthentication() — that's
                // our own programmatic dismissal (e.g. after the too-many-failures lockout
                // sets a lock reason). Treat it like the user-initiated cancels: silent.
                if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                    code != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    code != BiometricPrompt.ERROR_CANCELED
                ) {
                    onError(msg.toString())
                }
            }
            // Fires when biometric is recognized as not matching (wrong finger/face). The
            // BiometricPrompt itself shows its own "try again" feedback; we forward the
            // event so the VM can count toward the app-level too-many-failures lockout.
            override fun onAuthenticationFailed() { onAuthFailed() }
        }
    )
    BiometricPrompt.PromptInfo.Builder()
        .setTitle("Biometric Unlock")
        .setNegativeButtonText("Use master password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
        .also { prompt.authenticate(it) }
    return prompt
}
