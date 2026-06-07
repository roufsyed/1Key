package com.onekey.feature.settings.presentation.screen

import com.onekey.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import com.onekey.core.presentation.lockaware.LockAwareTextField
import com.onekey.core.presentation.lockaware.SecurePasswordTextField
import com.onekey.core.presentation.lockaware.rememberSecurePasswordFieldState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.onekey.feature.settings.presentation.viewmodel.SettingsAction
import com.onekey.feature.settings.presentation.viewmodel.SettingsDestination
import com.onekey.feature.settings.presentation.viewmodel.SettingsDialogId
import com.onekey.feature.settings.presentation.viewmodel.SettingsEntry
import com.onekey.feature.settings.presentation.viewmodel.SettingsEvent
import com.onekey.feature.settings.presentation.viewmodel.SettingsSearchViewModel
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
    onAutofill: () -> Unit,
    onBackup: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onFaq: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
    searchVm: SettingsSearchViewModel = hiltViewModel(),
) {
    val isHideTopBarOnScroll by settingsVm.isHideTopBarOnScroll.collectAsStateWithLifecycle()
    val isSeedingData by settingsVm.isSeedingData.collectAsStateWithLifecycle()
    val query by searchVm.query.collectAsStateWithLifecycle()
    val searchActive by searchVm.searchActive.collectAsStateWithLifecycle()
    val searchResults by searchVm.results.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteVaultDialog by remember { mutableStateOf(false) }
    val deleteVaultPasswordState = rememberSecurePasswordFieldState()
    var deleteVaultPasswordVisible by remember { mutableStateOf(false) }
    var deleteVaultPasswordError by remember { mutableStateOf<String?>(null) }
    var deleteVaultAttemptsRemaining by remember { mutableIntStateOf(3) }

    LaunchedEffect(Unit) {
        settingsVm.event.collect { event ->
            when (event) {
                SettingsEvent.VaultContentsDeleted -> {
                    showDeleteVaultDialog = false
                    deleteVaultPasswordVisible = false
                    deleteVaultPasswordError = null
                    deleteVaultAttemptsRemaining = 3
                    onVaultReset()
                }
                is SettingsEvent.SeedComplete ->
                    snackbarHostState.showSnackbar("${event.count} sample credentials added")
                is SettingsEvent.TwoFaSeedComplete ->
                    snackbarHostState.showSnackbar(
                        "${event.count} sample 2FA codes added - open the 2FA tab"
                    )
                is SettingsEvent.DeleteVaultConfirmFailed -> {
                    deleteVaultAttemptsRemaining = event.attemptsRemaining
                    deleteVaultPasswordError = if (event.attemptsRemaining == 1)
                        "Wrong master password - 1 attempt remaining before the vault locks."
                    else
                        "Wrong master password - ${event.attemptsRemaining} attempts remaining."
                }
                SettingsEvent.VaultLocked -> {
                    showDeleteVaultDialog = false
                    deleteVaultPasswordState.clear()
                    deleteVaultPasswordVisible = false
                    deleteVaultPasswordError = null
                    deleteVaultAttemptsRemaining = 3
                    // The "Vault Locked" explanation lives on LockScreen via LockReasonStore
                    // - Settings has already left composition by the time the user lands there.
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

    fun handleResultTap(entry: SettingsEntry) {
        focusManager.clearFocus()
        if (entry.highlightKey != null) searchVm.setPendingHighlight(entry.highlightKey)
        searchVm.setSearchActive(false)
        when (val action = entry.action) {
            is SettingsAction.Navigate -> when (action.destination) {
                SettingsDestination.General -> onGeneral()
                SettingsDestination.Security -> onSecurity()
                SettingsDestination.Backup -> onBackup()
                SettingsDestination.Faq -> onFaq()
                SettingsDestination.PrivacyPolicy -> onPrivacyPolicy()
                SettingsDestination.SetupPin -> onSetupPin()
                SettingsDestination.ChangePassword -> onChangePassword()
                SettingsDestination.Autofill -> onAutofill()
            }
            is SettingsAction.OpenDialogOn -> when (action.dialogId) {
                SettingsDialogId.DeleteVault -> showDeleteVaultDialog = true
            }
        }
    }

    BackHandler(enabled = searchActive) {
        searchVm.setSearchActive(false)
    }

    Scaffold(
        modifier = if (searchActive) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (searchActive) {
                SettingsSearchBar(
                    query = query,
                    onQueryChange = { searchVm.updateQuery(it) },
                    onClose = { searchVm.setSearchActive(false) },
                    onClear = { searchVm.updateQuery("") },
                )
            } else {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        if (showBack) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchVm.setSearchActive(true) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search settings")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (searchActive && query.length >= 2) {
            SettingsSearchResults(
                query = query,
                results = searchResults,
                onResultClick = { handleResultTap(it) },
                modifier = Modifier
                    .padding(padding)
                    .imePadding(),
            )
        } else {
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
                        icon = Icons.Default.Keyboard,
                        title = "Autofill",
                        subtitle = "Fill saved logins into other apps and websites",
                        onClick = onAutofill,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsMenuRow(
                        icon = Icons.Default.Backup,
                        title = "Backup & Import",
                        subtitle = "Export your vault or import from another app",
                        onClick = onBackup,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsMenuRow(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
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
                                Text("Adds 8 sample logins each with a working TOTP secret - codes appear in the 2FA tab")
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
                "1Key - version 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
        }
    }

    if (showDeleteVaultDialog) {
        val isVerifyingDeleteVault by settingsVm.isVerifyingDeleteVault.collectAsStateWithLifecycle()
        val canConfirm = !deleteVaultPasswordState.isEmpty && !isVerifyingDeleteVault

        LockAwareDialog(
            onDismissRequest = {
                if (isVerifyingDeleteVault) return@LockAwareDialog
                showDeleteVaultDialog = false
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
                    SecurePasswordTextField(
                        state = deleteVaultPasswordState,
                        onValueChanged = { if (deleteVaultPasswordError != null) deleteVaultPasswordError = null },
                        label = { Text("Master password") },
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
                        settingsVm.deleteVaultContentsWithVerification(deleteVaultPasswordState.consume())
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

@Composable
private fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
            LockAwareTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search settings") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear query")
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchResults(
    query: String,
    results: List<SettingsEntry>,
    onResultClick: (SettingsEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No results for \"$query\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(results, key = { it.sectionLabel + it.title }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.title) },
                    supportingContent = { Text(entry.subtitle) },
                    leadingContent = {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    },
                    trailingContent = { SettingsSectionBadge(entry.sectionLabel) },
                    modifier = Modifier.clickable { onResultClick(entry) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun SettingsSectionBadge(label: String) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
