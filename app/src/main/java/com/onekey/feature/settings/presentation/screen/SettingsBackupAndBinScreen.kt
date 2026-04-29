package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.feature.settings.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupAndBinScreen(
    onBack: () -> Unit,
    onBackup: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val recycleBinRetention by settingsVm.recycleBinRetention.collectAsStateWithLifecycle()
    val isRecycleBinEnabled by settingsVm.isRecycleBinEnabled.collectAsStateWithLifecycle()
    var showRetentionPicker by remember { mutableStateOf(false) }
    var showDisableBinDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Recycle Bin") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
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
            SectionHeader("Backup & Import")
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Backup & Import") },
                    supportingContent = { Text("Export your vault or import credentials from another app") },
                    leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable(onClick = onBackup),
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader("Recycle bin")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
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

                    if (isRecycleBinEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
    }

    if (showDisableBinDialog) {
        AlertDialog(
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
        AlertDialog(
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
