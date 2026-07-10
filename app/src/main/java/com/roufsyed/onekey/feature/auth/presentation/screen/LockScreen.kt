package com.roufsyed.onekey.feature.auth.presentation.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.roufsyed.onekey.core.presentation.animation.PremiumMorphEasing
import com.roufsyed.onekey.core.presentation.animation.UnlockTransitionPhase
import com.roufsyed.onekey.core.presentation.animation.UnlockTransitionTimings
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.core.presentation.lockaware.LockLogoSection
import com.roufsyed.onekey.core.presentation.lockaware.PasswordUnlockSection
import com.roufsyed.onekey.core.presentation.lockaware.PinUnlockSection
import com.roufsyed.onekey.core.presentation.util.rememberCanUseBiometric
import com.roufsyed.onekey.core.presentation.util.showBiometricPrompt
import com.roufsyed.onekey.core.presentation.viewmodel.AppViewModel
import com.roufsyed.onekey.core.security.LockReason
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthEvent
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Same-frame jitter absorber applied AFTER the auto-trigger observes window
 * focus returned. Lets any pending main-looper messages already enqueued
 * before focus-gained drain before we call `authenticate()`, so the prompt
 * mounts on a stable composition frame.
 *
 * NOTE: a pure-time `delay(N)` is NOT sufficient as a primary gate. `delay`
 * shares the same main-thread message queue as `onPause`, so under load
 * (a power-press relock blocks main for hundreds of milliseconds while the
 * synchronous snapshot hook + downstream observers run) the delay can
 * complete while `onPause` is still queued and the activity still reads as
 * RESUMED. The window-focus signal observed via `OnWindowFocusChangeListener`
 * empirically lands before `onPause` on a power-press; combining it with
 * `repeatOnLifecycle(RESUMED)` and a re-check after the settle gives us
 * defense in depth.
 */
