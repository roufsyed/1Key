package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.feature.settings.presentation.viewmodel.SettingsHighlightKeys
import com.onekey.feature.settings.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGeneralScreen(
    onBack: () -> Unit,
    onManageCategories: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val isDarkTheme by settingsVm.isDarkTheme.collectAsStateWithLifecycle()
    val isShowFavourites by settingsVm.isShowFavourites.collectAsStateWithLifecycle()
    val isHideTopBarOnScroll by settingsVm.isHideTopBarOnScroll.collectAsStateWithLifecycle()
    val isVaultFooterVisible by settingsVm.isVaultFooterVisible.collectAsStateWithLifecycle()
    val recycleBinRetention by settingsVm.recycleBinRetention.collectAsStateWithLifecycle()
    val isRecycleBinEnabled by settingsVm.isRecycleBinEnabled.collectAsStateWithLifecycle()
    val highlightKey by settingsVm.highlightKey.collectAsStateWithLifecycle()
    var showRetentionPicker by rememberSaveable { mutableStateOf(false) }
    var showDisableBinDialog by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { settingsVm.clearHighlight() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("General") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Hide top bar on scroll") },
                        supportingContent = {
                            Text(
                                if (isHideTopBarOnScroll) "Top bar collapses as you scroll lists"
                                else "Top bar stays pinned while scrolling"
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.UnfoldLess, contentDescription = null)
                        },
                        trailingContent = {
                            Switch(
                                checked = isHideTopBarOnScroll,
                                onCheckedChange = { settingsVm.setHideTopBarOnScroll(it) },
                            )
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Show privacy footer") },
                        supportingContent = {
                            Text(
                                if (isVaultFooterVisible)
                                    "\"Your vault is encrypted and stored only on this device.\" appears below the vault list"
                                else
                                    "Footer hidden from the vault list",
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Shield, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = isVaultFooterVisible,
                                onCheckedChange = { settingsVm.setVaultFooterVisible(it) },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Recycle bin")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.RECYCLE_BIN,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Use recycle bin") },
                            supportingContent = {
                                Text(
                                    if (isRecycleBinEnabled)
                                        "Deleted credentials wait in the bin so you can restore them if you change your mind."
                                    else
                                        "Off — every delete is permanent the moment you confirm it. There's no undo.",
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                            trailingContent = {
                                Switch(
                                    checked = isRecycleBinEnabled,
                                    onCheckedChange = { newValue ->
                                        if (!newValue) {
                                            showDisableBinDialog = true
                                        } else {
                                            settingsVm.setRecycleBinEnabled(true)
                                        }
                                    },
                                )
                            },
                        )
                    }

                    if (isRecycleBinEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        HighlightableRow(
                            isHighlighted = highlightKey == SettingsHighlightKeys.RECYCLE_BIN_RETENTION,
                            onHighlightConsumed = settingsVm::clearHighlight,
                        ) {
                            ListItem(
                                headlineContent = { Text("Auto-clear after") },
                                supportingContent = {
                                    Text(
                                        if (recycleBinRetention == RecycleBinRetention.NEVER)
                                            "Items stay in the bin until you remove them"
                                        else
                                            "Deleted items are removed for good after ${recycleBinRetention.label.lowercase()}",
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                                trailingContent = { Text(recycleBinRetention.label) },
                                modifier = Modifier.clickable { showRetentionPicker = true },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Categories")
            Card(modifier = Modifier.fillMaxWidth()) {
                HighlightableRow(
                    isHighlighted = highlightKey == SettingsHighlightKeys.MANAGE_CATEGORIES,
                    onHighlightConsumed = settingsVm::clearHighlight,
                ) {
                    ListItem(
                        headlineContent = { Text("Manage categories") },
                        supportingContent = { Text("Add or remove credential categories") },
                        leadingContent = { Icon(Icons.Default.LocalOffer, contentDescription = null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable(onClick = onManageCategories),
                    )
                }
            }
        }
    }

    if (showDisableBinDialog) {
        LockAwareDialog(
            onDismissRequest = { showDisableBinDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Turn off recycle bin?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "From now on, deleting a credential removes it immediately. " +
                            "There'll be no way to undo it — not even from this device.",
                    )
                    Text(
                        "Anything already in the bin stays there. You can restore or empty those " +
                            "items normally, and turning the bin back on later restores the safety net for future deletes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsVm.setRecycleBinEnabled(false)
                        showDisableBinDialog = false
                    },
                ) {
                    Text("Turn it off", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableBinDialog = false }) {
                    Text("Keep it on")
                }
            },
        )
    }

    if (showRetentionPicker) {
        LockAwareDialog(
            onDismissRequest = { showRetentionPicker = false },
            icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
            title = { Text("Auto-clear recycle bin") },
            text = {
                Column {
                    Text(
                        "How long should deleted credentials wait in the bin before being removed?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    RecycleBinRetention.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsVm.setRecycleBinRetention(option)
                                    showRetentionPicker = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = recycleBinRetention == option,
                                onClick = {
                                    settingsVm.setRecycleBinRetention(option)
                                    showRetentionPicker = false
                                },
                            )
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                if (option == RecycleBinRetention.DAYS_30) {
                                    Text(
                                        "Default — recommended",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (option == RecycleBinRetention.NEVER) {
                                    Text(
                                        "You'll need to empty the bin manually",
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
                TextButton(onClick = { showRetentionPicker = false }) { Text("Done") }
            },
        )
    }
}
