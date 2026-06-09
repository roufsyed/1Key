package com.onekey.feature.auth.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import com.onekey.core.presentation.lockaware.SecurePasswordTextField
import com.onekey.core.presentation.lockaware.rememberSecurePasswordFieldState
import com.onekey.feature.auth.presentation.viewmodel.AuthEvent
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.delay

private const val STEP_VERIFY_CURRENT = 0
private const val STEP_ENTER_NEW = 1
private const val STEP_CONFIRM_NEW = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupPinScreen(
    viewModel: AuthViewModel,
    onPinSet: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPinAlreadySetup by viewModel.isPinSetup.collectAsStateWithLifecycle()

    // Initial step decided once based on whether a PIN is already set. SetupPinScreen is
    // entered from Settings → Security where viewModel.isPinSetup has been collected long
    // enough to be authoritative; rememberSaveable freezes the choice across rotations.
    var step by rememberSaveable {
        mutableStateOf(if (viewModel.isPinSetup.value) STEP_VERIFY_CURRENT else STEP_ENTER_NEW)
    }

    var currentPinInput by rememberSaveable { mutableStateOf("") }
    var newPinInput by rememberSaveable { mutableStateOf("") }
    var confirmPinInput by rememberSaveable { mutableStateOf("") }

    // Set when the user uses the Forgot-PIN escape hatch - back-navigation from STEP_ENTER_NEW
    // exits the screen rather than returning to STEP_VERIFY_CURRENT (no point re-asking
    // for the current PIN once master-password proof has bypassed it).
    var bypassedCurrentPin by rememberSaveable { mutableStateOf(false) }
    var currentPinError by rememberSaveable { mutableStateOf<String?>(null) }
    // Soft cap: 3 wrong current PINs disables the field but doesn't lock the vault. The
    // user is already inside an unlocked vault - full lockout would be UX-hostile.
    var currentPinExhausted by rememberSaveable { mutableStateOf(false) }
    var showMismatch by rememberSaveable { mutableStateOf(false) }

    var showForgotPinDialog by rememberSaveable { mutableStateOf(false) }
    // SecurePasswordFieldState rather than rememberSaveable String - reduces the window during
    // which the master-password String lives on the JVM heap. Does not survive rotation
    // (intentional: passwords should not persist in InstanceState).
    val forgotPinPasswordState = rememberSecurePasswordFieldState()
    var forgotPinPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var forgotPinPasswordError by rememberSaveable { mutableStateOf(false) }
    var forgotPinAttemptsRemaining by rememberSaveable { mutableIntStateOf(3) }

    LaunchedEffect(state) {
        if (state is AuthUiState.SetupComplete) {
            viewModel.clearError()
            onPinSet()
        }
    }

    LaunchedEffect(showMismatch) {
        if (showMismatch) {
            delay(1_500)
            confirmPinInput = ""
            showMismatch = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AuthEvent.CurrentPinVerified -> {
                    currentPinInput = ""
                    currentPinError = null
                    step = STEP_ENTER_NEW
                }
                is AuthEvent.CurrentPinFailed -> {
                    currentPinInput = ""
                    currentPinError = if (event.remaining == 1)
                        "Wrong PIN - 1 attempt remaining."
                    else
                        "Wrong PIN - ${event.remaining} attempts remaining."
                }
                AuthEvent.CurrentPinExhausted -> {
                    currentPinInput = ""
                    currentPinExhausted = true
                    currentPinError = "Too many wrong attempts. Use 'Forgot PIN?' below to reset."
                }
                AuthEvent.MasterPasswordVerifiedForPinChange -> {
                    showForgotPinDialog = false
                    forgotPinPasswordState.clear()
                    forgotPinPasswordVisible = false
                    forgotPinPasswordError = false
                    forgotPinAttemptsRemaining = 3
                    bypassedCurrentPin = true
                    currentPinInput = ""
                    currentPinError = null
                    currentPinExhausted = false
                    step = STEP_ENTER_NEW
                }
                is AuthEvent.PinChangeMasterPasswordFailed -> {
                    forgotPinPasswordError = true
                    forgotPinAttemptsRemaining = event.remaining
                }
                AuthEvent.PinChangeVaultLocked -> {
                    showForgotPinDialog = false
                    forgotPinPasswordState.clear()
                    forgotPinPasswordVisible = false
                    forgotPinPasswordError = false
                    forgotPinAttemptsRemaining = 3
                    // The "Vault Locked" explanation is handled on LockScreen via
                    // LockReasonStore - the auto-lock observer routes us there.
                }
                else -> Unit
            }
        }
    }

    val screenTitle = when (step) {
        STEP_CONFIRM_NEW -> "Confirm PIN"
        else -> if (isPinAlreadySetup) "Change PIN" else "Set PIN"
    }
    val stepBody = when (step) {
        STEP_VERIFY_CURRENT -> "Enter your current 6-digit PIN to continue"
        STEP_ENTER_NEW -> if (isPinAlreadySetup) "Enter a new 6-digit PIN" else "Enter a 6-digit PIN"
        else -> "Confirm your new 6-digit PIN"
    }
    val stepDescription = when (step) {
        STEP_VERIFY_CURRENT ->
            "Verify your current PIN before changing it. The digits are checked locally."
        STEP_ENTER_NEW ->
            if (isPinAlreadySetup)
                "Your existing PIN will be replaced with this new one. The PIN digits themselves are never saved."
            else
                "Your PIN unlocks access to the vault key stored in Android KeyStore. The PIN digits themselves are never saved."
        else -> "Re-enter the new PIN to confirm. They must match exactly."
    }

    val pinForCurrentStep = when (step) {
        STEP_VERIFY_CURRENT -> currentPinInput
        STEP_ENTER_NEW -> newPinInput
        else -> confirmPinInput
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text(screenTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            step == STEP_CONFIRM_NEW -> {
                                step = STEP_ENTER_NEW
                                confirmPinInput = ""
                                showMismatch = false
                            }
                            step == STEP_ENTER_NEW && isPinAlreadySetup && !bypassedCurrentPin -> {
                                step = STEP_VERIFY_CURRENT
                                newPinInput = ""
                                currentPinInput = ""
                                currentPinError = null
                            }
                            else -> onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stepBody,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stepDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { index ->
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (index < pinForCurrentStep.length) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(14.dp),
                    ) {}
                }
            }
            Spacer(Modifier.height(24.dp))

            val pinFieldEnabled = state !is AuthUiState.Loading &&
                !(step == STEP_VERIFY_CURRENT && currentPinExhausted)
            LockAwareOutlinedTextField(
                value = pinForCurrentStep,
                onValueChange = { new ->
                    if (new.length <= 6 && new.all { it.isDigit() }) {
                        when (step) {
                            STEP_VERIFY_CURRENT -> {
                                currentPinInput = new
                                if (currentPinError != null) currentPinError = null
                                if (new.length == 6 && !currentPinExhausted) {
                                    viewModel.verifyCurrentPin(new.toCharArray())
                                }
                            }
                            STEP_ENTER_NEW -> {
                                newPinInput = new
                                if (new.length == 6) step = STEP_CONFIRM_NEW
                            }
                            STEP_CONFIRM_NEW -> {
                                confirmPinInput = new
                                if (new.length == 6) {
                                    if (newPinInput == new) {
                                        viewModel.setupPin(new.toCharArray())
                                    } else {
                                        showMismatch = true
                                    }
                                }
                            }
                        }
                    }
                },
                label = { Text("PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(200.dp),
                enabled = pinFieldEnabled,
                singleLine = true,
                isError = step == STEP_VERIFY_CURRENT && currentPinError != null,
            )

            if (step == STEP_VERIFY_CURRENT && currentPinError != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    currentPinError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

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

            // Forgot-PIN escape hatch only on the verify-current step. It's not useful
            // anywhere else: if the user reached step 1 or 2, they've either already
            // verified the current PIN or come in on a fresh PIN setup.
            if (step == STEP_VERIFY_CURRENT) {
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = { showForgotPinDialog = true }) {
                    Text("Forgot PIN? Reset using master password")
                }
            }
        }
    }

    if (showForgotPinDialog) {
        val dismissDialog = {
            showForgotPinDialog = false
            // forgotPinPasswordState is cleared by SecurePasswordTextField's DisposableEffect
            // on unmount, but we reset the other dialog state explicitly here.
            forgotPinPasswordVisible = false
            forgotPinPasswordError = false
            forgotPinAttemptsRemaining = 3
        }
        LockAwareDialog(
            onDismissRequest = dismissDialog,
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            title = { Text("Use master password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter your master password to bypass the current-PIN check. " +
                            "Once verified you can set a new PIN - the existing one will be replaced.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Master password is verified locally and never stored or transmitted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecurePasswordTextField(
                        state = forgotPinPasswordState,
                        onValueChanged = { if (forgotPinPasswordError) forgotPinPasswordError = false },
                        label = { Text("Master password") },
                        isError = forgotPinPasswordError,
                        supportingText = if (forgotPinPasswordError) {
                            {
                                val remaining = forgotPinAttemptsRemaining
                                Text(
                                    if (remaining == 1) "Incorrect password - 1 attempt remaining before vault locks."
                                    else "Incorrect password - $remaining attempts remaining."
                                )
                            }
                        } else null,
                        visualTransformation = if (forgotPinPasswordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { forgotPinPasswordVisible = !forgotPinPasswordVisible }) {
                                Icon(
                                    if (forgotPinPasswordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (forgotPinPasswordVisible) "Hide password" else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.verifyMasterPasswordForPinChange(forgotPinPasswordState.consume())
                    },
                    enabled = !forgotPinPasswordState.isEmpty && state !is AuthUiState.Loading,
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = dismissDialog) { Text("Cancel") }
            },
        )
    }
}
