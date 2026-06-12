package com.onekey.feature.auth.presentation.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import com.onekey.core.presentation.lockaware.SecurePasswordFieldState
import com.onekey.core.presentation.lockaware.SecurePasswordTextField
import com.onekey.core.presentation.lockaware.rememberSecurePasswordFieldState
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.onekey.feature.secretkey.scan.QrParseResult
import com.onekey.feature.secretkey.scan.SECRET_KEY_HUMAN_PREFIX
import com.onekey.feature.secretkey.scan.formatCanonicalSkForPrint
import com.onekey.feature.secretkey.scan.parseEmergencyKitQr
import com.onekey.feature.settings.presentation.dialog.LeaveKitSaveWarningDialog
import com.onekey.feature.settings.presentation.dialog.SecretKeySkipOnboardingDialog
import com.onekey.feature.settings.presentation.viewmodel.SecretKeySettingsEvent
import com.onekey.feature.settings.presentation.viewmodel.SecretKeySettingsViewModel

// Step layout for the onboarding flow:
//   0 - Welcome
//   1 - Privacy
//   2 - Create Vault (master password)
//   3 - Secret Key ceremony (NEW)
//   4 - Vault Ready
//
// `READY_STEP` is `TOTAL_STEPS`. The challenger Issue 9 D regression note:
// users on the pre-SK code can only have step values 0/1/2 saved, and step
// 2 (CreateVault) keeps its meaning across versions, so no rememberSaveable
// migration is required when this constant moves from 3 -> 4.
private const val TOTAL_STEPS = 4
private const val READY_STEP = TOTAL_STEPS

/**
 * Pseudo-step value for the inlined Emergency Kit save gate that sits
 * between the SK ceremony commit and the Vault Ready page (blocker B5).
 * Not counted in [TOTAL_STEPS] because the StepIndicator is hidden on
 * this pseudo-step the same way it is hidden on [READY_STEP]. The
 * literal value just needs to be distinct from any real step.
 */