private const val POST_FOCUS_SETTLE_MS = 50L

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
    // Atomic snapshot of (biometric enabled, lock reason set) - read together from the same
    // DataStore Preferences object so the auto-trigger never fires in the brief cold-start
    // window where one of the two flat flows has updated and the other hasn't.
    val biometricUnlockGate by viewModel.biometricUnlockGate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lockScreenExitProgress = remember { Animatable(0f) }

    // While a lock reason is active the user must enter their master password - biometric
    // and PIN are both held back until they prove possession that way. The store-backed
    // `lockReason` only clears after a successful unlockWithPassword.
    val biometricBlockedByLockReason = lockReason != null
    // Local dialog dismissal so OK closes the dialog without releasing the biometric block.
    var lockReasonDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lockReason) { if (lockReason == null) lockReasonDismissed = false }

    // Local fallback flag - flipped by the "Forgot PIN? Use master password" button or
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

    // Single-shot biometric auto-trigger flag.
    //
    // `rememberSaveable` so a rotation / configuration change mid-LockScreen does
    // NOT reset the flag and re-fire the prompt while the user is already typing
    // the master password. The companion ON_PAUSE observer below resets the flag
    // ONLY when the activity is not changing configurations (real backgrounding,
    // screen-off, navigation away) so the next ON_RESUME re-fires cleanly.
    //
    // The flag also blocks a successful master-password unlock from re-firing
    // biometric on top of the unlock animation: state leaves Idle on unlock
    // success, but even if the effect re-ran on the next ON_RESUME, the
    // `state is AuthUiState.Idle` gate inside the effect would reject it.
    var autoTriggeredBiometric by rememberSaveable { mutableStateOf(false) }
    // Held so we can dismiss the BiometricPrompt programmatically - specifically when the
    // too-many-failures lockout fires mid-prompt. Without this, the prompt stays on screen
    // accepting attempts the app would refuse anyway. Not saveable: a rotation rebuilds
    // the prompt naturally via the auto-trigger effect.
    var activeBiometricPrompt by remember { mutableStateOf<BiometricPrompt?>(null) }

    // Window-focus gate: a redundant guard on top of `repeatOnLifecycle(RESUMED)`
    // that catches power-press races the lifecycle gate alone misses.
    //
    // Mechanism: both `MSG_WINDOW_FOCUS_CHANGED` and `onPause` ride the same
    // main looper. In practice the focus-loss dispatch lands before `onPause`
    // does on a power-press, so observing `hasWindowFocus == false` is an
    // earlier signal that "the user has stopped looking at this window"
    // than the lifecycle's `RESUMED -> STARTED` transition. We use it as a
    // belt-and-braces gate; the two gates together survive heavy main-thread
    // load (the lock() path can block main for hundreds of milliseconds while
    // the synchronous snapshot clear runs).
    //
    // Re-read `view.hasWindowFocus()` immediately after attaching the
    // listener so the initial state catches up if focus was lost between
    // the `remember` snapshot and the attach.
    val view = LocalView.current

    // Pin the host Activity to portrait while the lock screen is mounted -
    // matches 1Password and avoids the unlock surface ever rendering in
    // landscape. Restore the prior orientation on dispose so post-unlock
    // screens remain free to rotate per the device setting.
    DisposableEffect(view) {
        val activity = view.context as? Activity
        val previousOrientation =
            activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = previousOrientation
        }
    }

    var hasWindowFocus by remember { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            hasWindowFocus = focused
        }
        val attachedObserver = view.viewTreeObserver
        attachedObserver.addOnWindowFocusChangeListener(listener)
        hasWindowFocus = view.hasWindowFocus()
        onDispose {
            // Use the observer we attached to. If it has been killed (merged
            // into a parent on view detach), fall back to the View's current
            // ViewTreeObserver and swallow the documented IllegalStateException
            // a dead observer raises on removal.
            val target = if (attachedObserver.isAlive) attachedObserver else view.viewTreeObserver
            try {
                target.removeOnWindowFocusChangeListener(listener)
            } catch (_: IllegalStateException) {
                // Observer was killed between the isAlive check and the call.
                // The listener is unreachable anyway.
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Reset the single-shot flag (and drop any stale prompt reference) on real
    // background/foreground transitions, but NOT on configuration changes. The
    // bug this fixes: pressing power on the unlocked vault relocks via
    // ScreenOffLockReceiver, routes to LockScreen, and the previous state-keyed
    // LaunchedEffect fired showBiometricPrompt() while the activity was in
    // ON_PAUSE -> ON_STOP. The system cancelled that mid-build prompt, but the
    // `rememberSaveable` flag stayed `true`, so on screen-wake no auto-trigger
    // re-fired and the user had to tap "Use biometric" manually.
    //
    // The `isChangingConfigurations` guard preserves the rotation invariant: a
    // user who is mid-typing during a rotation does not get a fresh auto-fire
    // (the flag survives the configuration change unchanged).
    //
    // Order is intentional: cancel the prompt, then null the reference, then
    // clear the flag. A re-entrant emission on the same frame must not observe
    // a non-null stale prompt with a cleared flag.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val isConfigChange = (context as? Activity)?.isChangingConfigurations == true
                if (!isConfigChange) {
                    activeBiometricPrompt?.cancelAuthentication()
                    activeBiometricPrompt = null
                    autoTriggeredBiometric = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(lockReason) {
        if (lockReason != null) {
            activeBiometricPrompt?.cancelAuthentication()
            activeBiometricPrompt = null
        }
    }
    // Auto-trigger biometric exactly once per surface entry.
    //
    // Two gates compose into the trigger:
    //   1. `repeatOnLifecycle(RESUMED)` cancels the coroutine on ON_PAUSE.
    //   2. `snapshotFlow { hasWindowFocus }.filter { it }.first()` suspends
    //      until our window actually has user focus.
    // Each gate alone is insufficient: a lifecycle-only gate races onPause
    // dispatch (the same main-looper queue our lock() path can saturate),
    // and a focus-only check would have a small read-vs-listener window. The
    // combination catches the power-press race that the previous time-based
    // delay missed under heavy main-thread load.
    //
    // The settle delay after focus is observed absorbs same-frame jitter so
    // we mount on a stable frame. After the settle, the gate re-checks
    // `hasWindowFocus`: between `first()` and the gate body the focus could
    // have flipped back to false (e.g. focus arrived briefly, then a
    // queued focus-loss message ran during the settle). Without the
    // re-check, that race would let `authenticate()` fire on an unfocused
    // window.
    //
    // The `autoTriggeredBiometric = true` latch is set AFTER
    // `showBiometricPrompt` returns. If the helper throws or a future gate
    // causes a non-fire, the latch stays unset so the next ON_RESUME cycle
    // can retry. `onAuthFailed` keeps the prompt mounted for retry, so we
    // intentionally do not null `activeBiometricPrompt` there.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { hasWindowFocus }.filter { it }.first()
            delay(POST_FOCUS_SETTLE_MS)
            if (!autoTriggeredBiometric &&
                hasWindowFocus &&
                state is AuthUiState.Idle &&
                biometricUnlockGate.biometricEnabled &&
                !biometricUnlockGate.lockReasonSet &&
                canUseBiometric &&
                !requiresMasterPasswordRecheck
            ) {
                val prompt = showBiometricPrompt(
                    context = context,
                    onSuccess = {
                        activeBiometricPrompt = null
                        viewModel.unlockWithBiometric()
                    },
                    onError = { msg ->
                        activeBiometricPrompt = null
                        viewModel.setBiometricError(msg)
                    },
                    onAuthFailed = { viewModel.recordBiometricFailure() },
                )
                activeBiometricPrompt = prompt
                autoTriggeredBiometric = true
            }
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
                LockLogoSection(
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
                    "Enter your master password to confirm it's really you - " +
                    "biometric will work as usual on your next unlock."
            LockReason.TooManyFailedPinAttempts ->
                "Three wrong PIN attempts. That could mean someone other than you was " +
                    "trying to get in, so we've paused biometric unlock to keep your data safe. " +
                    "Enter your master password to confirm it's really you - biometric will " +
                    "work as usual on your next unlock."
            LockReason.TooManyFailedBiometricAttempts ->
                "Three failed biometric attempts. That could mean someone other than you " +
                    "was trying to get in, so we've paused biometric unlock to keep your data " +
                    "safe. Enter your master password to confirm it's really you - biometric " +
                    "will work as usual on your next unlock."
            null -> ""
        }
        LockAwareDialog(
            onDismissRequest = { /* non-dismissible - user must acknowledge */ },
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
                "Periodic security check - please verify with your master password",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This check ensures only you - not just someone with your unlocked phone - can access the vault.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

