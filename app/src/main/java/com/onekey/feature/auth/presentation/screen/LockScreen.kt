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
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.PasswordUnlockSection
import com.onekey.core.presentation.lockaware.PinUnlockSection
import com.onekey.core.presentation.util.rememberCanUseBiometric
import com.onekey.core.presentation.util.showBiometricPrompt
import com.onekey.core.presentation.viewmodel.AppViewModel
import com.onekey.core.security.LockReason
import com.onekey.feature.auth.presentation.viewmodel.AuthEvent
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val passwordLockoutUntilMs by viewModel.passwordLockoutUntilMs.collectAsStateWithLifecycle()
    val pinLockoutUntilMs by viewModel.pinLockoutUntilMs.collectAsStateWithLifecycle()
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
                // The remaining events fire from Settings → Change PIN flows on a different
                // AuthViewModel-NavBackStackEntry. They aren't observed here.
                else -> Unit
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
        // `state is Idle` keeps this effect single-shot at screen-entry time. Without it,
        // a successful master-password unlock clears lockReason, biometricUnlockGate
        // re-emits, and the effect re-fires DURING the unlock animation — opening the
        // biometric prompt on top of the user already heading to the vault.
        if (!autoTriggeredBiometric &&
            state is AuthUiState.Idle &&
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
            ),
    ) {
        val density = LocalDensity.current
        val horizontalPadding = if (maxWidth < 400.dp) 24.dp else 32.dp
        val topSpacing = (maxHeight * 0.13f).coerceIn(44.dp, 120.dp)
        val exitTravelPx = remember(maxHeight, density) { with(density) { maxHeight.toPx() * 1.08f } }
        val exitProgress = lockScreenExitProgress.value

        // Two-section split so the keyboard never hides the unlock button:
        //   - Hero (logo / title / subtitle): scrollable, takes remaining height.
        //   - Form (banner / unlock controls / error / biometric fallback): pinned
        //     to the bottom with `.imePadding()`, so it stays above the keyboard
        //     when shown and above the navigation bar otherwise. The previous
        //     layout had the whole screen in one verticalScroll with imePadding
        //     on the outer Box; bringing the focused TextField into view scrolled
        //     the unlock button below the visible viewport.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -exitTravelPx * exitProgress
                    alpha = 1f - (0.24f * exitProgress)
                },
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
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
            }

            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = horizontalPadding, vertical = 16.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                if (mustUsePassword) {
                    if (requiresMasterPasswordRecheck) MasterPasswordRecheckBanner()
                    Spacer(Modifier.height(14.dp))
                    PasswordUnlockSection(
                        state = state,
                        lockoutUntilMs = passwordLockoutUntilMs,
                        onPasswordSubmit = { viewModel.unlockWithPassword(it) },
                    )
                } else if (isPinSetup && !forcePasswordFallback) {
                    PinUnlockSection(
                        state = state,
                        lockoutUntilMs = pinLockoutUntilMs,
                        onPinSubmit = { pin -> viewModel.unlockWithPin(pin.toCharArray()) },
                        onFallbackToPassword = {
                            forcePasswordFallback = true
                            viewModel.clearError()
                        },
                    )
                } else {
                    PasswordUnlockSection(
                        state = state,
                        lockoutUntilMs = passwordLockoutUntilMs,
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
        LockAwareDialog(
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

