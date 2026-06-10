package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.onekey.core.presentation.util.oneKeyTopBarColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.util.rememberCanUseBiometric
import com.onekey.core.domain.model.BackgroundLockTimeout
import com.onekey.core.domain.model.InactivityLockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.SecurePasswordTextField
import com.onekey.core.presentation.lockaware.rememberSecurePasswordFieldState
import com.onekey.core.security.HardwareKeyIsolationTier
import com.onekey.feature.settings.presentation.viewmodel.SettingsEvent
import com.onekey.feature.settings.presentation.viewmodel.SettingsHighlightKeys
import com.onekey.feature.settings.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSecurityScreen(
    onBack: () -> Unit,
    onSetupPin: () -> Unit,
    onChangePassword: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val isBiometricEnabled by settingsVm.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinSetup by settingsVm.isPinSetup.collectAsStateWithLifecycle()
    val isScreenshotsEnabled by settingsVm.isScreenshotsEnabled.collectAsStateWithLifecycle()
    val backgroundLockTimeout by settingsVm.backgroundLockTimeout.collectAsStateWithLifecycle()
    val inactivityLockTimeout by settingsVm.inactivityLockTimeout.collectAsStateWithLifecycle()
    val isMasterPasswordRecheckEnabled by settingsVm.isMasterPasswordRecheckEnabled.collectAsStateWithLifecycle()
    val masterPasswordRecheckInterval by settingsVm.masterPasswordRecheckInterval.collectAsStateWithLifecycle()
    val isRestoreLastScreenOnUnlock by settingsVm.isRestoreLastScreenOnUnlock.collectAsStateWithLifecycle()
    val hwIsolation by settingsVm.hardwareKeyIsolation.collectAsStateWithLifecycle()

    val canUseBiometric = rememberCanUseBiometric()
    val highlightKey by settingsVm.highlightKey.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { settingsVm.clearHighlight() }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showBiometricConfirmDialog by remember { mutableStateOf(false) }
    val biometricPasswordState = rememberSecurePasswordFieldState()
    var biometricPasswordVisible by remember { mutableStateOf(false) }
    var biometricPasswordError by remember { mutableStateOf(false) }
    var biometricAttemptsRemaining by remember { mutableIntStateOf(3) }

    var showRemovePinDialog by remember { mutableStateOf(false) }
    val removePinPasswordState = rememberSecurePasswordFieldState()
    var removePinPasswordVisible by remember { mutableStateOf(false) }
    var removePinPasswordError by remember { mutableStateOf(false) }
    var removePinAttemptsRemaining by remember { mutableIntStateOf(3) }
    var showScreenshotDialog by remember { mutableStateOf(false) }
    var pendingScreenshotsEnabled by remember { mutableStateOf(true) }
    var showBackgroundLockDialog by remember { mutableStateOf(false) }
    var showInactivityLockDialog by remember { mutableStateOf(false) }
    var showRestoreLastScreenDialog by remember { mutableStateOf(false) }
    var showHwIsolationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settingsVm.event.collect { event ->
            when (event) {
                SettingsEvent.PinRemoved -> {
                    showRemovePinDialog = false
                    removePinPasswordVisible = false
                    removePinPasswordError = false
                    removePinAttemptsRemaining = 3
                    snackbarHostState.showSnackbar("PIN removed - master password is now required to unlock")
                }
                is SettingsEvent.PinRemoveConfirmFailed -> {
                    removePinPasswordError = true
                    removePinAttemptsRemaining = event.attemptsRemaining
                }
                is SettingsEvent.Error -> snackbarHostState.showSnackbar(event.message)
                SettingsEvent.BiometricEnabled -> {
                    showBiometricConfirmDialog = false
                    biometricPasswordVisible = false
                    biometricPasswordError = false
                    biometricAttemptsRemaining = 3
                }
                is SettingsEvent.BiometricConfirmFailed -> {
                    biometricPasswordError = true
                    biometricAttemptsRemaining = event.attemptsRemaining
                }
                SettingsEvent.VaultLocked -> {
                    showBiometricConfirmDialog = false
                    biometricPasswordState.clear()
                    biometricPasswordVisible = false
                    biometricPasswordError = false
                    biometricAttemptsRemaining = 3
                    showRemovePinDialog = false
                    removePinPasswordState.clear()
                    removePinPasswordVisible = false
                    removePinPasswordError = false
                    removePinAttemptsRemaining = 3
                    // The "Vault Locked" explanation lives on LockScreen via LockReasonStore
                    // - Settings has already left composition by the time the user lands there.
                }
                else -> Unit // VaultContentsDeleted, Seed events, DeleteVaultConfirmFailed handled elsewhere
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Security") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Unlock methods")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.PIN_SETUP,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Setup / Change PIN") },
                            supportingContent = { Text("Faster unlock with a 6-digit PIN") },
                            leadingContent = { Icon(Icons.Default.Lock, null) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable(onClick = onSetupPin),
                        )
                    }
                    if (canUseBiometric) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        HighlightableRow(
                            isHighlighted = highlightKey == SettingsHighlightKeys.BIOMETRIC_UNLOCK,
                            onHighlightConsumed = settingsVm::clearHighlight,
                        ) {
                            ListItem(
                                headlineContent = { Text("Biometric Unlock") },
                                supportingContent = { Text("Biometric data never leaves the device's secure hardware. 1Key only receives a yes/no result.") },
                                leadingContent = {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = isBiometricEnabled,
                                        onCheckedChange = { enabled ->
                                            if (enabled) {
                                                biometricPasswordError = false
                                                showBiometricConfirmDialog = true
                                            } else {
                                                settingsVm.setBiometricEnabled(false)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                    if (isPinSetup) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        HighlightableRow(
                            isHighlighted = highlightKey == SettingsHighlightKeys.REMOVE_PIN,
                            onHighlightConsumed = settingsVm::clearHighlight,
                        ) {
                            ListItem(
                                headlineContent = { Text("Remove PIN") },
                                supportingContent = { Text("Stop using a PIN - only your master password will unlock 1Key") },
                                leadingContent = { Icon(Icons.Default.LockReset, null) },
                                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                                modifier = Modifier.clickable {
                                    removePinPasswordVisible = false
                                    removePinPasswordError = false
                                    showRemovePinDialog = true
                                },
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Change Master Password") },
                        supportingContent = { Text("Update your vault master password") },
                        leadingContent = { Icon(Icons.Default.Key, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable(onClick = onChangePassword),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Auto-lock")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.BACKGROUND_LOCK,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Lock when app in background") },
                            supportingContent = { Text(backgroundLockTimeout.displayName) },
                            leadingContent = { Icon(Icons.Default.Timer, contentDescription = null) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { showBackgroundLockDialog = true },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.INACTIVITY_LOCK,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Lock after inactivity") },
                            supportingContent = { Text(inactivityLockTimeout.displayName) },
                            leadingContent = { Icon(Icons.Default.HourglassEmpty, contentDescription = null) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { showInactivityLockDialog = true },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.RESTORE_LAST_SCREEN,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Pick up where you left off") },
                            supportingContent = {
                                Text(
                                    if (isRestoreLastScreenOnUnlock)
                                        "After auto-lock, unlocking takes you back to the screen you were on."
                                    else
                                        "After auto-lock, unlocking always takes you to the home screen."
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Restore, contentDescription = null) },
                            trailingContent = {
                                Switch(
                                    checked = isRestoreLastScreenOnUnlock,
                                    onCheckedChange = { newValue ->
                                        if (newValue) {
                                            // Confirm before turning on - surface the trade-off.
                                            showRestoreLastScreenDialog = true
                                        } else {
                                            settingsVm.setRestoreLastScreenOnUnlock(false)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Periodic master password check")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.MASTER_PASSWORD_RECHECK,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Periodic master password check") },
                            supportingContent = {
                                Text(
                                    if (isMasterPasswordRecheckEnabled)
                                        "Master password required every ${masterPasswordRecheckInterval.label}"
                                    else
                                        "Biometric and PIN can be used indefinitely"
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                            trailingContent = {
                                Switch(
                                    checked = isMasterPasswordRecheckEnabled,
                                    onCheckedChange = { settingsVm.setMasterPasswordRecheckEnabled(it) },
                                )
                            },
                        )
                    }
                    if (isMasterPasswordRecheckEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Recheck interval",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            MasterPasswordInterval.entries.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { settingsVm.setMasterPasswordRecheckInterval(option) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    RadioButton(
                                        selected = masterPasswordRecheckInterval == option,
                                        onClick = { settingsVm.setMasterPasswordRecheckInterval(option) },
                                    )
                                    Column {
                                        Text(
                                            option.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (option == MasterPasswordInterval.HOURS_48) {
                                            Text(
                                                "Default - recommended",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Screen capture")
            Card(modifier = Modifier.fillMaxWidth()) {
                HighlightableRow(
                    isHighlighted = highlightKey == SettingsHighlightKeys.ALLOW_SCREENSHOTS,
                    onHighlightConsumed = settingsVm::clearHighlight,
                ) {
                    ListItem(
                        headlineContent = { Text("Allow Screenshots") },
                        supportingContent = {
                            Text(
                                if (isScreenshotsEnabled) "App visible in Recent Apps screen - screenshots and recordings enabled"
                                else "Blocks screenshots, screen recordings, and Recent Apps preview"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Screenshot, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isScreenshotsEnabled,
                                onCheckedChange = { newValue ->
                                    pendingScreenshotsEnabled = newValue
                                    showScreenshotDialog = true
                                },
                            )
                        },
                    )
                }
            }

            // Hardware key isolation status row. Read-only; tap to open an
            // explainer dialog. The probe behind the StateFlow runs once per
            // process on a background coroutine, so the first frame may show
            // "Checking..." for a short window before the resolved tier
            // appears. STRONGBOX and TEE both get the checkmark - the
            // requirement frames TEE as full-strength protection. Only the
            // SOFTWARE fallback surfaces the warning icon.
            Spacer(Modifier.height(8.dp))
            SectionHeader("Hardware key isolation")
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Hardware key isolation") },
                    supportingContent = {
                        Text(
                            when (hwIsolation?.tier) {
                                HardwareKeyIsolationTier.STRONGBOX ->
                                    "Hardware-isolated (StrongBox)"
                                HardwareKeyIsolationTier.TEE ->
                                    "Hardware-backed (TEE)"
                                HardwareKeyIsolationTier.SOFTWARE ->
                                    "Software fallback (no hardware support)"
                                null -> "Checking..."
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingContent = {
                        when (hwIsolation?.tier) {
                            HardwareKeyIsolationTier.STRONGBOX,
                            HardwareKeyIsolationTier.TEE ->
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Secure hardware",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            HardwareKeyIsolationTier.SOFTWARE ->
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Software fallback",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            null ->
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        }
                    },
                    modifier = Modifier.clickable { showHwIsolationDialog = true },
                )
            }
        }
    }

    if (showBiometricConfirmDialog) {
        LockAwareDialog(
            onDismissRequest = {
                showBiometricConfirmDialog = false
                biometricPasswordVisible = false
                biometricPasswordError = false
                biometricAttemptsRemaining = 3
            },
            icon = { Icon(Icons.Default.Fingerprint, contentDescription = null) },
            title = { Text("Confirm Master Password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Biometric unlock gives the same full access to your vault as your master password does.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "To make sure only you can enable it, please enter your master password once. " +
                            "It is verified locally and never stored or transmitted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecurePasswordTextField(
                        state = biometricPasswordState,
                        onValueChanged = { if (biometricPasswordError) biometricPasswordError = false },
                        label = { Text("Master password") },
                        isError = biometricPasswordError,
                        supportingText = if (biometricPasswordError) {
                            {
                                val remaining = biometricAttemptsRemaining
                                Text(
                                    if (remaining == 1) "Incorrect password - 1 attempt remaining before vault locks."
                                    else "Incorrect password - $remaining attempts remaining."
                                )
                            }
                        } else null,
                        visualTransformation = if (biometricPasswordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { biometricPasswordVisible = !biometricPasswordVisible }) {
                                Icon(
                                    if (biometricPasswordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (biometricPasswordVisible) "Hide password" else "Show password",
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
                        settingsVm.enableBiometricWithVerification(biometricPasswordState.consume())
                    },
                    enabled = !biometricPasswordState.isEmpty,
                ) { Text("Enable Biometric") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBiometricConfirmDialog = false
                        biometricPasswordVisible = false
                        biometricPasswordError = false
                        biometricAttemptsRemaining = 3
                    }
                ) { Text("Cancel") }
            },
        )
    }

    if (showScreenshotDialog) {
        val enabling = pendingScreenshotsEnabled
        LockAwareDialog(
            onDismissRequest = { showScreenshotDialog = false },
            icon = { Icon(Icons.Default.Screenshot, contentDescription = null) },
            title = { Text(if (enabling) "Enable Screenshots?" else "Disable Screenshots?") },
            text = {
                Text(
                    if (enabling)
                        "Allowing screenshots means this app can appear in the Recent Apps screen " +
                            "and screen capture tools will be able to capture your passwords.\n\n" +
                            "Only enable this if you genuinely need to take screenshots inside 1Key."
                    else
                        "Disabling screenshots prevents this app from appearing in the Recent Apps " +
                            "screen and blocks screen capture tools from capturing your passwords.\n\n" +
                            "This is the recommended setting for a password manager."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        settingsVm.setScreenshotsEnabled(enabling)
                        showScreenshotDialog = false
                    },
                    colors = if (enabling) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ) else ButtonDefaults.buttonColors(),
                ) {
                    Text(if (enabling) "Enable Anyway" else "Disable Screenshots")
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenshotDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showBackgroundLockDialog) {
        var pendingBackgroundLockTimeout by remember { mutableStateOf(backgroundLockTimeout) }
        LockAwareDialog(
            onDismissRequest = { showBackgroundLockDialog = false },
            icon = { Icon(Icons.Default.Timer, contentDescription = null) },
            title = { Text("Lock when app in background") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "How quickly the vault locks after you leave the app. Shorter is more secure - the vault key is wiped from memory when this fires.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    BackgroundLockTimeout.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pendingBackgroundLockTimeout = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = pendingBackgroundLockTimeout == option,
                                onClick = { pendingBackgroundLockTimeout = option },
                            )
                            Column {
                                Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                                if (option == BackgroundLockTimeout.IMMEDIATE) {
                                    Text(
                                        "Lock the moment you leave the app",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsVm.setBackgroundLockTimeout(pendingBackgroundLockTimeout)
                    showBackgroundLockDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundLockDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showInactivityLockDialog) {
        var pendingInactivityLockTimeout by remember { mutableStateOf(inactivityLockTimeout) }
        LockAwareDialog(
            onDismissRequest = { showInactivityLockDialog = false },
            icon = { Icon(Icons.Default.HourglassEmpty, contentDescription = null) },
            title = { Text("Lock after inactivity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "How long the vault stays unlocked while the app is open but you're not using it. \"Never\" disables the idle timer (the background timer still applies).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    InactivityLockTimeout.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pendingInactivityLockTimeout = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = pendingInactivityLockTimeout == option,
                                onClick = { pendingInactivityLockTimeout = option },
                            )
                            Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsVm.setInactivityLockTimeout(pendingInactivityLockTimeout)
                    showInactivityLockDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showInactivityLockDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRemovePinDialog) {
        LockAwareDialog(
            onDismissRequest = {
                showRemovePinDialog = false
                removePinPasswordVisible = false
                removePinPasswordError = false
                removePinAttemptsRemaining = 3
            },
            icon = { Icon(Icons.Default.LockReset, contentDescription = null) },
            title = { Text("Remove PIN?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Once you remove your PIN, you will no longer be able to access vault using PIN. " +
                            "You can set up a new PIN later in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "To make sure only you can change this, please enter your master password. " +
                            "It's verified locally and never stored or transmitted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecurePasswordTextField(
                        state = removePinPasswordState,
                        onValueChanged = { if (removePinPasswordError) removePinPasswordError = false },
                        label = { Text("Master password") },
                        isError = removePinPasswordError,
                        supportingText = if (removePinPasswordError) {
                            {
                                val remaining = removePinAttemptsRemaining
                                Text(
                                    if (remaining == 1) "Incorrect password - 1 attempt remaining before vault locks."
                                    else "Incorrect password - $remaining attempts remaining."
                                )
                            }
                        } else null,
                        visualTransformation = if (removePinPasswordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { removePinPasswordVisible = !removePinPasswordVisible }) {
                                Icon(
                                    if (removePinPasswordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (removePinPasswordVisible) "Hide password" else "Show password",
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
                        settingsVm.removePinWithVerification(removePinPasswordState.consume())
                    },
                    enabled = !removePinPasswordState.isEmpty,
                ) { Text("Remove PIN") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRemovePinDialog = false
                        removePinPasswordVisible = false
                        removePinPasswordError = false
                        removePinAttemptsRemaining = 3
                    }
                ) { Text("Cancel") }
            },
        )
    }

    if (showRestoreLastScreenDialog) {
        LockAwareDialog(
            onDismissRequest = { showRestoreLastScreenDialog = false },
            icon = { Icon(Icons.Default.Restore, contentDescription = null) },
            title = { Text("Pick up where you left off?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "When auto-lock fires while you're using the app and you unlock again, " +
                            "we'll take you back to the same screen you were on - even if that " +
                            "was a credential's details. Helps you stay in flow.",
                    )
                    Text(
                        "The trade-off: anyone who legitimately unlocks the phone after you " +
                            "(with your master password or biometric) lands on whatever you were " +
                            "last viewing. You can change this anytime in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsVm.setRestoreLastScreenOnUnlock(true)
                        showRestoreLastScreenDialog = false
                    },
                ) { Text("Turn it on") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreLastScreenDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showHwIsolationDialog) {
        // Read-only explainer. Body is ~5 short sentences, ASCII-only, framed
        // positively for both StrongBox and TEE (TEE is full-strength
        // protection, not a downgrade). The "Close" action is the only one.
        LockAwareDialog(
            onDismissRequest = { showHwIsolationDialog = false },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Hardware key isolation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (hwIsolation?.tier) {
                            HardwareKeyIsolationTier.STRONGBOX ->
                                "Your device has a dedicated secure chip (StrongBox). " +
                                    "1Key stores the vault wrapping key inside it. " +
                                    "Even a compromise of the main CPU cannot extract the key. " +
                                    "This is the strongest isolation Android offers."
                            HardwareKeyIsolationTier.TEE ->
                                "Your device uses a Trusted Execution Environment (TEE). " +
                                    "The TEE is a separate secure mode of the main CPU. " +
                                    "Regular apps, 1Key included, cannot read keys stored there. " +
                                    "This is the standard hardware-backed protection on Android."
                            HardwareKeyIsolationTier.SOFTWARE ->
                                "On this device the vault wrapping key is not in secure hardware. " +
                                    "Android's software keystore is being used as a fallback. " +
                                    "This usually means the device is rooted or running on an emulator. " +
                                    "We recommend installing 1Key on a non-rooted device for full protection."
                            null ->
                                "Checking your device's secure hardware..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "StrongBox and TEE both protect your vault to the highest standard " +
                            "Android exposes to apps. StrongBox is an additional qualifier on " +
                            "devices that have a dedicated chip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHwIsolationDialog = false }) { Text("Close") }
            },
        )
    }
}
