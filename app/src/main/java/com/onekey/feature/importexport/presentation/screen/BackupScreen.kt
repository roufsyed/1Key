package com.onekey.feature.importexport.presentation.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.feature.importexport.domain.ImportResult
import com.onekey.feature.importexport.domain.SkipReason
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportEvent
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportUiState
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedFormat by remember { mutableStateOf(ExportFormat.JSON) }
    var encryptExport by remember { mutableStateOf(true) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPasswordInput by remember { mutableStateOf("") }
    var exportPasswordVisible by remember { mutableStateOf(false) }
    var exportPasswordError by remember { mutableStateOf<String?>(null) }
    var isVerifyingExportPassword by remember { mutableStateOf(false) }
    var showExportLockedDialog by remember { mutableStateOf(false) }
    var showDisableEncryptionDialog by remember { mutableStateOf(false) }
    var disableEncPwdInput by remember { mutableStateOf("") }
    var disableEncPwdVisible by remember { mutableStateOf(false) }
    var disableEncPwdError by remember { mutableStateOf<String?>(null) }
    var importPasswordInput by remember { mutableStateOf("") }
    var importPasswordVisible by remember { mutableStateOf(false) }
    var showSkippedDialog by remember { mutableStateOf(false) }
    var showFailedDialog by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var resultDialogSuccess by remember { mutableStateOf(true) }
    var resultDialogMessage by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        viewModel.notifyPickerDone()
        if (uri != null) {
            if (encryptExport) viewModel.exportEncrypted(uri, selectedFormat, context)
            else viewModel.export(uri, selectedFormat, context)
        } else {
            viewModel.clearPendingExportPassword()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        viewModel.notifyPickerDone()
        uri?.let { viewModel.import(it, context) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
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
                    viewModel.setPendingExportPassword(exportPasswordInput.toCharArray())
                    showExportPasswordDialog = false
                    exportPasswordInput = ""
                    exportPasswordVisible = false
                    exportPasswordError = null
                    isVerifyingExportPassword = false
                    viewModel.notifyPickerLaunched()
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

    LaunchedEffect(state) {
        when (val s = state) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Import") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Export ────────────────────────────────────────────────────────
            BackupSectionHeader("Export")

            Text("Format", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format },
                        label = { Text(format.name) },
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

            if (encryptExport) {
                Text(
                    "Encrypted with AES-256-GCM. You'll need your master password to restore from this backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "NOT encrypted — anyone who gets this file can read all your passwords.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    if (encryptExport) showExportPasswordDialog = true
                    else {
                        viewModel.notifyPickerLaunched()
                        exportLauncher.launch("1key_backup.${selectedFormat.name.lowercase()}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImportExportUiState.Loading,
            ) { Text("Export Vault") }

            // ── Import ────────────────────────────────────────────────────────
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            BackupSectionHeader("Import")

            Text(
                "Picks up your file format automatically — just select any .json or .csv credential export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    viewModel.notifyPickerLaunched()
                    importLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImportExportUiState.Loading,
            ) { Text("Import Credentials") }

            when (val s = state) {
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

            // ── Importing from another app ────────────────────────────────────
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            BackupSectionHeader("Migrating from another app")
            ImportGuideCard()

            Spacer(Modifier.height(16.dp))
        }
    }

    // ── Disable-encryption dialog ─────────────────────────────────────────────

    if (showDisableEncryptionDialog) {
        AlertDialog(
            onDismissRequest = {
                showDisableEncryptionDialog = false
                disableEncPwdInput = ""
                disableEncPwdVisible = false
                disableEncPwdError = null
            },
            icon = {
                Icon(Icons.Default.LockOpen, null, tint = MaterialTheme.colorScheme.error)
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
                        "Only disable encryption if you need to import the file into another app " +
                            "that cannot handle the encrypted format.",
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
                        supportingText = disableEncPwdError?.let { err -> { Text(err) } },
                        visualTransformation = if (disableEncPwdVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { disableEncPwdVisible = !disableEncPwdVisible }) {
                                Icon(
                                    if (disableEncPwdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null,
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
                        viewModel.verifyPasswordForPlainExport(disableEncPwdInput.toCharArray())
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
                TextButton(onClick = {
                    showDisableEncryptionDialog = false
                    disableEncPwdInput = ""
                    disableEncPwdVisible = false
                    disableEncPwdError = null
                }) { Text("Keep Encrypted") }
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
            icon = { Icon(Icons.Default.Lock, null) },
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
                                    null,
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
                        viewModel.verifyPasswordForExport(exportPasswordInput.toCharArray())
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
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.error) },
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
                Button(onClick = {
                    showExportLockedDialog = false
                    viewModel.lockVault()
                }) { Text("OK") }
            },
        )
    }

    // ── Result dialog (export success / error) ────────────────────────────────

    if (showResultDialog) {
        AlertDialog(
            onDismissRequest = { showResultDialog = false; viewModel.acknowledgeResult() },
            icon = {
                Icon(
                    if (resultDialogSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    null,
                    tint = if (resultDialogSuccess) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(if (resultDialogSuccess) "Backup Saved" else "Something Went Wrong") },
            text = { Text(resultDialogMessage, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = { showResultDialog = false; viewModel.acknowledgeResult() }) { Text("OK") }
            },
        )
    }

    // ── Import password dialog ────────────────────────────────────────────────

    val awaitingImport = state as? ImportExportUiState.AwaitingImportPassword
    if (awaitingImport != null) {
        AlertDialog(
            onDismissRequest = {
                importPasswordInput = ""
                importPasswordVisible = false
                viewModel.cancelPendingImport()
            },
            icon = { Icon(Icons.Default.LockOpen, null) },
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
                                    null,
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
                        viewModel.importWithPassword(importPasswordInput.toCharArray())
                        importPasswordInput = ""
                        importPasswordVisible = false
                    },
                    enabled = importPasswordInput.isNotEmpty(),
                ) { Text("Decrypt & Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    importPasswordInput = ""
                    importPasswordVisible = false
                    viewModel.cancelPendingImport()
                }) { Text("Cancel") }
            },
        )
    }

    // ── Skipped / failed dialogs ──────────────────────────────────────────────

    val importSuccessState = state as? ImportExportUiState.ImportSuccess

    if (showSkippedDialog && importSuccessState != null) {
        val skipped = importSuccessState.result.skipped
        AlertDialog(
            onDismissRequest = { showSkippedDialog = false },
            icon = { Icon(Icons.Default.Info, null) },
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
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Failed entries (${failed.size})") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(failed) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Entry ${entry.rowIndex}", style = MaterialTheme.typography.bodyMedium)
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
}

// ── Import guide ──────────────────────────────────────────────────────────────

@Composable
private fun ImportGuideCard() {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = {
                    Text("How to migrate from another app", style = MaterialTheme.typography.bodyMedium)
                },
                supportingContent = { Text("Step-by-step guide · what transfers · what doesn't") },
                leadingContent = { Icon(Icons.Default.SwapHoriz, null) },
                trailingContent = {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                },
                modifier = Modifier.clickable { expanded = !expanded },
            )
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Step 1
                    GuideStep(
                        number = "1",
                        title = "Export from your current app",
                        icon = Icons.Default.FileUpload,
                    ) {
                        AppGuideRow("Google Passwords", "passwords.google.com → Settings → Export passwords")
                        AppGuideRow("LastPass", "Account Options → Advanced → Export → CSV")
                        AppGuideRow("KeePass", "File → Export → CSV format")
                        AppGuideRow("Safari / iCloud", "Passwords → ··· → Export All Passwords (iOS 17+)")
                        AppGuideRow("1Password", "File → Export → All Items → CSV")
                        AppGuideRow("Dashlane / NordPass", "Settings → Export → CSV")
                    }

                    // Step 2
                    GuideStep(
                        number = "2",
                        title = "Transfer the file to this device",
                        icon = Icons.Default.PhoneAndroid,
                    ) {
                        Text(
                            "Use AirDrop, a USB cable, email-to-self, or cloud storage (iCloud Drive, Google Drive). " +
                                "The exported file is plain text — handle it carefully and delete it once the import is done.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Step 3
                    GuideStep(
                        number = "3",
                        title = "Import in 1Key",
                        icon = Icons.Default.FileDownload,
                    ) {
                        Text(
                            "Tap \"Import Credentials\" above and pick your file. " +
                                "1Key detects JSON and CSV automatically — no format selection needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()

                    Text(
                        "What transfers well",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TransferRow(good = true, "Titles, usernames, passwords, URLs, and notes")
                        TransferRow(good = true, "Folders and groups — imported as 1Key categories")
                        TransferRow(good = true, "TOTP / 2FA secrets, if included in the export")
                        TransferRow(good = true, "Duplicates are detected and skipped automatically")
                    }

                    Text(
                        "What to watch out for",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TransferRow(
                            good = false,
                            "Google Passwords never exports TOTP secrets — you'll need to re-scan those QR codes manually",
                        )
                        TransferRow(
                            good = false,
                            "File attachments and documents don't transfer — text credentials only",
                        )
                        TransferRow(
                            good = false,
                            "Credit cards, addresses, and identity items become generic credentials with custom fields — they may look different",
                        )
                        TransferRow(
                            good = false,
                            "Proprietary encrypted formats (.1pux from 1Password, .kdbx from KeePass) can't be imported directly — export as CSV from those apps instead",
                        )
                        TransferRow(
                            good = false,
                            "Shared / team vaults from LastPass Teams or 1Password Business require a separate export per vault",
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun GuideStep(
    number: String,
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                Text(number, color = MaterialTheme.colorScheme.onPrimary)
            }
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Column(
            modifier = Modifier.padding(start = 36.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
private fun AppGuideRow(app: String, instruction: String) {
    Column(modifier = Modifier.padding(bottom = 2.dp)) {
        Text(app, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(
            instruction,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransferRow(good: Boolean, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (good) Icons.Default.CheckCircle else Icons.Default.Cancel,
            null,
            tint = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun BackupSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
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