private const val KIT_SAVE_STEP = 100

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    viewModel: AuthViewModel,
    onSetupComplete: () -> Unit,
    /**
     * Caller-owned navigation hook the screen invokes when the user taps
     * "Scan QR" on the Secret-Key-required restore dialog. The NavGraph
     * is expected to navigate to [Screen.SecretKeyImportScanner.route];
     * the scan result lands here via the [scannedSecretKey] parameter on
     * the next composition.
     */
    onScanEmergencyKitQr: () -> Unit = {},
    /**
     * Canonical Secret Key string the SK scanner produced (or null when
     * the user did not just return from the scanner). The screen
     * forwards this value into the restore dialog's input field on a
     * one-shot basis; the caller is expected to have already removed
     * the key from its SavedStateHandle so this value is consumed
     * exactly once per round-trip.
     */
    scannedSecretKey: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // rememberSaveable so a rotation doesn't dump the user back to the welcome page
    // mid-flow. Plain `remember` only survives recomposition, not activity recreation.
    var step by rememberSaveable { mutableStateOf(0) }
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showSkipSecretKeyDialog by rememberSaveable { mutableStateOf(false) }

    // Blocker B5 gate: did the user enable Secret Key this run (vs Skip),
    // and have they acknowledged saving the Emergency Kit yet?
    //
    // - skEnabledThisFlow flips true when the user taps Save & Continue
    //   on step 3 (the SK ceremony). The Skip path leaves it false so
    //   the kit-save step is bypassed for opt-out vaults.
    // - kitSaveAcknowledged starts false and only flips true after the
    //   user has (a) successfully written the PDF to a SAF location and
    //   (b) ticked the "I have saved my Emergency Kit" checkbox.
    //
    // Both are rememberSaveable so a rotation mid-step does not lose the
    // user's progress. The composable that mounts on the kit-save step
    // installs a BackHandler so system back / IME back cannot bypass.
    var skEnabledThisFlow by rememberSaveable { mutableStateOf(false) }
    var kitSaveAcknowledgedInOnboarding by rememberSaveable { mutableStateOf(false) }

    // Hoisted to survive AnimatedContent transitions (which destroy child composables).
    // SecurePasswordFieldState uses `remember` (not `rememberSaveable`) intentionally:
    // master passwords must not persist to InstanceState / SavedStateHandle bundles.
    // Back->Continue navigation within a session preserves these; process death clears them.
    val passwordState = rememberSecurePasswordFieldState()
    val confirmPasswordState = rememberSecurePasswordFieldState()
    var privacyAccepted by rememberSaveable { mutableStateOf(false) }

    // Latched master password for the SK ceremony commit. The master password
    // field on step 2 is `remember` (not rememberSaveable) so a rotation
    // mid-ceremony cannot recover the typed bytes; we snapshot the value
    // before navigating step 2 -> step 3 and pass the snapshot to the SK
    // setup methods. The snapshot is zeroed inside the VM's setup methods.
    var pendingMasterPassword: CharArray? by remember { mutableStateOf(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        viewModel.notifyPickerDone()
        if (uri != null) {
            pendingRestoreUri = uri
            showRestoreDialog = true
            // Clear any prior error so the dialog starts fresh.
            viewModel.clearError()
        }
    }

    LaunchedEffect(state) {
        if (state is AuthUiState.SetupComplete) {
            // Hide the IME only on a successful setup - a wrong restore-backup password
            // or any in-flight error should leave the keyboard up so the user can retype
            // immediately without re-tapping the field. `clearFocus(force = true)` covers
            // hardware-keyboard / stuck-IME edge cases where `hide()` alone isn't enough.
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            // Dismiss any restore-related dialogs so the "vault ready" page is visible underneath.
            showRestoreDialog = false
            pendingRestoreUri = null
            // Drop the latched MP - the setup commit has zeroed it already,
            // but null'ing the reference releases the array for GC.
            pendingMasterPassword = null
        }
        // SK-required pivot: keep the restore dialog state aware of the pivot
        // so the dialog dismisses itself once the SK scanner takes over.
        if (state is AuthUiState.SecretKeyRequiredForRestore) {
            showRestoreDialog = false
        }
    }

    // SetupComplete fires the moment setupWithSecretKey commits the vault.
    // For SK-enabled flows we hold the user on the kit-save gate until
    // they acknowledge saving. For Skip / non-SK flows we go straight
    // through to VaultReadyPage as before.
    val setupCompleted = state is AuthUiState.SetupComplete
    val showKitSavePage = setupCompleted && skEnabledThisFlow && !kitSaveAcknowledgedInOnboarding
    val showReadyPage = setupCompleted && (!skEnabledThisFlow || kitSaveAcknowledgedInOnboarding)
    val skRequiredState = state as? AuthUiState.SecretKeyRequiredForRestore

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        AnimatedContent(
            targetState = when {
                showReadyPage -> READY_STEP
                showKitSavePage -> KIT_SAVE_STEP
                else -> step
            },
            transitionSpec = {
                val forward = targetState > initialState
                val enter = slideInHorizontally { if (forward) it else -it } + fadeIn()
                val exit = slideOutHorizontally { if (forward) -it else it } + fadeOut()
                enter togetherWith exit
            },
            modifier = Modifier.weight(1f),
            label = "onboarding_page",
        ) { currentStep ->
            when (currentStep) {
                0 -> WelcomePage(onNext = { step = 1 })
                1 -> PrivacyPage(onNext = { step = 2 }, onBack = { step = 0 })
                2 -> CreateVaultPage(
                    state = state,
                    passwordState = passwordState,
                    confirmPasswordState = confirmPasswordState,
                    privacyAccepted = privacyAccepted,
                    onPrivacyAcceptedChange = { privacyAccepted = it },
                    onBack = {
                        step = 1
                        // Clear so a stale error doesn't reappear if the user comes back to step 2.
                        viewModel.clearError()
                    },
                    onSubmit = {
                        // Snapshot the master password before navigating to
                        // the SK ceremony. confirmPasswordState is cleared
                        // immediately - we never display the password again
                        // after this point.
                        confirmPasswordState.clear()
                        pendingMasterPassword?.fill(' ')
                        pendingMasterPassword = passwordState.consume()
                        viewModel.ensurePendingSecretKey()
                        step = 3
                    },
                    onClearError = { viewModel.clearError() },
                    onRestoreFromBackup = {
                        viewModel.notifyPickerLaunched()
                        restoreLauncher.launch(arrayOf("*/*"))
                    },
                )
                3 -> SecretKeyCeremonyPage(
                    viewModel = viewModel,
                    state = state,
                    onBack = {
                        // Back drops the latched MP and the pending SK so a
                        // returning user has to retype the MP and the SK
                        // gets regenerated on next reach of this step.
                        pendingMasterPassword?.fill(' ')
                        pendingMasterPassword = null
                        viewModel.clearPendingSecretKey()
                        viewModel.clearError()
                        step = 2
                    },
                    onSaveAndContinue = {
                        val pwd = pendingMasterPassword ?: return@SecretKeyCeremonyPage
                        // Hand ownership of the snapshot to the VM; null out
                        // our local reference so a re-tap cannot resubmit.
                        pendingMasterPassword = null
                        // B5 gate: mark this run as SK-enabled BEFORE the
                        // commit so the kit-save step is reached when
                        // SetupComplete fires (which happens synchronously
                        // inside the launched coroutine - the flip must
                        // already be visible when the state observer runs).
                        skEnabledThisFlow = true
                        viewModel.setupWithSecretKey(pwd)
                    },
                    onSkip = { showSkipSecretKeyDialog = true },
                )
                KIT_SAVE_STEP -> OnboardingKitSavePage(
                    onFinish = { kitSaveAcknowledgedInOnboarding = true },
                )
                READY_STEP -> VaultReadyPage(onContinue = onSetupComplete)
                else -> Unit
            }
        }

        if (!showReadyPage && !showKitSavePage) {
            StepIndicator(
                current = step,
                total = TOTAL_STEPS,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )
        }
    }

    if (showRestoreDialog) {
        RestoreFromBackupDialog(
            state = state,
            onConfirm = { charArray ->
                pendingRestoreUri?.let {
                    viewModel.restoreFromEncryptedBackup(it, charArray, context)
                }
            },
            onClearError = { viewModel.clearError() },
            onDismiss = {
                showRestoreDialog = false
                pendingRestoreUri = null
                viewModel.clearError()
            },
        )
    }

    if (showSkipSecretKeyDialog) {
        SecretKeySkipOnboardingDialog(
            onConfirm = {
                showSkipSecretKeyDialog = false
                val pwd = pendingMasterPassword ?: return@SecretKeySkipOnboardingDialog
                pendingMasterPassword = null
                viewModel.setupSkippingSecretKey(pwd)
            },
            onDismiss = {
                showSkipSecretKeyDialog = false
            },
        )
    }

    if (skRequiredState != null) {
        RestoreSecretKeyDialog(
            backupCreatedAtMs = skRequiredState.backupCreatedAtMs,
            backupVaultVersion = skRequiredState.backupVaultVersion,
            isLoading = state is AuthUiState.Loading,
            vmError = skRequiredState.error,
            onClearVmError = { viewModel.clearSkRestoreError() },
            onCancel = { viewModel.clearPendingSecretKeyRestore() },
            onSubmit = { canonicalSk ->
                viewModel.restoreFromEncryptedBackupWithSecretKey(canonicalSk, context)
            },
            onScanQr = onScanEmergencyKitQr,
            preFilledFromScan = scannedSecretKey,
        )
    }
}

