package com.onekey.feature.auth.presentation.screen

import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel

private const val TOTAL_STEPS = 3
private const val READY_STEP = TOTAL_STEPS

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    viewModel: AuthViewModel,
    onSetupComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // Hoisted so that navigating Back → Continue between pages doesn't wipe what the user
    // already typed / accepted. AnimatedContent destroys child composables on transition,
    // so any state held inside CreateVaultPage would be lost.
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var privacyAccepted by remember { mutableStateOf(false) }

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
            // Dismiss the restore dialog (if open) so the "vault ready" page is visible underneath.
            showRestoreDialog = false
            pendingRestoreUri = null
        }
    }

    val showReadyPage = state is AuthUiState.SetupComplete

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        AnimatedContent(
            targetState = if (showReadyPage) READY_STEP else step,
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
                    password = password,
                    onPasswordChange = { password = it; viewModel.clearError() },
                    confirmPassword = confirmPassword,
                    onConfirmPasswordChange = { confirmPassword = it; viewModel.clearError() },
                    privacyAccepted = privacyAccepted,
                    onPrivacyAcceptedChange = { privacyAccepted = it },
                    onBack = {
                        step = 1
                        // Clear so a stale error doesn't reappear if the user comes back to step 2.
                        viewModel.clearError()
                    },
                    onSubmit = { viewModel.setup(password.toCharArray()) },
                    onRestoreFromBackup = {
                        viewModel.notifyPickerLaunched()
                        restoreLauncher.launch(arrayOf("*/*"))
                    },
                )
                READY_STEP -> VaultReadyPage(onContinue = onSetupComplete)
                else -> Unit
            }
        }

        if (!showReadyPage) {
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
            onConfirm = { password ->
                pendingRestoreUri?.let {
                    viewModel.restoreFromEncryptedBackup(it, password.toCharArray(), context)
                }
            },
            onDismiss = {
                showRestoreDialog = false
                pendingRestoreUri = null
                viewModel.clearError()
            },
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
        PrivacyPoint("Your master password never leaves your device — not even hashed.")
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
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    privacyAccepted: Boolean,
    onPrivacyAcceptedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onRestoreFromBackup: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showPolicyDialog by remember { mutableStateOf(false) }

    val passwordMismatch by remember {
        derivedStateOf { confirmPassword.isNotEmpty() && password != confirmPassword }
    }
    val passwordTooShort by remember { derivedStateOf { password.isNotEmpty() && password.length < 8 } }
    val canSubmit = password.length >= 8 && !passwordMismatch && privacyAccepted && state !is AuthUiState.Loading

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
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
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
            "Your password is never stored. We derive an encryption key from it using PBKDF2 (310,000 iterations). Forget the password — lose access forever.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
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
            singleLine = true,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = onConfirmPasswordChange,
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
            singleLine = true,
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

        Spacer(Modifier.height(24.dp))
    }

    if (showPolicyDialog) {
        PrivacyPolicyDialog(onDismiss = { showPolicyDialog = false })
    }
}

@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
        title = { Text("Privacy Policy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrivacyPoint("All your data stays on this device, encrypted with AES-256-GCM.")
                PrivacyPoint("No accounts, no internet connection, no cloud sync.")
                PrivacyPoint("No analytics, telemetry, or crash reports — ever.")
                PrivacyPoint("Your master password never leaves your device, not even hashed.")
                PrivacyPoint("If you forget your master password, your data cannot be recovered.")
                PrivacyPoint("Encrypted .1key backups are protected by your master password. Plain JSON or CSV exports are unencrypted — treat those files as sensitive.")
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
            "Your master password is the only key. Keep it safe — there is no recovery.",
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
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // Dismiss automatically once setup completes (LaunchedEffect handles navigation).
    // Keep dialog open while loading so the user can see the spinner.
    AlertDialog(
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
                if (state is AuthUiState.Error) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup password") },
                    singleLine = true,
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
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty() && state !is AuthUiState.Loading,
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
    val staticHint = "This takes a few seconds — we're stretching your password so it's hard to crack."
    val loadingMessages = listOf(
        "Securing your vault…",
        "Encrypting with your master password…",
        "Almost done…",
    )

    // Keyed on isLoading so a fresh loading cycle (e.g. retry after error) starts at 0
    // synchronously — without the key the previous index would briefly paint as the
    // first frame before the LaunchedEffect could reset it.
    var messageIndex by remember(isLoading) { mutableStateOf(0) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            while (true) {
                kotlinx.coroutines.delay(1200)
                if (messageIndex < loadingMessages.lastIndex) messageIndex++
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
