package com.onekey.feature.importexport.presentation.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportEvent
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportUiState
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportScreen(
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
                title = { Text("Import / Export") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Format", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format },
                        label = { Text(format.name) },
                    )
                }
            }

            // ── Export ────────────────────────────────────────────────────────
            Divider()
            Text("Export", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Encrypt with master password", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (encryptExport) "Protected — AES-256-GCM"
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
                    "Backup encrypted with AES-256-GCM. The same password is required to restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "Exports all credentials as ${selectedFormat.name}. The exported file is NOT encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    if (encryptExport) {
                        showExportPasswordDialog = true
                    } else {
                        viewModel.notifyPickerLaunched()
                        exportLauncher.launch("1key_backup.${selectedFormat.name.lowercase()}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImportExportUiState.Loading,
            ) { Text("Export Vault") }

            // ── Import ────────────────────────────────────────────────────────
            Divider()
            Text("Import", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = {
                    viewModel.notifyPickerLaunched()
                    importLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImportExportUiState.Loading,
            ) { Text("Import Credentials") }

            when (val s = state) {
                is ImportExportUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is ImportExportUiState.ImportSuccess -> {
                    val r = s.result
                    Text(
                        buildString {
                            append("Imported ${r.imported} ${if (r.imported == 1) "credential" else "credentials"}")
                            if (r.skipped.isNotEmpty()) append(" · ${r.skipped.size} skipped")
                            if (r.failed.isNotEmpty()) append(" · ${r.failed.size} failed")
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> Unit
            }
        }
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
                        viewModel.lockVault()
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
                viewModel.acknowledgeResult()
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
                        viewModel.acknowledgeResult()
                    }
                ) { Text("OK") }
            },
        )
    }

    // ── Import password dialog (encrypted file detected) ──────────────────────

    val awaitingImport = state as? ImportExportUiState.AwaitingImportPassword
    if (awaitingImport != null) {
        AlertDialog(
            onDismissRequest = {
                importPasswordInput = ""
                importPasswordVisible = false
                viewModel.cancelPendingImport()
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
                        viewModel.importWithPassword(importPasswordInput.toCharArray())
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
                        viewModel.cancelPendingImport()
                    }
                ) { Text("Cancel") }
            },
        )
    }
}
