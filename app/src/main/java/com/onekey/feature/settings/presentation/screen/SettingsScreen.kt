package com.onekey.feature.settings.presentation.screen

import android.net.Uri
import com.onekey.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.usecase.ExportFormat
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
    val isSeedingData by settingsVm.isSeedingData.collectAsStateWithLifecycle()
    val backupState by importExportVm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        settingsVm.event.collect { event ->
            when (event) {
                SettingsEvent.PinReset -> snackbarHostState.showSnackbar("PIN has been reset")
                SettingsEvent.VaultReset -> onVaultReset()
                is SettingsEvent.SeedComplete -> snackbarHostState.showSnackbar("${event.count} sample credentials added")
                is SettingsEvent.Error -> snackbarHostState.showSnackbar(event.message)
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
    var showResetPinDialog by remember { mutableStateOf(false) }
    var showResetVaultDialog by remember { mutableStateOf(false) }
    var resetVaultConfirmed by remember { mutableStateOf(false) }
    var showScreenshotDialog by remember { mutableStateOf(false) }
    var pendingScreenshotsEnabled by remember { mutableStateOf(true) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { importExportVm.export(it, selectedFormat, context) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importExportVm.import(it, selectedFormat, context) }
    }

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
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            // ── Appearance ────────────────────────────────────────────────────
            item {
                SectionHeader("Appearance")
                Card(modifier = Modifier.fillMaxWidth()) {
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
                }
            }

            // ── Security ──────────────────────────────────────────────────────
            item {
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
                                supportingContent = { Text("Use fingerprint or face to unlock") },
                                leadingContent = {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = isBiometricEnabled,
                                        onCheckedChange = { settingsVm.setBiometricEnabled(it) },
                                    )
                                },
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("Allow Screenshots") },
                            supportingContent = {
                                Text(
                                    if (isScreenshotsEnabled) "App visible in Recent Apps screen"
                                    else "App hidden from Recent Apps screen"
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
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = {
                                Text("Reset Vault", color = MaterialTheme.colorScheme.error)
                            },
                            supportingContent = { Text("Permanently delete all credentials") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { showResetVaultDialog = true },
                        )
                    }
                }
            }

            // ── Backup ────────────────────────────────────────────────────────
            item {
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

                        HorizontalDivider()

                        Text(
                            "The exported file is NOT encrypted — store it securely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    exportLauncher.launch("1key_backup.${selectedFormat.name.lowercase()}")
                                },
                                modifier = Modifier.weight(1f),
                                enabled = backupState !is ImportExportUiState.Loading,
                            ) { Text("Export") }

                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f),
                                enabled = backupState !is ImportExportUiState.Loading,
                            ) { Text("Import") }
                        }

                        when (val s = backupState) {
                            is ImportExportUiState.Loading ->
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            is ImportExportUiState.Success ->
                                Text(s.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            is ImportExportUiState.Error ->
                                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            else -> Unit
                        }
                    }
                }
            }

            // ── Tags ──────────────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Tags")
            }

            items(tags, key = { it.name }) { tag ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(tag.name) },
                        trailingContent = if (!tag.isDefault) {
                            {
                                IconButton(onClick = { settingsVm.deleteTag(tag.name) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete tag",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        } else null,
                    )
                }
            }

            item {
                if (showAddTag) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newTagName,
                            onValueChange = { newTagName = it },
                            label = { Text("Tag name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        IconButton(onClick = {
                            if (newTagName.isNotBlank()) {
                                settingsVm.addTag(newTagName.trim())
                                newTagName = ""
                                showAddTag = false
                            }
                        }) { Icon(Icons.Default.Check, "Save tag") }
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
                        Text("Add Tag")
                    }
                }
            }
            // ── Developer Options (debug builds only) ─────────────────────────
            if (BuildConfig.DEBUG) {
                item {
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
            }

            // ── Privacy ───────────────────────────────────────────────────────
            item {
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
            }

            // ── Licences ──────────────────────────────────────────────────────
            item {
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
                    "1Key — version 1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
            }
        }
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

    if (showResetVaultDialog) {
        AlertDialog(
            onDismissRequest = {
                showResetVaultDialog = false
                resetVaultConfirmed = false
            },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Reset Vault?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This will permanently delete ALL credentials stored in this vault. " +
                            "This action cannot be undone.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = resetVaultConfirmed,
                            onCheckedChange = { resetVaultConfirmed = it },
                        )
                        Text("I understand all my credentials will be deleted")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetVaultDialog = false
                        resetVaultConfirmed = false
                        settingsVm.resetVault()
                    },
                    enabled = resetVaultConfirmed,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete Everything") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showResetVaultDialog = false
                        resetVaultConfirmed = false
                    }
                ) { Text("Cancel") }
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
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}