// ── Page 1: Welcome ───────────────────────────────────────────────────────────

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "1Key",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your passwords, fully under your control.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(48.dp))

        FeatureRow(
            icon = Icons.Default.Security,
            title = "Military-grade encryption",
            subtitle = "AES-256-GCM with PBKDF2 key derivation",
        )
        Spacer(Modifier.height(20.dp))
        FeatureRow(
            icon = Icons.Default.CloudOff,
            title = "100% offline",
            subtitle = "No internet required, no cloud, no accounts",
        )
        Spacer(Modifier.height(20.dp))
        FeatureRow(
            icon = Icons.Default.Fingerprint,
            title = "Biometric unlock",
            subtitle = "Fingerprint or face unlock for quick access",
        )
        Spacer(Modifier.height(20.dp))
        FeatureRow(
            icon = Icons.Default.Key,
            title = "Built-in 2FA",
            subtitle = "TOTP codes without a separate authenticator app",
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Get Started", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Page 2: Privacy & security promise ───────────────────────────────────────

@Composable
private fun PrivacyPage(onNext: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PrivacyTip,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Your privacy matters",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "1Key is built with a simple principle: we can't access your data because we never see it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(40.dp))

        PrivacyPoint("Everything stays on your device. No sync servers, no backups to the cloud.")
        Spacer(Modifier.height(16.dp))
        PrivacyPoint("Your master password never leaves your device - not even hashed.")
        Spacer(Modifier.height(16.dp))
        PrivacyPoint("No analytics, no crash reporting, no telemetry of any kind.")
        Spacer(Modifier.height(16.dp))
        PrivacyPoint("Open source. You can verify every claim we make.")

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Continue", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Page 3: Create vault ──────────────────────────────────────────────────────

@Composable
private fun CreateVaultPage(
    state: AuthUiState,
    passwordState: SecurePasswordFieldState,
    confirmPasswordState: SecurePasswordFieldState,
    privacyAccepted: Boolean,
    onPrivacyAcceptedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onClearError: () -> Unit,
    onRestoreFromBackup: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }

    val passwordMismatch = !confirmPasswordState.isEmpty && !passwordState.contentEquals(confirmPasswordState)
    val passwordTooShort = !passwordState.isEmpty && passwordState.length < 8
    val canSubmit = passwordState.length >= 8 && !passwordMismatch && privacyAccepted && state !is AuthUiState.Loading

    // Hero (intro copy) is the only thing that scrolls; the form (fields, privacy
    // controls, primary + secondary actions) is a non-scrolling sibling pinned to
    // the bottom. The outer OnboardingScreen Column already applies `.imePadding()`,
    // so the form here doesn't need its own - it sits above the keyboard naturally.
    // The single-scroll original lost the "Create Vault" button below the viewport
    // when a password field was focused.
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            Text(
                "Create your master password",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This is the only password you need to remember. If you lose it, your data cannot be recovered.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your password is never stored. We derive an encryption key from it using PBKDF2 (310,000 iterations). Forget the password - lose access forever.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SecurePasswordTextField(
                state = passwordState,
                onValueChanged = onClearError,
                label = { Text("Master Password") },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                isError = passwordTooShort,
                supportingText = {
                    if (passwordTooShort) Text("At least 8 characters required")
                },
            )

            Spacer(Modifier.height(12.dp))

            SecurePasswordTextField(
                state = confirmPasswordState,
                onValueChanged = onClearError,
                label = { Text("Confirm Password") },
                visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showConfirm = !showConfirm }) {
                        Icon(if (showConfirm) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                isError = passwordMismatch,
                supportingText = { if (passwordMismatch) Text("Passwords do not match") },
            )

            if (state is AuthUiState.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    (state as AuthUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = state !is AuthUiState.Loading) { onPrivacyAcceptedChange(!privacyAccepted) },
            ) {
                Checkbox(
                    checked = privacyAccepted,
                    onCheckedChange = onPrivacyAcceptedChange,
                    enabled = state !is AuthUiState.Loading,
                )
                Text(
                    "I've read and agree to the Privacy Policy",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = { showPolicyDialog = true },
                modifier = Modifier.align(Alignment.Start).padding(start = 4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Default.PrivacyTip, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Read the Privacy Policy", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state is AuthUiState.Loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Create Vault", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.height(12.dp))
            VaultSetupStatusLine(isLoading = state is AuthUiState.Loading)

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(
                onClick = onRestoreFromBackup,
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is AuthUiState.Loading,
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Restore from encrypted 1Key backup")
            }
        }
    }

    if (showPolicyDialog) {
        PrivacyPolicyDialog(onDismiss = { showPolicyDialog = false })
    }
}

@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    LockAwareDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
        title = { Text("Privacy Policy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrivacyPoint("All your data stays on this device, encrypted with AES-256-GCM.")
                PrivacyPoint("No accounts, no internet connection, no cloud sync.")
                PrivacyPoint("No analytics, telemetry, or crash reports - ever.")
                PrivacyPoint("Your master password never leaves your device, not even hashed.")
                PrivacyPoint("If you forget your master password, your data cannot be recovered.")
                PrivacyPoint("Encrypted .1key backups are protected by your master password. Plain JSON or CSV exports are unencrypted - treat those files as sensitive.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        },
    )
}

// ── Page 4: Vault ready ───────────────────────────────────────────────────────

@Composable
private fun VaultReadyPage(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(110.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Your vault is ready",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Your master password is the only key. Keep it safe - there is no recovery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(36.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "What's next",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                FeatureRow(
                    icon = Icons.Default.Add,
                    title = "Add your first login",
                    subtitle = "Tap the + button on the home screen",
                )
                Spacer(Modifier.height(14.dp))
                FeatureRow(
                    icon = Icons.Default.Fingerprint,
                    title = "Enable biometric unlock",
                    subtitle = "Skip the master password on daily unlocks",
                )
                Spacer(Modifier.height(14.dp))
                FeatureRow(
                    icon = Icons.Default.ImportExport,
                    title = "Import your existing passwords",
                    subtitle = "Import your 1Key encrypted backup or migrate from other credential manager apps from Settings",
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Open Vault", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Restore-from-backup dialog ────────────────────────────────────────────────

@Composable
private fun RestoreFromBackupDialog(
    state: AuthUiState,
    onConfirm: (CharArray) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    val passwordState = rememberSecurePasswordFieldState()
    var showPassword by remember { mutableStateOf(false) }
    val errorMessage = (state as? AuthUiState.Error)?.message

    // Dismiss automatically once setup completes (LaunchedEffect handles navigation).
    // Keep dialog open while loading so the user can see the spinner. A wrong
    // password lands the VM in AuthUiState.Error - we keep the dialog open and
    // surface the message in the password field's supportingText (mirrors the
    // SecretKeyRotateConfirmDialog wrong-password pattern), so the user can
    // retype without re-uploading the file.
    LockAwareDialog(
        onDismissRequest = { if (state !is AuthUiState.Loading) onDismiss() },
        icon = { Icon(Icons.Default.Restore, contentDescription = null) },
        title = { Text("Restore from Backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the master password that was used to encrypt this backup. " +
                        "It will become your new vault master password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecurePasswordTextField(
                    state = passwordState,
                    onValueChanged = {
                        if (errorMessage != null) onClearError()
                    },
                    label = { Text("Backup password") },
                    enabled = state !is AuthUiState.Loading,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passwordState.consume()) },
                enabled = !passwordState.isEmpty && state !is AuthUiState.Loading,
                // Stable lower bound so the button does not visibly shrink
                // when the "Restore" label is swapped for an 18.dp spinner
                // while Argon2id derivation runs.
                modifier = Modifier.widthIn(min = 120.dp),
            ) {
                if (state is AuthUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Restore")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = state !is AuthUiState.Loading) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun VaultSetupStatusLine(isLoading: Boolean) {
    val staticHint = "This takes a few seconds - we're stretching your password so it's hard to crack."
    val loadingMessages = listOf(
        "Securing your vault…",
        "Encrypting with your master password…",
        "Almost done…",
    )

    // Keyed on isLoading so a fresh loading cycle (e.g. retry after error) starts at 0
    // synchronously - without the key the previous index would briefly paint as the
    // first frame before the LaunchedEffect could reset it.
    var messageIndex by remember(isLoading) { mutableStateOf(0) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            // Walk to the last message, then exit. Without the bound this looped forever
            // delaying 1.2s and waking the coroutine for nothing until isLoading flipped.
            while (messageIndex < loadingMessages.lastIndex) {
                kotlinx.coroutines.delay(1200)
                messageIndex++
            }
        }
    }

    val text = if (isLoading) loadingMessages[messageIndex] else staticHint
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "vault_setup_status",
    ) { current ->
        Text(
            current,
            style = MaterialTheme.typography.bodySmall,
            color = if (isLoading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrivacyPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -- Page 4: Secret Key ceremony ----------------------------------------------

/**
 * Onboarding step 3 (zero-indexed). Shown after the master password is set,
 * before the vault commit. The VM has generated a fresh 16-byte SK via
 * `ensurePendingSecretKey`; we render the canonical printed form here and
 * give the user two paths:
 *
 *  - Save & Continue: hands the latched master password back to the VM,
 *    which calls AuthRepository.setupMasterPasswordWithSecretKey. The
 *    subsequent SetupComplete state navigates to the Vault Ready page.
 *    The Emergency Kit save prompt is owned by SettingsSecretKeyScreen
 *    flow; the onboarding ceremony does NOT render the save prompt
 *    inline (the user reaches it via Settings if they wish).
 *  - Skip Secret Key (advanced): opens SecretKeySkipOnboardingDialog
 *    for the destructive second-confirm. On confirm the VM zeroes the
 *    pending SK and runs the MP-only setup with the opted-out flag set.
 *
 * Both buttons are disabled while the underlying AuthUiState is Loading
 * (the master-password commit / verifier derive is running).
 */
@Composable
private fun SecretKeyCeremonyPage(
    viewModel: AuthViewModel,
    state: AuthUiState,
    onBack: () -> Unit,
    onSaveAndContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    // Pull the canonical SK string from the VM. The VM regenerates if and
    // only if pendingSecretKey was cleared (back navigation), so this read
    // matches the bytes that will be committed on Save & Continue.
    LaunchedEffect(Unit) {
        viewModel.ensurePendingSecretKey()
    }
    val canonical by viewModel.pendingSecretKeyCanonical.collectAsStateWithLifecycle()
    val isLoading = state is AuthUiState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Your Secret Key",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "1Key just generated a 128-bit Secret Key on this device. We will " +
                "mix it into your master password to derive the vault key, so " +
                "a stolen backup file cannot be brute-forced from the password " +
                "alone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Secret Key value",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = canonical ?: "Generating...",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "After you continue, save this value as an Emergency Kit " +
                        "from Settings > Security > Secret Key. Without it, V5 " +
                        "encrypted backups taken from this device cannot be restored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeatureRow(
                    icon = Icons.Default.Lock,
                    title = "Local-only",
                    subtitle = "Wrapped under your device's Android Keystore. Never leaves this device unless you save the kit.",
                )
                FeatureRow(
                    icon = Icons.Default.Security,
                    title = "Defeats password-only attacks",
                    subtitle = "An attacker who steals an encrypted backup needs your master password AND this Secret Key.",
                )
                FeatureRow(
                    icon = Icons.Default.Recommend,
                    title = "Default-on",
                    subtitle = "Recommended for all new vaults. You can opt out if you prefer the master-password-only model.",
                )
            }
        }

        if (state is AuthUiState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSaveAndContinue,
            enabled = !isLoading && canonical != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Save & Continue", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
        ) {
            Text("Continue without Secret Key")
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
            Text("Back")
        }

        Spacer(Modifier.height(24.dp))
    }
}

// -- Restore-from-backup Secret Key dialog ------------------------------------

/**
 * Modal that pivots the restore-from-backup flow into "scan or type the
 * Secret Key" mode. Surfaced by [OnboardingScreen] when the underlying
 * AuthUiState transitions to [AuthUiState.SecretKeyRequiredForRestore].
 *
 * Two input modes:
 *  - Manual paste of the printed form (`A3-XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX`).
 *    The text field accepts both the printed form and the bare 26-char
 *    Crockford string; [canonicalSkToBytes] normalises before decode.
 *  - QR scanner via the existing 2FA QrScannerScreen surface (deferred to
 *    a future iteration; the design reserves the slot here so the screen
 *    flow does not change when scanning lands).
 *
 * Caller contract:
 *  - [onSubmit] receives the canonical SK string (manual input is
 *    normalised inside this composable BEFORE the call).
 *  - [onCancel] zeros the in-flight password snapshot and returns to Idle.
 *  - [isLoading] true while the retry-with-SK is running; disables both
 *    buttons and shows a spinner inside Submit.
 *  - [vmError] non-null when the most recent retry failed (wrong SK,
 *    malformed input, corrupted backup). The dialog surfaces it in the
 *    SK input's supportingText so the user can retry without losing the
 *    file / password context. Cleared by [onClearVmError] as soon as the
 *    user edits the field, so the error never lingers stale.
 */
@Composable
private fun RestoreSecretKeyDialog(
    backupCreatedAtMs: Long,
    backupVaultVersion: Int,
    isLoading: Boolean,
    vmError: String?,
    onClearVmError: () -> Unit,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
    onScanQr: () -> Unit = {},
    preFilledFromScan: String? = null,
) {
    var skInput by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    // One-shot fill: when the user returns from the QR scanner the
    // caller passes the decoded 26-char canonical SK here. We render
    // it in the field using the PRINTED form ("A3-XXXXX-XXXXX-...")
    // so the user sees the same value that is on their paper kit, not
    // a bare-bones raw string. The submit handler tolerates either
    // form so this is purely a display choice. Subsequent recompositions
    // with the same scanned value are a no-op because skInput is
    // already set to the formatted version.
    LaunchedEffect(preFilledFromScan) {
        if (!preFilledFromScan.isNullOrBlank()) {
            val printed = runCatching { formatCanonicalSkForPrint(preFilledFromScan) }
                .getOrDefault(preFilledFromScan)
            if (skInput != printed) {
                skInput = printed
                localError = null
                if (vmError != null) onClearVmError()
            }
        }
    }
    // localError takes precedence over vmError so a fresh client-side
    // validation message (e.g. "expected 26 characters") replaces a prior
    // server-side error without flicker. supportingText shows whichever
    // is set.
    val effectiveError = localError ?: vmError
    val createdAtLabel = remember(backupCreatedAtMs) {
        if (backupCreatedAtMs <= 0L) "(unknown date)"
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(backupCreatedAtMs))
    }

    LockAwareDialog(
        onDismissRequest = { if (!isLoading) onCancel() },
        icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
        title = { Text("Secret Key required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This backup was created with the Secret Key feature enabled. " +
                        "Enter the Secret Key from your Emergency Kit to restore.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Backup taken $createdAtLabel, vault version $backupVaultVersion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LockAwareOutlinedTextField(
                    value = skInput,
                    onValueChange = { value ->
                        skInput = value
                        if (localError != null) localError = null
                        if (vmError != null) onClearVmError()
                    },
                    label = { Text("Secret Key (e.g. ${SECRET_KEY_HUMAN_PREFIX}XXXXX-...)") },
                    placeholder = { Text("${SECRET_KEY_HUMAN_PREFIX}XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX") },
                    singleLine = false,
                    isError = effectiveError != null,
                    supportingText = effectiveError?.let { err -> { Text(err) } },
                    enabled = !isLoading,
                    trailingIcon = {
                        // Scan-from-camera affordance lives inside the field
                        // so the SK row stays compact. Disabled while a
                        // verify is in flight so a tap during the Argon2id
                        // derive does not race the result.
                        IconButton(onClick = onScanQr, enabled = !isLoading) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan QR from Emergency Kit",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Paste the printed value from your kit, or scan its QR code. " +
                        "Dashes and the A3- prefix are optional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Accept either the raw 26-char canonical or the printed
                    // A3-XXXXX-... form. parseEmergencyKitQr expects the
                    // full URI; we are NOT decoding a QR here, just the
                    // text payload, so route through canonicalSkToBytes
                    // (which tolerates dashes / prefix) by way of a
                    // round-trip check.
                    val normalized = skInput.trim()
                        .removePrefix(SECRET_KEY_HUMAN_PREFIX)
                        .removePrefix(SECRET_KEY_HUMAN_PREFIX.lowercase())
                        .replace("-", "")
                        .uppercase()
                    if (normalized.length != 26) {
                        localError = "Expected 26 characters of the Secret Key (after stripping dashes)."
                        return@Button
                    }
                    // Quick QR-URL parse path: if the user pasted the raw
                    // `1key-emergency:?sk=...` payload instead of the
                    // printed form, accept it too. parseEmergencyKitQr
                    // returns Ok with the canonical 26 chars.
                    val asUri = parseEmergencyKitQr(skInput.trim())
                    val canonical = when (asUri) {
                        is QrParseResult.Ok -> asUri.canonicalSk
                        QrParseResult.NotEmergencyKit -> normalized
                        is QrParseResult.WrongVersion -> {
                            localError = "Emergency Kit version ${asUri.seenVer} is not supported by this build."
                            return@Button
                        }
                        QrParseResult.Malformed -> {
                            localError = "Couldn't read the Secret Key. Check the value and try again."
                            return@Button
                        }
                    }
                    onSubmit(canonical)
                },
                enabled = !isLoading && skInput.isNotBlank(),
                // Stable lower bound so the button does not shrink when
                // the "Restore" label is swapped for the 16.dp spinner.
                modifier = Modifier.widthIn(min = 120.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Restore")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isLoading) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun StepIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val isActive = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .size(if (isActive) 10.dp else 8.dp)
            )
        }
    }
}

/**
 * Inlined Emergency Kit save gate for the onboarding flow (blocker B5).
 * Sits between the SK ceremony commit and the Vault Ready page so the
 * user cannot finish onboarding without having both:
 *   (a) written the kit PDF to a SAF location, AND
 *   (b) ticked the "I have saved my Emergency Kit" checkbox.
 *
 * Why inlined here (not the existing [com.onekey.feature.settings.presentation.screen.EmergencyKitSavePromptScreen]):
 *  - That screen is a full Scaffold owned by the Settings navigation
 *    graph; routing into it from the onboarding flow would require a
 *    cross-graph navigation that the rest of onboarding does not use.
 *  - Inlining lets the BackHandler that blocks dismissal live inside
 *    the AnimatedContent slot, so a Back-during-transition cannot
 *    bypass the gate.
 *
 * Reuses [SecretKeySettingsViewModel] because by the time this page
 * shows, [AuthRepository.setupMasterPasswordWithSecretKey] has already
 * committed the SK into the Keystore wrapper, which is where that VM
 * reads it from via [SecretKeySettingsViewModel.refreshCanonicalKitPrintedForm].
 */
@Composable
private fun OnboardingKitSavePage(
    onFinish: () -> Unit,
    vm: SecretKeySettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val canonical by vm.canonicalKitPrintedForm.collectAsStateWithLifecycle()
    val isSaving by vm.isSavingKit.collectAsStateWithLifecycle()
    val lastKitDownloadAt by vm.lastKitDownloadAt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var ackSaved by rememberSaveable { mutableStateOf(false) }
    // Soft-block: when the user back-gestures without finishing the
    // gate, surface a warning dialog explaining the consequences
    // instead of silently swallowing the press. The user can either
    // return to the kit-save step (default action) or, if they
    // genuinely want out, accept the risk and proceed - which
    // advances to the Vault Ready page. The vault is already
    // committed at this point so the SK is in the Keystore; the user
    // can re-render the kit later from Settings.
    var showLeaveWarning by rememberSaveable { mutableStateOf(false) }
    val saveIncomplete = !ackSaved || lastKitDownloadAt == null

    BackHandler(enabled = saveIncomplete) {
        showLeaveWarning = true
    }

    // Refresh the printed-form SK string the moment the page mounts.
    // The VM derives it from the in-Keystore SK; reading it lazily
    // avoids holding the bytes in VM state longer than necessary.
    LaunchedEffect(Unit) {
        vm.refreshCanonicalKitPrintedForm()
    }

    LaunchedEffect(Unit) {
        vm.event.collect { event ->
            when (event) {
                SecretKeySettingsEvent.KitSaved -> {
                    snackbarHostState.showSnackbar("Emergency Kit saved")
                }
                is SecretKeySettingsEvent.KitSaveFailed -> {
                    snackbarHostState.showSnackbar(
                        event.message.ifBlank { "Could not save Emergency Kit." },
                    )
                }
                else -> Unit
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? ->
        // SAF result came back. Clear the picker-active flag so the
        // background-lock timer can resume. Match with the
        // notifyPickerLaunched call at the Save button onClick site
        // below - without that pair, the SAF round-trip would let the
        // auto-lock fire and zero the SK before the save coroutine
        // ran (causing the "stuck on Loading" / "kit not saved" bug
        // the user reported).
        vm.notifyPickerDone()
        if (uri != null) {
            vm.saveKitToUri(uri, context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Save your Emergency Kit",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "You cannot finish setup without saving the kit. Without it, " +
                    "no future device can restore your encrypted backups.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            // SK preview card. Re-uses the same shape as the ceremony
            // step so the visual continuity makes clear this is the
            // same Secret Key the user just acknowledged.
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Secret Key value",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = canonical ?: "Loading...",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    val filename = "1key-emergency-kit.pdf"
                    // Suppress auto-lock during the SAF round-trip. The
                    // system Files app foregrounds, our process goes to
                    // background, and the background-lock timer would
                    // otherwise zero the SK in the holder before the
                    // save coroutine ran. Companion notifyPickerDone()
                    // is in the saveLauncher result callback above.
                    vm.notifyPickerLaunched()
                    saveLauncher.launch(filename)
                },
                enabled = canonical != null && !isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (lastKitDownloadAt != null) "Save Emergency Kit again"
                        else "Save Emergency Kit (PDF)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Required acknowledgement checkbox. The B5 gate is the
            // logical AND of (lastKitDownloadAt != null) AND ackSaved -
            // a click-only ack without an actual save would let the
            // user breeze through; a save without a deliberate ack
            // would defeat the conscious-consent design.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ackSaved = !ackSaved }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = ackSaved,
                    onCheckedChange = { ackSaved = it },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "I have saved my Emergency Kit somewhere safe.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onFinish,
                enabled = ackSaved && lastKitDownloadAt != null && !isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showLeaveWarning) {
        LeaveKitSaveWarningDialog(
            onContinueSaving = { showLeaveWarning = false },
            onLeaveAnyway = {
                // The vault is already committed and the SK is wrapped in
                // the Keystore; "Leave anyway" advances past the gate to
                // the Vault Ready page so the user is not trapped. They
                // can re-render the kit later from Settings > Security >
                // Secret Key > Re-download Emergency Kit.
                showLeaveWarning = false
                onFinish()
            },
        )
    }
}
