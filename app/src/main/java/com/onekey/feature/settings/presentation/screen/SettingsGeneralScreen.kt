package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.onekey.core.presentation.util.oneKeyTopBarColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.RecycleBinRetention
import com.onekey.core.domain.model.ThemeMode
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
    val themeMode by settingsVm.themeMode.collectAsStateWithLifecycle()
    val isShowFavourites by settingsVm.isShowFavourites.collectAsStateWithLifecycle()
    val isHideTopBarOnScroll by settingsVm.isHideTopBarOnScroll.collectAsStateWithLifecycle()
    val isVaultFooterVisible by settingsVm.isVaultFooterVisible.collectAsStateWithLifecycle()
    val isNotesRenderMarkdownEnabled by settingsVm.isNotesRenderMarkdownEnabled.collectAsStateWithLifecycle()
    val recycleBinRetention by settingsVm.recycleBinRetention.collectAsStateWithLifecycle()
    val isRecycleBinEnabled by settingsVm.isRecycleBinEnabled.collectAsStateWithLifecycle()
    val highlightKey by settingsVm.highlightKey.collectAsStateWithLifecycle()
    var showThemePicker by rememberSaveable { mutableStateOf(false) }
    var showRetentionPicker by rememberSaveable { mutableStateOf(false) }
    var showDisableBinDialog by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { settingsVm.clearHighlight() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
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
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.DARK_THEME,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        // Custom Row instead of M3 ListItem: ListItem flips to a
                        // 3-line layout when the supporting text wraps, and in
                        // that layout the trailing content is top-aligned by
                        // spec (not configurable). A Row with CenterVertically
                        // keeps the trailing value visually centred regardless
                        // of how many lines the supporting text takes. Spacing
                        // and typography are picked to match the neighbouring
                        // M3 ListItems so the row reads as part of the group.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Match M3 ListItem's default container so this
                                // row reads as part of the same list as the
                                // ListItems below it (M3 1.3.2 ListItem reads
                                // `surface` from ListTokens.ListItemContainerColor).
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { showThemePicker = true }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = when (themeMode) {
                                    ThemeMode.DARK -> Icons.Default.DarkMode
                                    ThemeMode.LIGHT -> Icons.Default.LightMode
                                    ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                },
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    when (themeMode) {
                                        ThemeMode.SYSTEM -> "Matches the device theme"
                                        ThemeMode.LIGHT -> "Always light"
                                        ThemeMode.DARK -> "Always dark"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                themeMode.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.SHOW_FAVOURITES,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.HIDE_TOP_BAR_ON_SCROLL,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
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
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.VAULT_FOOTER,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
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
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.NOTES_RENDER_MARKDOWN,
                        onHighlightConsumed = settingsVm::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Render markdown in notes") },
                            supportingContent = {
                                Text("Format headings, lists, links, and code in your notes.")
                            },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = null)
                            },
                            trailingContent = {
                                Switch(
                                    checked = isNotesRenderMarkdownEnabled,
                                    onCheckedChange = { settingsVm.setNotesRenderMarkdownEnabled(it) },
                                )
                            },
                        )
                    }
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
                                        "Off - every delete is permanent the moment you confirm it. There's no undo.",
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
                            "There'll be no way to undo it - not even from this device.",
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

    if (showThemePicker) {
        LockAwareDialog(
            onDismissRequest = { showThemePicker = false },
            icon = {
                Icon(
                    when (themeMode) {
                        ThemeMode.DARK -> Icons.Default.DarkMode
                        ThemeMode.LIGHT -> Icons.Default.LightMode
                        ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                    },
                    contentDescription = null,
                )
            },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeMode.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsVm.setThemeMode(option)
                                    showThemePicker = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = themeMode == option,
                                onClick = {
                                    settingsVm.setThemeMode(option)
                                    showThemePicker = false
                                },
                            )
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when (option) {
                                        ThemeMode.SYSTEM -> "Match the device theme"
                                        ThemeMode.LIGHT -> "Always light"
                                        ThemeMode.DARK -> "Always dark"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) { Text("Done") }
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
                                        "Default - recommended",
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
