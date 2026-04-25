package com.onekey.feature.settings.presentation.screen

import android.net.Uri
import com.onekey.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.LockTimeout
import com.onekey.core.domain.model.MasterPasswordInterval
import com.onekey.core.domain.model.Tag
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.feature.importexport.domain.ImportResult
import com.onekey.feature.importexport.domain.SkipReason
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportEvent
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportUiState
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportViewModel
import com.onekey.feature.settings.presentation.viewmodel.SettingsEvent
import com.onekey.feature.settings.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    onSetupPin: () -> Unit,
    onChangePassword: () -> Unit,
    onVaultReset: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
    importExportVm: ImportExportViewModel = hiltViewModel(),
) {
    val tags by settingsVm.tags.collectAsStateWithLifecycle()
    val isDarkTheme by settingsVm.isDarkTheme.collectAsStateWithLifecycle()
    val isBiometricEnabled by settingsVm.isBiometricEnabled.collectAsStateWithLifecycle()
    val isPinSetup by settingsVm.isPinSetup.collectAsStateWithLifecycle()
    val isScreenshotsEnabled by settingsVm.isScreenshotsEnabled.collectAsStateWithLifecycle()
    val isShowFavourites by settingsVm.isShowFavourites.collectAsStateWithLifecycle()
    val lockTimeout by settingsVm.lockTimeout.collectAsStateWithLifecycle()
    val isMasterPasswordRecheckEnabled by settingsVm.isMasterPasswordRecheckEnabled.collectAsStateWithLifecycle()
    val masterPasswordRecheckInterval by settingsVm.masterPasswordRecheckInterval.collectAsStateWithLifecycle()
    val isSeedingData by settingsVm.isSeedingData.collectAsStateWithLifecycle()
    val backupState by importExportVm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    var showBiometricConfirmDialog by remember { mutableStateOf(false) }
    var biometricPasswordInput by remember { mutableStateOf("") }
    var biometricPasswordVisible by remember { mutableStateOf(false) }
    var biometricPasswordError by remember { mutableStateOf(false) }
    var biometricAttemptsRemaining by remember { mutableIntStateOf(3) }
    var showBiometricLockedDialog by remember { mutableStateOf(false) }
    var showSkippedDialog by remember { mutableStateOf(false) }
    var showFailedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settingsVm.event.collect { event ->
            when (event) {
                SettingsEvent.PinReset -> snackbarHostState.showSnackbar("PIN has been reset")
                SettingsEvent.VaultContentsDeleted -> onVaultReset()
                is SettingsEvent.SeedComplete -> snackbarHostState.showSnackbar("${event.count} sample credentials added")
                is SettingsEvent.Error -> snackbarHostState.showSnackbar(event.message)
                SettingsEvent.BiometricEnabled -> {
                    showBiometricConfirmDialog = false
                    biometricPasswordInput = ""
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
                    biometricPasswordInput = ""
                    biometricPasswordVisible = false
                    biometricPasswordError = false
                    biometricAttemptsRemaining = 3
                    showBiometricLockedDialog = true
                }
            }
        }
    }

    val canUseBiometric = remember {
        androidx.biometric.BiometricManager.from(context).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }

    var newTagName by remember { mutableStateOf("") }
    var showAddTag by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf(ExportFormat.JSON) }
    var encryptExport by remember { mutableStateOf(true) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPasswordInput by remember { mutableStateOf("") }
    var exportPasswordVisible by remember { mutableStateOf(false) }
    var importPasswordInput by remember { mutableStateOf("") }
    var importPasswordVisible by remember { mutableStateOf(false) }
    var showDisableEncryptionDialog by remember { mutableStateOf(false) }
    var disableEncPwdInput by remember { mutableStateOf("") }
    var disableEncPwdVisible by remember { mutableStateOf(false) }
    var disableEncPwdError by remember { mutableStateOf<String?>(null) }
    var exportPasswordError by remember { mutableStateOf<String?>(null) }
    var isVerifyingExportPassword by remember { mutableStateOf(false) }
    var showExportLockedDialog by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var resultDialogSuccess by remember { mutableStateOf(true) }
    var resultDialogMessage by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        importExportVm.notifyPickerDone()
        if (uri != null) {
            if (encryptExport) importExportVm.exportEncrypted(uri, selectedFormat, context)
            else importExportVm.export(uri, selectedFormat, context)
        } else {
            importExportVm.clearPendingExportPassword()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        importExportVm.notifyPickerDone()
        uri?.let { importExportVm.import(it, context) }
    }

    LaunchedEffect(Unit) {
        importExportVm.events.collect { event ->
            when (event) {
                is ImportExportEvent.PlainExportAllowed -> {
                    encryptExport = false
                    showDisableEncryptionDialog = false
                    disableEncPwdInput = ""
                    disableEncPwdVisible = false
                    disableEncPwdError = null
                }
                is ImportExportEvent.PlainExportDenied -> {
                    disableEncPwdError = event.message
                }
                is ImportExportEvent.ExportPasswordVerified -> {
                    importExportVm.setPendingExportPassword(exportPasswordInput.toCharArray())
                    showExportPasswordDialog = false
                    exportPasswordInput = ""
                    exportPasswordVisible = false
                    exportPasswordError = null
                    isVerifyingExportPassword = false
                    importExportVm.notifyPickerLaunched()
                    exportLauncher.launch("1key_backup.1key")
                }
                is ImportExportEvent.ExportPasswordFailed -> {
                    isVerifyingExportPassword = false
                    exportPasswordError = if (event.attemptsRemaining == 1)
                        "Wrong password — 1 attempt remaining before the vault locks."
                    else
                        "Wrong password — ${event.attemptsRemaining} attempts remaining."
                }
                is ImportExportEvent.ExportVaultLocked -> {
                    isVerifyingExportPassword = false
                    showExportPasswordDialog = false
                    exportPasswordInput = ""
                    exportPasswordVisible = false
                    exportPasswordError = null
                    showExportLockedDialog = true
                }
            }
        }
    }

    LaunchedEffect(backupState) {
        when (val s = backupState) {
            is ImportExportUiState.Success -> {
                resultDialogSuccess = true
                resultDialogMessage = s.message
                showResultDialog = true
            }
            is ImportExportUiState.Error -> {
                resultDialogSuccess = false
                resultDialogMessage = s.message
                showResultDialog = true
            }
            else -> Unit
        }
    }
    var showResetPinDialog by remember { mutableStateOf(false) }
    var showScreenshotDialog by remember { mutableStateOf(false) }
    var pendingScreenshotsEnabled by remember { mutableStateOf(true) }
    var showDeleteVaultDialog by remember { mutableStateOf(false) }
    var deleteVaultConfirmed by remember { mutableStateOf(false) }
    var showLockTimeoutDialog by remember { mutableStateOf(false) }
    var pendingLockTimeout by remember(lockTimeout) { mutableStateOf(lockTimeout) }
    var tagToDelete by remember { mutableStateOf<Tag?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                    }
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
            // ── Appearance ────────────────────────────────────────────────────
            SectionHeader("Appearance")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text("Dark theme") },
                        supportingContent = { Text(if (isDarkTheme) "On" else "Off") },
                        leadingContent = {
                            Icon(
                                if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = isDarkTheme,
                                onCheckedChange = { settingsVm.toggleTheme() },
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Show Favourites tab") },
                        supportingContent = {
                            Text(
                                if (isShowFavourites) "Favourites visible in bottom navigation"
                                else "Favourites hidden from bottom navigation"
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Favorite, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = isShowFavourites,
                                onCheckedChange = { settingsVm.setShowFavourites(it) },
                            )
                        },
                    )
                }
            }

            // ── Security ──────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader("Security")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text("Setup / Change PIN") },
                        supportingContent = { Text("Faster unlock with a 6-digit PIN") },
                        leadingContent = { Icon(Icons.Default.Lock, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable(onClick = onSetupPin),
                    )
                    if (canUseBiometric) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Allow Screenshots") },
                        supportingContent = {
                            Text(
                                if (isScreenshotsEnabled) "App visible in Recent Apps screen — screenshots and recordings enabled"
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Auto-lock") },
                        supportingContent = { Text(lockTimeout.displayName) },
                        leadingContent = { Icon(Icons.Default.Timer, contentDescription = null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable { showLockTimeoutDialog = true },
                    )
                    if (isPinSetup) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("Reset PIN") },
                            supportingContent = { Text("Remove saved PIN, revert to master password") },
                            leadingContent = { Icon(Icons.Default.LockReset, null) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { showResetPinDialog = true },
                        )
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

            // ── Password Verification ─────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader("Password Verification")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
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
                                                "Default — recommended",
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

            // ── Backup ────────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader("Backup")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Format", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = selectedFormat == fmt,
                                onClick = { selectedFormat = fmt },
                                label = { Text(fmt.name) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Encrypt export", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (encryptExport) "Protected with your master password"
                                else "Unprotected — anyone can read it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = encryptExport,
                            onCheckedChange = { newValue ->
                                if (!newValue) {
                                    disableEncPwdError = null
                                    showDisableEncryptionDialog = true
                                } else {
                                    encryptExport = true
                                }
                            },
                        )
                    }
                    HorizontalDivider()
                    if (encryptExport) {
                        Text(
                            "Backup encrypted with AES-256-GCM. The same password is required to restore.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "The exported file is NOT encrypted — store it securely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (encryptExport) {
                                    showExportPasswordDialog = true
                                } else {
                                    importExportVm.notifyPickerLaunched()
                                    exportLauncher.launch("1key_backup.${selectedFormat.name.lowercase()}")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = backupState !is ImportExportUiState.Loading,
                        ) { Text("Export") }
                        OutlinedButton(
                            onClick = {
                                importExportVm.notifyPickerLaunched()
                                importLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = backupState !is ImportExportUiState.Loading,
                        ) { Text("Import") }
                    }
                    when (val s = backupState) {
                        is ImportExportUiState.Loading ->
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        is ImportExportUiState.ImportSuccess ->
                            ImportSummaryRow(
                                result = s.result,
                                onShowSkipped = { showSkippedDialog = true },
                                onShowFailed = { showFailedDialog = true },
                            )
                        else -> Unit
                    }
                }
            }

            // ── Categories ────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader("Categories")
            tags.forEach { tag ->
                key(tag.name) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(tag.name) },
                            trailingContent = if (!tag.isDefault) {
                                {
                                    IconButton(onClick = { tagToDelete = tag }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete category",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            } else null,
                        )
                    }
                }
            }
            if (showAddTag) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { input ->
                            newTagName = input.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase() else it.toString()
                            }
                        },
                        label = { Text("Category name") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(onClick = {
                        if (newTagName.isNotBlank()) {
                            settingsVm.addTag(newTagName.trim())
                            newTagName = ""
                            showAddTag = false
                        }
                    }) { Icon(Icons.Default.Check, "Save category") }
                    IconButton(onClick = { showAddTag = false; newTagName = "" }) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { showAddTag = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Category")
                }
            }

            // ── Developer Options (debug builds only) ─────────────────────────
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Developer Options")
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Seed Sample Data") },
                        supportingContent = { Text("Insert 9 sample credentials covering all categories") },
                        leadingContent = {
                            if (isSeedingData) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Storage, contentDescription = null)
                            }
                        },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable(enabled = !isSeedingData) { settingsVm.seedData() },
                    )
                }
            }

            // ── Danger Zone ───────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader("Danger Zone")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                ),
            ) {
                ListItem(
                    headlineContent = {
                        Text("Delete Vault", color = MaterialTheme.colorScheme.error)
                    },
                    supportingContent = {
                        Text("Remove all credentials while keeping your account active")
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable { showDeleteVaultDialog = true },
                )
            }

            // ── Privacy ───────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader("Privacy")
            ExpandableInfoCard(
                title = "Privacy Policy",
                icon = Icons.Default.PrivacyTip,
            ) {
                PrivacyLine("All credentials are stored locally on this device using AES-256-GCM encryption.")
                PrivacyLine("1Key does not require an account or internet connection.")
                PrivacyLine("No analytics, telemetry, or crash reporting of any kind.")
                PrivacyLine("Your master password never leaves your device — not even a hash.")
                PrivacyLine("Exports are unencrypted plaintext — treat them as sensitive files.")
            }

            // ── Licences ──────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionHeader("Open Source Licences")
            ExpandableInfoCard(
                title = "Third-party libraries",
                icon = Icons.Default.Description,
            ) {
                LicenceRow("Kotlin", "Apache License 2.0", "JetBrains")
                LicenceRow("Jetpack Compose", "Apache License 2.0", "Google")
                LicenceRow("Room", "Apache License 2.0", "Google")
                LicenceRow("Hilt / Dagger", "Apache License 2.0", "Google")
                LicenceRow("OkHttp / Retrofit", "Apache License 2.0", "Square")
                LicenceRow("Kotlinx Coroutines", "Apache License 2.0", "JetBrains")
                LicenceRow("Gson", "Apache License 2.0", "Google")
                LicenceRow("Android Biometric", "Apache License 2.0", "Google")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Your vault is encrypted and stored only on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(
                "1Key — version 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    val importSuccessState = backupState as? ImportExportUiState.ImportSuccess

    if (showSkippedDialog && importSuccessState != null) {
        val skipped = importSuccessState.result.skipped
        AlertDialog(
            onDismissRequest = { showSkippedDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("Skipped (${skipped.size})") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(skipped) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                item.title.ifBlank { "(no title)" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (item.username.isNotBlank()) {
                                Text(
                                    item.username,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                when (item.reason) {
                                    SkipReason.DUPLICATE_ID -> "Already in vault (same ID)"
                                    SkipReason.DUPLICATE_TITLE_USERNAME -> "Already in vault (same name & username)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSkippedDialog = false }) { Text("Close") }
            },
        )
    }

    if (showFailedDialog && importSuccessState != null) {
        val failed = importSuccessState.result.failed
        AlertDialog(
            onDismissRequest = { showFailedDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Failed entries (${failed.size})") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(failed) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Entry ${entry.rowIndex}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                entry.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFailedDialog = false }) { Text("Close") }
            },
        )
    }

    if (showBiometricConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showBiometricConfirmDialog = false
                biometricPasswordInput = ""
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
                    OutlinedTextField(
                        value = biometricPasswordInput,
                        onValueChange = { input ->
                            biometricPasswordInput = input
                            if (biometricPasswordError) biometricPasswordError = false
                        },
                        label = { Text("Master password") },
                        singleLine = true,
                        isError = biometricPasswordError,
                        supportingText = if (biometricPasswordError) {
                            {
                                val remaining = biometricAttemptsRemaining
                                Text(
                                    if (remaining == 1) "Incorrect password — 1 attempt remaining before vault locks."
                                    else "Incorrect password — $remaining attempts remaining."
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
                        settingsVm.enableBiometricWithVerification(biometricPasswordInput.toCharArray())
                    },
                    enabled = biometricPasswordInput.isNotEmpty(),
                ) { Text("Enable Biometric") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBiometricConfirmDialog = false
                        biometricPasswordInput = ""
                        biometricPasswordVisible = false
                        biometricPasswordError = false
                        biometricAttemptsRemaining = 3
                    }
                ) { Text("Cancel") }
            },
        )
    }

    if (showBiometricLockedDialog) {
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
                Text(
                    "You've entered the wrong password 3 times while trying to enable biometric unlock. " +
                        "To keep your data safe, your vault has been locked. " +
                        "Please re-authenticate to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = { showBiometricLockedDialog = false }) { Text("OK") }
            },
        )
    }

    if (showScreenshotDialog) {
        val enabling = pendingScreenshotsEnabled
        AlertDialog(
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

    if (showLockTimeoutDialog) {
        AlertDialog(
            onDismissRequest = { showLockTimeoutDialog = false },
            icon = { Icon(Icons.Default.Timer, contentDescription = null) },
            title = { Text("Auto-lock") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Choose how long 1Key waits before locking when you leave the app or stop interacting. The vault key is wiped from memory when auto-lock triggers — shorter timeout means a smaller window of exposure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LockTimeout.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pendingLockTimeout = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = pendingLockTimeout == option,
                                onClick = { pendingLockTimeout = option },
                            )
                            Column {
                                Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                                if (option == LockTimeout.IMMEDIATE) {
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
                    settingsVm.setLockTimeout(pendingLockTimeout)
                    showLockTimeoutDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showLockTimeoutDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteVaultDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteVaultDialog = false
                deleteVaultConfirmed = false
            },
            icon = {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete Your Vault?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This will permanently remove all your saved credentials, their history and app settings from this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = deleteVaultConfirmed,
                            onCheckedChange = { deleteVaultConfirmed = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.error,
                            ),
                        )
                        Text(
                            "I understand all my credentials will be permanently deleted",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteVaultDialog = false
                        deleteVaultConfirmed = false
                        settingsVm.deleteVaultContents()
                    },
                    enabled = deleteVaultConfirmed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete Vault") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteVaultDialog = false
                        deleteVaultConfirmed = false
                    }
                ) { Text("Keep My Credentials") }
            },
        )
    }

    if (showResetPinDialog) {
        AlertDialog(
            onDismissRequest = { showResetPinDialog = false },
            icon = { Icon(Icons.Default.LockReset, contentDescription = null) },
            title = { Text("Reset PIN?") },
            text = { Text("Your PIN will be removed. You will need to use your master password to unlock the vault.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetPinDialog = false
                        settingsVm.resetPin()
                    }
                ) { Text("Reset PIN") }
            },
            dismissButton = {
                TextButton(onClick = { showResetPinDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── Disable-encryption confirmation dialog ────────────────────────────────

    if (showDisableEncryptionDialog) {
        AlertDialog(
            onDismissRequest = {
                showDisableEncryptionDialog = false
                disableEncPwdInput = ""
                disableEncPwdVisible = false
                disableEncPwdError = null
            },
            icon = {
                Icon(
                    Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Turn Off Encryption?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Unencrypted exports are plain readable files. Anyone who finds your backup — " +
                            "on your device, in cloud storage, or sent by mistake — can read every " +
                            "password without any tools or technical knowledge.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Encryption is strongly recommended. Only disable it if you need to import " +
                            "the file into another app that cannot handle the encrypted format.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = disableEncPwdInput,
                        onValueChange = { input ->
                            disableEncPwdInput = input
                            if (disableEncPwdError != null) disableEncPwdError = null
                        },
                        label = { Text("Master password") },
                        singleLine = true,
                        isError = disableEncPwdError != null,
                        supportingText = disableEncPwdError?.let { { Text(it) } },
                        visualTransformation = if (disableEncPwdVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { disableEncPwdVisible = !disableEncPwdVisible }) {
                                Icon(
                                    if (disableEncPwdVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
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
                    onClick = {
                        importExportVm.verifyPasswordForPlainExport(disableEncPwdInput.toCharArray())
                        disableEncPwdInput = ""
                    },
                    enabled = disableEncPwdInput.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Disable Encryption") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDisableEncryptionDialog = false
                        disableEncPwdInput = ""
                        disableEncPwdVisible = false
                        disableEncPwdError = null
                    }
                ) { Text("Keep Encrypted") }
            },
        )
    }

    // ── Export password dialog ────────────────────────────────────────────────

    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isVerifyingExportPassword) {
                    showExportPasswordDialog = false
                    exportPasswordInput = ""
                    exportPasswordVisible = false
                    exportPasswordError = null
                }
            },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Encrypt Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter your master password to encrypt the backup. " +
                            "You will need the same password to restore from this file.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = exportPasswordInput,
                        onValueChange = { input ->
                            exportPasswordInput = input
                            if (exportPasswordError != null) exportPasswordError = null
                        },
                        label = { Text("Master password") },
                        singleLine = true,
                        isError = exportPasswordError != null,
                        supportingText = exportPasswordError?.let { err -> { Text(err) } },
                        visualTransformation = if (exportPasswordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { exportPasswordVisible = !exportPasswordVisible }) {
                                Icon(
                                    if (exportPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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
                    onClick = {
                        isVerifyingExportPassword = true
                        importExportVm.verifyPasswordForExport(exportPasswordInput.toCharArray())
                    },
                    enabled = exportPasswordInput.isNotEmpty() && !isVerifyingExportPassword,
                ) {
                    if (isVerifyingExportPassword) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Verify & Continue")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportPasswordDialog = false
                        exportPasswordInput = ""
                        exportPasswordVisible = false
                        exportPasswordError = null
                    },
                    enabled = !isVerifyingExportPassword,
                ) { Text("Cancel") }
            },
        )
    }

    // ── Export vault-locked dialog ────────────────────────────────────────────

    if (showExportLockedDialog) {
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
                Text(
                    "You've entered the wrong password 3 times. " +
                        "To keep your data safe, your vault has been locked. " +
                        "Please re-authenticate to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportLockedDialog = false
                        importExportVm.lockVault()
                    },
                ) { Text("OK") }
            },
        )
    }

    // ── Export / import result dialog ─────────────────────────────────────────

    if (showResultDialog) {
        AlertDialog(
            onDismissRequest = {
                showResultDialog = false
                importExportVm.acknowledgeResult()
            },
            icon = {
                Icon(
                    if (resultDialogSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (resultDialogSuccess) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(if (resultDialogSuccess) "Backup Saved" else "Something Went Wrong") },
            text = { Text(resultDialogMessage, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = {
                        showResultDialog = false
                        importExportVm.acknowledgeResult()
                    }
                ) { Text("OK") }
            },
        )
    }

    // ── Import password dialog (encrypted file detected) ──────────────────────

    val awaitingImport = backupState as? ImportExportUiState.AwaitingImportPassword
    if (awaitingImport != null) {
        AlertDialog(
            onDismissRequest = {
                importPasswordInput = ""
                importPasswordVisible = false
                importExportVm.cancelPendingImport()
            },
            icon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
            title = { Text("Encrypted Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This backup is password-protected. Enter the password that was used when it was created.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (awaitingImport.error != null) {
                        Text(
                            awaitingImport.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = importPasswordInput,
                        onValueChange = { importPasswordInput = it },
                        label = { Text("Backup password") },
                        singleLine = true,
                        isError = awaitingImport.error != null,
                        visualTransformation = if (importPasswordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { importPasswordVisible = !importPasswordVisible }) {
                                Icon(
                                    if (importPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
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
                    onClick = {
                        importExportVm.importWithPassword(importPasswordInput.toCharArray())
                        importPasswordInput = ""
                        importPasswordVisible = false
                    },
                    enabled = importPasswordInput.isNotEmpty(),
                ) { Text("Decrypt & Import") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        importPasswordInput = ""
                        importPasswordVisible = false
                        importExportVm.cancelPendingImport()
                    }
                ) { Text("Cancel") }
            },
        )
    }

    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            icon = {
                Icon(
                    Icons.Default.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("Remove \u201c${tag.name}\u201d?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The \u201c${tag.name}\u201d category will be permanently removed.")
                    Text(
                        "Any passwords currently tagged with it will have the category quietly " +
                            "stripped away \u2014 your passwords themselves stay completely safe " +
                            "and nothing else will change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsVm.deleteTag(tag.name)
                        tagToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove Category") }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) { Text("Keep It") }
            },
        )
    }
}

@Composable
private fun ExpandableInfoCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(title) },
                leadingContent = { Icon(icon, null) },
                trailingContent = {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                    )
                },
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LicenceRow(name: String, licence: String, author: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text("$licence · $author", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportSummaryRow(
    result: ImportResult,
    onShowSkipped: () -> Unit,
    onShowFailed: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Imported ${result.imported} ${if (result.imported == 1) "credential" else "credentials"}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (result.skipped.isNotEmpty()) {
            Text(
                "${result.skipped.size} skipped — tap to view",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onShowSkipped),
            )
        }
        if (result.failed.isNotEmpty()) {
            Text(
                "${result.failed.size} failed to parse — tap to view",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onShowFailed),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}
