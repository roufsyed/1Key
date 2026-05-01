package com.onekey.feature.settings.presentation.screen

import com.onekey.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
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
    onGeneral: () -> Unit,
    onSecurity: () -> Unit,
    onBackup: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onFaq: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val isHideTopBarOnScroll by settingsVm.isHideTopBarOnScroll.collectAsStateWithLifecycle()
    val isSeedingData by settingsVm.isSeedingData.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteVaultDialog by remember { mutableStateOf(false) }
    var deleteVaultPassword by remember { mutableStateOf("") }
    var deleteVaultPasswordVisible by remember { mutableStateOf(false) }
    var deleteVaultPasswordError by remember { mutableStateOf<String?>(null) }
    var deleteVaultAttemptsRemaining by remember { mutableIntStateOf(3) }

    LaunchedEffect(Unit) {
        settingsVm.event.collect { event ->
            when (event) {
                SettingsEvent.VaultContentsDeleted -> {
                    showDeleteVaultDialog = false
                    deleteVaultPassword = ""
                    deleteVaultPasswordVisible = false
                    deleteVaultPasswordError = null
                    deleteVaultAttemptsRemaining = 3
                    onVaultReset()
                }
                is SettingsEvent.SeedComplete ->
                    snackbarHostState.showSnackbar("${event.count} sample credentials added")
                is SettingsEvent.TwoFaSeedComplete ->
                    snackbarHostState.showSnackbar(
                        "${event.count} sample 2FA codes added — open the 2FA tab"
                    )
                is SettingsEvent.DeleteVaultConfirmFailed -> {
                    deleteVaultAttemptsRemaining = event.attemptsRemaining
                    deleteVaultPasswordError = if (event.attemptsRemaining == 1)
                        "Wrong master password — 1 attempt remaining before the vault locks."
                    else
                        "Wrong master password — ${event.attemptsRemaining} attempts remaining."
                }
                SettingsEvent.VaultLocked -> {
                    showDeleteVaultDialog = false
                    deleteVaultPassword = ""
                    deleteVaultPasswordVisible = false
                    deleteVaultPasswordError = null
                    deleteVaultAttemptsRemaining = 3
                    // The "Vault Locked" explanation lives on LockScreen via LockReasonStore
                    // — Settings has already left composition by the time the user lands there.
                }
                is SettingsEvent.Error -> snackbarHostState.showSnackbar(event.message)
                else -> Unit // PinReset, BiometricEnabled, BiometricConfirmFailed handled in Security subscreen
            }
        }
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = topAppBarState,
        canScroll = { isHideTopBarOnScroll },
    )
    LaunchedEffect(isHideTopBarOnScroll) {
        if (!isHideTopBarOnScroll) {
            topAppBarState.heightOffset = 0f
            topAppBarState.contentOffset = 0f
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                    }
                },
                scrollBehavior = scrollBehavior,
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsMenuRow(
                        icon = Icons.Default.Tune,
                        title = "General",
                        subtitle = "Theme, layout, categories",
                        onClick = onGeneral,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsMenuRow(
                        icon = Icons.Default.Lock,
                        title = "Security",
                        subtitle = "Unlock methods, auto-lock, master password",
                        onClick = onSecurity,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsMenuRow(
                        icon = Icons.Default.Backup,
                        title = "Backup",
                        subtitle = "Export your vault or import from another app",
                        onClick = onBackup,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsMenuRow(
                        icon = Icons.Default.HelpOutline,
                        title = "FAQ",
                        subtitle = "How encryption, privacy, and security work in 1Key",
                        onClick = onFaq,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsMenuRow(
                        icon = Icons.Default.PrivacyTip,
                        title = "Privacy Policy",
                        subtitle = "What we collect, what we don't, and why",
                        onClick = onPrivacyPolicy,
                    )
                }
            }

            // ── Developer Options (debug builds only) ─────────────────────────
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Developer Options")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
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
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("Seed Dummy 2FA Codes") },
                            supportingContent = {
                                Text("Adds 8 sample logins each with a working TOTP secret — codes appear in the 2FA tab")
                            },
                            leadingContent = {
                                if (isSeedingData) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Security, contentDescription = null)
                                }
                            },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable(enabled = !isSeedingData) { settingsVm.seedTwoFaData() },
                        )
                    }
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

            Spacer(Modifier.height(16.dp))
            Text(
                "Your vault is encrypted and stored only on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(
                "1Key — version 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteVaultDialog) {
        val isVerifyingDeleteVault by settingsVm.isVerifyingDeleteVault.collectAsStateWithLifecycle()
        val canConfirm = deleteVaultPassword.isNotEmpty() && !isVerifyingDeleteVault

        LockAwareDialog(
            onDismissRequest = {
                if (isVerifyingDeleteVault) return@LockAwareDialog
                showDeleteVaultDialog = false
                deleteVaultPassword = ""
                deleteVaultPasswordVisible = false
                deleteVaultPasswordError = null
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
                    Text(
                        "Enter your master password to confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LockAwareOutlinedTextField(
                        value = deleteVaultPassword,
                        onValueChange = {
                            deleteVaultPassword = it
                            if (deleteVaultPasswordError != null) deleteVaultPasswordError = null
                        },
                        label = { Text("Master password") },
                        singleLine = true,
                        enabled = !isVerifyingDeleteVault,
                        isError = deleteVaultPasswordError != null,
                        visualTransformation = if (deleteVaultPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { deleteVaultPasswordVisible = !deleteVaultPasswordVisible }) {
                                Icon(
                                    if (deleteVaultPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    deleteVaultPasswordError?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        settingsVm.deleteVaultContentsWithVerification(deleteVaultPassword.toCharArray())
                    },
                    enabled = canConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (isVerifyingDeleteVault) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        Text("Delete Vault")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isVerifyingDeleteVault,
                    onClick = {
                        showDeleteVaultDialog = false
                        deleteVaultPassword = ""
                        deleteVaultPasswordVisible = false
                        deleteVaultPasswordError = null
                    }
                ) { Text("Keep My Credentials") }
            },
        )
    }
}

@Composable
private fun SettingsMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
