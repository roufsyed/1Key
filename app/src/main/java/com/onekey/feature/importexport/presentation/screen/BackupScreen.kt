package com.onekey.feature.importexport.presentation.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import com.onekey.core.presentation.lockaware.LockAwareWindowDialog
import com.onekey.feature.importexport.domain.ConflictResolution
import com.onekey.feature.importexport.domain.ImportFieldOptions
import com.onekey.feature.importexport.domain.ImportPlan
import com.onekey.feature.importexport.domain.ImportResult
import com.onekey.feature.importexport.domain.SkipReason
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportEvent
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportUiState
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onNavigateToVault: () -> Unit = {},
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Hoisted to screen scope so the post-import success snackbar isn't launched on a
    // scope that's already being torn down by `importDialogOpen = false`.
    val screenScope = rememberCoroutineScope()
    // Once an ImportPreview state is observed, the dialog stays open across Loading,
    // ImportSuccess, and Error transitions until the user explicitly dismisses it.
    var importDialogOpen by remember { mutableStateOf(false) }

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
        if (state is ImportExportUiState.ImportPreview) {
            importDialogOpen = true
        }
        // While the import dialog is open it owns its own success/error rendering —
        // suppress the generic result dialog so they don't stack.
        if (importDialogOpen) return@LaunchedEffect
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            val isInteractionBlocked =
                state is ImportExportUiState.Loading || state is ImportExportUiState.ImportPreview

            Button(
                onClick = {
                    if (encryptExport) showExportPasswordDialog = true
                    else {
                        viewModel.notifyPickerLaunched()
                        exportLauncher.launch("1key_backup.${selectedFormat.name.lowercase()}")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isInteractionBlocked,
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
                enabled = !isInteractionBlocked,
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

    // ── Import preview dialog ─────────────────────────────────────────────────

    if (importDialogOpen) {
        ImportPreviewDialog(
            state = state,
            onConfirm = { opts -> viewModel.confirmImport(opts) },
            onCancelPreview = {
                importDialogOpen = false
                viewModel.cancelPendingImport()
            },
            onRetry = { opts -> viewModel.confirmImport(opts) },
            onBackToSelection = { viewModel.returnToImportPreview() },
            onAcknowledgeError = {
                importDialogOpen = false
                viewModel.cancelPendingImport()
            },
            onDone = { result ->
                importDialogOpen = false
                viewModel.acknowledgeResult()
                if (result.imported > 0) {
                    screenScope.launch {
                        snackbarHostState.showSnackbar(
                            message = if (result.imported == 1)
                                "✓ 1 credential added to your vault"
                            else
                                "✓ ${result.imported} credentials added to your vault",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            },
            onViewVault = {
                importDialogOpen = false
                viewModel.acknowledgeResult()
                onNavigateToVault()
            },
            onConfirmResolution = { resolution -> viewModel.confirmConflictResolution(resolution) },
        )
    }

    // ── Disable-encryption dialog ─────────────────────────────────────────────

    if (showDisableEncryptionDialog) {
        LockAwareDialog(
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
                    LockAwareOutlinedTextField(
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
        LockAwareDialog(
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
                    LockAwareOutlinedTextField(
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
        LockAwareDialog(
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
        LockAwareDialog(
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
        LockAwareDialog(
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
                    LockAwareOutlinedTextField(
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
        LockAwareDialog(
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
        LockAwareDialog(
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

// ── Import preview ────────────────────────────────────────────────────────────

private enum class ImportDialogPhase { Preview, Review, Importing, Success, Error, Other }

private fun ImportExportUiState.toDialogPhase(): ImportDialogPhase = when (this) {
    is ImportExportUiState.ImportPreview -> ImportDialogPhase.Preview
    is ImportExportUiState.ImportReview -> ImportDialogPhase.Review
    is ImportExportUiState.Loading -> ImportDialogPhase.Importing
    is ImportExportUiState.ImportSuccess -> ImportDialogPhase.Success
    is ImportExportUiState.Error -> ImportDialogPhase.Error
    else -> ImportDialogPhase.Other
}

@Composable
private fun ImportPreviewDialog(
    state: ImportExportUiState,
    onConfirm: (ImportFieldOptions) -> Unit,
    onCancelPreview: () -> Unit,
    onRetry: (ImportFieldOptions) -> Unit,
    onBackToSelection: () -> Unit,
    onAcknowledgeError: () -> Unit,
    onDone: (ImportResult) -> Unit,
    onViewVault: () -> Unit,
    onConfirmResolution: (ConflictResolution) -> Unit,
) {
    // Capture the most recent ImportPreview so the preview body, opts state, and retry
    // path all have stable parsed/keys context across phase transitions to Loading,
    // ImportSuccess, and Error.
    var capturedPreview by remember { mutableStateOf<ImportExportUiState.ImportPreview?>(null) }
    LaunchedEffect(state) {
        if (state is ImportExportUiState.ImportPreview) capturedPreview = state
    }
    val preview = capturedPreview ?: return

    // Capture the latest plan so the Review body / actions remain stable across the
    // Loading / Success / Error transitions that follow user confirmation.
    var capturedReview by remember { mutableStateOf<ImportExportUiState.ImportReview?>(null) }
    LaunchedEffect(state) {
        if (state is ImportExportUiState.ImportReview) capturedReview = state
    }
    var resolution by remember { mutableStateOf(ConflictResolution.MERGE) }

    var opts by remember(preview) {
        mutableStateOf(ImportFieldOptions(customFieldKeys = preview.customFieldKeys.toSet()))
    }

    val phase = state.toDialogPhase()
    val totalCount = preview.parsed.credentials.size

    LockAwareWindowDialog(
        onDismissRequest = { if (phase == ImportDialogPhase.Preview) onCancelPreview() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Back-press only closes during Preview — other phases require explicit
            // user choice so we don't lose state mid-transaction.
            dismissOnBackPress = phase == ImportDialogPhase.Preview,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ImportDialogHeader(
                    phase = phase,
                    totalCount = totalCount,
                    failedCount = preview.parsed.failed.size,
                    onCancelPreview = onCancelPreview,
                )
                HorizontalDivider()

                // Body — animated phase transitions. Default fade+scale gives a
                // gentle handoff between phases without a structural reflow.
                AnimatedContent(
                    targetState = phase,
                    modifier = Modifier.weight(1f),
                    label = "import dialog phase",
                ) { p ->
                    when (p) {
                        ImportDialogPhase.Preview -> PreviewPhaseBody(
                            preview = preview,
                            opts = opts,
                            onOptsChange = { opts = it },
                        )
                        ImportDialogPhase.Review -> {
                            val review = capturedReview
                            if (review != null) ReviewPhaseBody(
                                plan = review.plan,
                                resolution = resolution,
                                onResolutionChange = { resolution = it },
                            ) else Box(Modifier.fillMaxSize())
                        }
                        ImportDialogPhase.Importing -> ImportingPhaseBody()
                        ImportDialogPhase.Success -> {
                            val res = (state as? ImportExportUiState.ImportSuccess)?.result
                            if (res != null) SuccessPhaseBody(res) else Box(Modifier.fillMaxSize())
                        }
                        ImportDialogPhase.Error -> {
                            val msg = (state as? ImportExportUiState.Error)?.message
                                ?: "Import failed"
                            ErrorPhaseBody(message = msg)
                        }
                        ImportDialogPhase.Other -> Box(Modifier.fillMaxSize())
                    }
                }

                HorizontalDivider()
                ImportDialogActions(
                    phase = phase,
                    totalCount = totalCount,
                    onCancelPreview = onCancelPreview,
                    onConfirm = { onConfirm(opts) },
                    onConfirmResolution = { onConfirmResolution(resolution) },
                    onRetry = { onRetry(opts) },
                    onBackToSelection = onBackToSelection,
                    onAcknowledgeError = onAcknowledgeError,
                    onDone = {
                        (state as? ImportExportUiState.ImportSuccess)
                            ?.let { onDone(it.result) }
                    },
                    onViewVault = onViewVault,
                )
            }
        }
    }
}

@Composable
private fun ImportDialogHeader(
    phase: ImportDialogPhase,
    totalCount: Int,
    failedCount: Int,
    onCancelPreview: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when (phase) {
                    ImportDialogPhase.Review -> "Review import"
                    ImportDialogPhase.Importing -> "Importing"
                    ImportDialogPhase.Success -> "Import complete"
                    ImportDialogPhase.Error -> "Import failed"
                    else -> "Import Preview"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (phase == ImportDialogPhase.Preview) {
                Text(
                    buildString {
                        append(if (totalCount == 1) "1 credential" else "$totalCount credentials")
                        if (failedCount > 0) append(" · $failedCount failed to parse")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (phase == ImportDialogPhase.Preview) {
            IconButton(onClick = onCancelPreview) {
                Icon(Icons.Default.Close, contentDescription = "Cancel import")
            }
        }
    }
}

@Composable
private fun ImportDialogActions(
    phase: ImportDialogPhase,
    totalCount: Int,
    onCancelPreview: () -> Unit,
    onConfirm: () -> Unit,
    onConfirmResolution: () -> Unit,
    onRetry: () -> Unit,
    onBackToSelection: () -> Unit,
    onAcknowledgeError: () -> Unit,
    onDone: () -> Unit,
    onViewVault: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (phase) {
            ImportDialogPhase.Preview -> {
                OutlinedButton(onClick = onCancelPreview, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = totalCount > 0,
                ) {
                    Text(
                        if (totalCount == 1) "Import 1 Credential"
                        else "Import $totalCount Credentials"
                    )
                }
            }
            ImportDialogPhase.Review -> {
                OutlinedButton(onClick = onCancelPreview, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(onClick = onConfirmResolution, modifier = Modifier.weight(1f)) {
                    Text("Continue")
                }
            }
            ImportDialogPhase.Importing -> {
                // No actions while the save is in flight — leaving the dialog mid-write
                // could orphan partial state.
                Spacer(modifier = Modifier.weight(1f).height(40.dp))
            }
            ImportDialogPhase.Success -> {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("Done")
                }
                Button(onClick = onViewVault, modifier = Modifier.weight(1f)) {
                    Text("View Vault")
                }
            }
            ImportDialogPhase.Error -> {
                // Three options on error: drop the import, return to the toggles to
                // adjust selection (often fixes opt-related failures), or retry as-is.
                OutlinedButton(onClick = onAcknowledgeError, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                OutlinedButton(onClick = onBackToSelection, modifier = Modifier.weight(1f)) {
                    Text("Back")
                }
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Text("Retry")
                }
            }
            ImportDialogPhase.Other -> {
                Spacer(modifier = Modifier.weight(1f).height(40.dp))
            }
        }
    }
}

@Composable
private fun PreviewPhaseBody(
    preview: ImportExportUiState.ImportPreview,
    opts: ImportFieldOptions,
    onOptsChange: (ImportFieldOptions) -> Unit,
) {
    val totalCount = preview.parsed.credentials.size
    Column(modifier = Modifier.fillMaxSize()) {
        if (preview.previewItems.isNotEmpty()) {
            PreviewCarousel(
                items = preview.previewItems,
                opts = opts,
                sensitiveCustomFieldKeys = preview.sensitiveCustomFieldKeys,
            )
            HorizontalDivider()
        } else if (totalCount == 0) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No credentials were found in this file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Fields to import",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    FieldToggleRow("Username", Icons.Default.Person, opts.username) {
                        onOptsChange(opts.copy(username = it))
                    }
                    FieldToggleRow("Password", Icons.Default.Lock, opts.password) {
                        onOptsChange(opts.copy(password = it))
                    }
                    FieldToggleRow("Website URL", Icons.Default.Language, opts.url) {
                        onOptsChange(opts.copy(url = it))
                    }
                    FieldToggleRow("Notes", Icons.Default.StickyNote2, opts.notes) {
                        onOptsChange(opts.copy(notes = it))
                    }
                    FieldToggleRow("2FA / TOTP secret", Icons.Default.Shield, opts.totp) {
                        onOptsChange(opts.copy(totp = it))
                    }
                    FieldToggleRow("Categories / tags", Icons.Default.Label, opts.tags) {
                        onOptsChange(opts.copy(tags = it))
                    }
                    if (preview.customFieldKeys.isEmpty()) {
                        FieldToggleRow(
                            label = "Custom fields (none in file)",
                            icon = Icons.Default.Tune,
                            checked = false,
                            enabled = false,
                        ) {}
                    } else {
                        val allCfSelected = preview.customFieldKeys.all { it in opts.customFieldKeys }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (allCfSelected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Text(
                                "Custom fields (${preview.customFieldKeys.size})",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                color = if (allCfSelected) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    onOptsChange(
                                        if (allCfSelected) opts.copy(customFieldKeys = emptySet())
                                        else opts.copy(customFieldKeys = preview.customFieldKeys.toSet())
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    if (allCfSelected) "None" else "All",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        preview.customFieldKeys.forEach { key ->
                            val isSensitive = key in preview.sensitiveCustomFieldKeys
                            FieldToggleRow(
                                label = key,
                                icon = if (isSensitive) Icons.Default.Lock else Icons.Default.Tune,
                                checked = key in opts.customFieldKeys,
                                indent = true,
                            ) { checked ->
                                onOptsChange(
                                    if (checked) opts.copy(customFieldKeys = opts.customFieldKeys + key)
                                    else opts.copy(customFieldKeys = opts.customFieldKeys - key)
                                )
                            }
                        }
                    }
                    FieldToggleRow("Favourites", Icons.Default.Favorite, opts.isFavorite) {
                        onOptsChange(opts.copy(isFavorite = it))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportingPhaseBody() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
            Text(
                "Adding credentials to your vault…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ReviewPhaseBody(
    plan: ImportPlan,
    resolution: ConflictResolution,
    onResolutionChange: (ConflictResolution) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Here's what we'll do with your file:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReviewSummaryRow(
            icon = Icons.Default.Add,
            color = MaterialTheme.colorScheme.primary,
            count = plan.newItems.size,
            singular = "new credential will be added",
            plural = "new credentials will be added",
        )
        if (plan.autoMerges.isNotEmpty()) {
            ReviewSummaryRow(
                icon = Icons.Default.Sync,
                color = MaterialTheme.colorScheme.primary,
                count = plan.autoMerges.size,
                singular = "existing credential will be enriched",
                plural = "existing credentials will be enriched",
                supporting = "Your file fills in fields you didn't have yet.",
            )
        }
        if (plan.skipped.isNotEmpty()) {
            ReviewSummaryRow(
                icon = Icons.Default.RemoveCircleOutline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                count = plan.skipped.size,
                singular = "duplicate ID skipped",
                plural = "duplicate IDs skipped",
            )
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        Text(
            buildString {
                append(plan.conflicts.size)
                append(" credential")
                if (plan.conflicts.size != 1) append("s")
                append(" already exist")
                if (plan.conflicts.size == 1) append("s")
                append(" with different values.")
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Choose what to do with these:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ResolutionOptionRow(
            selected = resolution == ConflictResolution.MERGE,
            title = "Merge with existing items",
            description = "Keep your current values. The import only fills in fields you didn't have.",
            onSelect = { onResolutionChange(ConflictResolution.MERGE) },
        )
        ResolutionOptionRow(
            selected = resolution == ConflictResolution.ADD_AS_SEPARATE,
            title = "Add as separate items",
            description = "Keep both versions side by side. Your existing items are untouched.",
            onSelect = { onResolutionChange(ConflictResolution.ADD_AS_SEPARATE) },
        )

        if (plan.conflicts.size <= 5) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Items in conflict:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.conflicts.forEach { conflict ->
                Text(
                    "• ${conflict.existing.title.ifBlank { "(untitled)" }} — " +
                        conflict.conflictingFields.joinToString(", ") + " differ" +
                        if (conflict.restoreFromBin) " · in recycle bin" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewSummaryRow(
    icon: ImageVector,
    color: Color,
    count: Int,
    singular: String,
    plural: String,
    supporting: String? = null,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$count ${if (count == 1) singular else plural}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResolutionOptionRow(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SuccessPhaseBody(result: ImportResult) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedCheckmark(size = 88.dp)
        Spacer(Modifier.height(4.dp))
        CountUpText(
            target = result.imported,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (result.imported == 1) "credential imported" else "credentials imported",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.skipped.isNotEmpty() || result.failed.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            ResultBreakdownCard(result = result)
        }
    }
}

@Composable
private fun ErrorPhaseBody(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PriorityHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                "We couldn't finish the import",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                "Your data is unchanged. Try again or cancel — your file selection and choices are preserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ResultBreakdownCard(result: ImportResult) {
    var skippedExpanded by remember { mutableStateOf(false) }
    var failedExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            BreakdownRow(
                icon = Icons.Default.CheckCircle,
                tint = MaterialTheme.colorScheme.primary,
                label = "Imported",
                count = result.imported,
                expandable = false,
                expanded = false,
                onToggleExpand = {},
                detail = {},
            )
            if (result.skipped.isNotEmpty()) {
                BreakdownRow(
                    icon = Icons.Default.Block,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Skipped (already in vault)",
                    count = result.skipped.size,
                    expandable = true,
                    expanded = skippedExpanded,
                    onToggleExpand = { skippedExpanded = !skippedExpanded },
                    detail = {
                        Column {
                            result.skipped.forEach { sk ->
                                Text(
                                    sk.title.ifBlank { "(no title)" } +
                                        if (sk.username.isNotBlank()) " — ${sk.username}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 48.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                                )
                            }
                        }
                    },
                )
            }
            if (result.failed.isNotEmpty()) {
                BreakdownRow(
                    icon = Icons.Default.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    label = "Failed to parse",
                    count = result.failed.size,
                    expandable = true,
                    expanded = failedExpanded,
                    onToggleExpand = { failedExpanded = !failedExpanded },
                    detail = {
                        Column {
                            result.failed.forEach { f ->
                                Text(
                                    "Row ${f.rowIndex}: ${f.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 48.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    count: Int,
    expandable: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    detail: @Composable () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (expandable) it.clickable(onClick = onToggleExpand) else it }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(count.toString(), style = MaterialTheme.typography.titleSmall, color = tint)
            if (expandable) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded) { detail() }
    }
}

@Composable
private fun AnimatedCheckmark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 64.dp,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(
            color = color.copy(alpha = 0.12f),
            radius = w / 2,
            center = Offset(w / 2, h / 2),
        )
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            lineTo(w * 0.45f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.36f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * progress.value, partial, true)
        drawPath(
            partial,
            color = color,
            style = Stroke(
                width = w * 0.085f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun CountUpText(
    target: Int,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = LocalContentColor.current,
) {
    val animated by animateIntAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "count up",
    )
    Text(
        text = animated.toString(),
        style = style,
        color = color,
        modifier = modifier,
    )
}

/**
 * Horizontally scrolling preview band. Cards are 82% of dialog width so the next card
 * "peeks" in — that's the affordance for "swipe to see more". Pager counter shows
 * "n of total" below the row when there's more than one sample.
 */
@Composable
private fun PreviewCarousel(
    items: List<Credential>,
    opts: ImportFieldOptions,
    sensitiveCustomFieldKeys: Set<String>,
) {
    val listState = rememberLazyListState()
    val currentIndex by remember(items) {
        derivedStateOf {
            (listState.firstVisibleItemIndex + 1).coerceAtMost(items.size).coerceAtLeast(1)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(280.dp),
        ) {
            val cardWidth = maxWidth * 0.82f
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items) { credential ->
                    PreviewCredentialCard(
                        credential = credential,
                        opts = opts,
                        sensitiveCustomFieldKeys = sensitiveCustomFieldKeys,
                        modifier = Modifier.width(cardWidth).fillMaxHeight(),
                    )
                }
            }
        }
        if (items.size > 1) {
            Text(
                "$currentIndex of ${items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun FieldToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    indent: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    val startPad = if (indent) 32.dp else 16.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .padding(start = startPad, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                checked -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                checked -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.height(24.dp),
        )
    }
}

@Composable
private fun PreviewCredentialCard(
    credential: Credential,
    opts: ImportFieldOptions,
    sensitiveCustomFieldKeys: Set<String>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Title row with type icon — always shown.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = previewTypeIcon(credential.type),
                    contentDescription = credential.type.displayName,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    credential.title.ifBlank { "(no title)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (opts.isFavorite && credential.isFavorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Each row wraps in AnimatedVisibility so toggle flips animate the row in/out
            // while the user can see the card. This is the "live cross-talk" — the panel
            // below the card (toggles) directly drives motion in the card above.
            AnimatedRow(visible = opts.username && credential.username.isNotBlank()) {
                PreviewFieldRow(Icons.Default.Person, credential.username)
            }
            AnimatedRow(visible = opts.password && credential.password.isNotBlank()) {
                PreviewFieldRow(Icons.Default.Lock, "••••••••")
            }
            AnimatedRow(visible = opts.url && credential.url.isNotBlank()) {
                PreviewFieldRow(Icons.Default.Language, credential.url)
            }
            AnimatedRow(visible = opts.notes && credential.notes.isNotBlank()) {
                val preview = if (credential.notes.length > 60)
                    credential.notes.take(60) + "…" else credential.notes
                PreviewFieldRow(Icons.Default.StickyNote2, preview)
            }
            AnimatedRow(visible = opts.totp && credential.otpParams != null) {
                PreviewFieldRow(Icons.Default.Shield, "2FA / TOTP enabled")
            }
            AnimatedRow(visible = opts.tags && credential.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    credential.tags.take(3).forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    if (credential.tags.size > 3) {
                        Text(
                            "+${credential.tags.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            credential.customFields.forEach { field ->
                AnimatedRow(visible = field.key in opts.customFieldKeys) {
                    val sensitive = field.isSensitive || field.key in sensitiveCustomFieldKeys
                    PreviewFieldRow(
                        icon = if (sensitive) Icons.Default.Lock else Icons.Default.Tune,
                        value = "${field.key}: ${if (field.isSensitive) "••••••••" else field.value}",
                    )
                }
            }

            // Empty-state — shown when every optional field is toggled off.
            val anyVisible = (opts.username && credential.username.isNotBlank()) ||
                (opts.password && credential.password.isNotBlank()) ||
                (opts.url && credential.url.isNotBlank()) ||
                (opts.notes && credential.notes.isNotBlank()) ||
                (opts.totp && credential.otpParams != null) ||
                (opts.tags && credential.tags.isNotEmpty()) ||
                credential.customFields.any { it.key in opts.customFieldKeys }
            AnimatedRow(visible = !anyVisible) {
                Text(
                    "Only the title will be imported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun AnimatedRow(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        content()
    }
}

// Local copy of the type→icon mapping used by CredentialDetailScreen. Kept private here
// so this screen has no cross-feature dependency; if a third caller appears, lift to a util.
private fun previewTypeIcon(type: CredentialType) = when (type) {
    CredentialType.LOGIN -> Icons.Default.Lock
    CredentialType.SECURE_NOTE -> Icons.Default.Description
    CredentialType.CREDIT_CARD -> Icons.Default.CreditCard
    CredentialType.PASSWORD -> Icons.Default.Key
    CredentialType.BANK_ACCOUNT -> Icons.Default.AccountBalance
    CredentialType.DATABASE -> Icons.Default.Storage
    CredentialType.EMAIL -> Icons.Default.Email
    CredentialType.SERVER -> Icons.Default.Computer
    CredentialType.OTHER -> Icons.Default.Label
}

@Composable
private fun PreviewFieldRow(icon: ImageVector, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
