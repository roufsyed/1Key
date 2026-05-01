package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.Tag
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.feature.settings.presentation.viewmodel.SettingsEvent
import com.onekey.feature.settings.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCategoriesScreen(
    onBack: () -> Unit,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val tags by settingsVm.tags.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var newTagName by remember { mutableStateOf("") }
    var showAddTag by remember { mutableStateOf(false) }
    var tagToDelete by remember { mutableStateOf<Tag?>(null) }

    LaunchedEffect(Unit) {
        settingsVm.event.collect { event ->
            if (event is SettingsEvent.Error) {
                snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
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
        }
    }

    tagToDelete?.let { tag ->
        LockAwareDialog(
            onDismissRequest = { tagToDelete = null },
            icon = {
                Icon(
                    Icons.Default.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("Remove “${tag.name}”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The “${tag.name}” category will be permanently removed.")
                    Text(
                        "Any passwords currently tagged with it will have the category quietly " +
                            "stripped away — your passwords themselves stay completely safe " +
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
