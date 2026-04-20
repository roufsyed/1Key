package com.onekey.feature.vault.presentation.screen

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.CustomField
import com.onekey.core.domain.model.Tag
import com.onekey.core.presentation.util.toFormattedDateTime
import com.onekey.core.presentation.util.toRelativeTime
import com.onekey.feature.twofa.presentation.screen.TotpWidget
import com.onekey.feature.vault.presentation.viewmodel.CredentialDetailUiState
import com.onekey.feature.vault.presentation.viewmodel.CredentialDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: CredentialDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        when (uiState) {
            is CredentialDetailUiState.Saved -> onBack()
            is CredentialDetailUiState.Deleted -> onDeleted()
            else -> Unit
        }
    }

    when (val state = uiState) {
        is CredentialDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is CredentialDetailUiState.Success -> {
            if (state.isEditing) {
                CredentialEditContent(
                    credential = state.credential,
                    availableTags = availableTags,
                    onSave = viewModel::save,
                    onAddTag = viewModel::addTag,
                    onBack = onBack,
                )
            } else {
                CredentialViewContent(
                    credential = state.credential,
                    history = history,
                    onEdit = viewModel::startEditing,
                    onDelete = viewModel::delete,
                    onBack = onBack,
                    onCopyPassword = viewModel::copyPassword,
                    onCopyUsername = viewModel::copyUsername,
                    onToggleFavorite = viewModel::toggleFavorite,
                )
            }
        }
        is CredentialDetailUiState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onBack) { Text("Go back") }
                }
            }
        }
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialViewContent(
    credential: Credential,
    history: List<CredentialHistoryEntry>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onCopyPassword: (String) -> Unit,
    onCopyUsername: (String) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(credential.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (credential.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (credential.isFavorite) "Remove from favourites" else "Add to favourites",
                            tint = if (credential.isFavorite) MaterialTheme.colorScheme.primary
                                   else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, "Delete") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (credential.username.isNotEmpty()) {
                DetailField(label = "Username", value = credential.username) { onCopyUsername(credential.username) }
            }
            if (credential.password.isNotEmpty()) {
                DetailField(
                    label = "Password",
                    value = if (showPassword) credential.password else "••••••••",
                    onCopy = { onCopyPassword(credential.password) },
                    trailing = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    }
                )
            }
            if (credential.url.isNotEmpty()) DetailField("URL", credential.url)
            if (credential.notes.isNotEmpty()) DetailField("Notes", credential.notes)
            if (credential.totpSecret != null) {
                TotpWidget(secret = credential.totpSecret)
            }
            credential.customFields.forEach { field ->
                DetailField(
                    label = field.key,
                    value = if (field.isSensitive) "••••••••" else field.value,
                )
            }

            if (credential.tags.isNotEmpty()) {
                TagsViewCard(tags = credential.tags)
            }

            // Metadata card
            MetadataCard(credential = credential)

            // History section
            if (history.isNotEmpty()) {
                HistorySection(
                    history = history,
                    expanded = historyExpanded,
                    onToggle = { historyExpanded = !historyExpanded },
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete credential?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MetadataCard(credential: Credential) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (credential.createdAt > 0L) {
                Text(
                    "Created: ${credential.createdAt.toFormattedDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (credential.updatedAt > 0L) {
                Text(
                    "Modified: ${credential.updatedAt.toFormattedDateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistorySection(
    history: List<CredentialHistoryEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("History", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${history.size} ${if (history.size == 1) "revision" else "revisions"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse history" else "Expand history",
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider()
                    history.forEachIndexed { index, entry ->
                        HistoryEntryRow(entry = entry)
                        if (index < history.lastIndex) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(entry: CredentialHistoryEntry) {
    var showPassword by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            entry.modifiedAt.toRelativeTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (entry.title.isNotEmpty()) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
        }
        if (entry.username.isNotEmpty()) {
            Text(
                "User: ${entry.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.password.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showPassword) "Pass: ${entry.password}" else "Pass: ••••••••",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (entry.url.isNotEmpty()) {
            Text(
                "URL: ${entry.url}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            trailing?.invoke()
            if (onCopy != null) {
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialEditContent(
    credential: Credential,
    availableTags: List<Tag>,
    onSave: (Credential) -> Unit,
    onAddTag: (String) -> Unit,
    onBack: () -> Unit,
) {
    var title by remember(credential.id) { mutableStateOf(credential.title) }
    var username by remember(credential.id) { mutableStateOf(credential.username) }
    var password by remember(credential.id) { mutableStateOf(credential.password) }
    var url by remember(credential.id) { mutableStateOf(credential.url) }
    var notes by remember(credential.id) { mutableStateOf(credential.notes) }
    var totpSecret by remember(credential.id) { mutableStateOf(credential.totpSecret ?: "") }
    var selectedTags by remember(credential.id) { mutableStateOf(credential.tags) }
    var customFields by remember(credential.id) { mutableStateOf(credential.customFields) }
    var showPassword by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showPasswordGenerator by remember { mutableStateOf(false) }

    val canAddField by remember { derivedStateOf { customFields.size < CustomField.MAX_FIELDS } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (credential.id.isEmpty()) "New Credential" else "Edit Credential") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.Close, null) } },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(credential.copy(
                                title = title.trim(),
                                username = username.trim(),
                                password = password,
                                url = url.trim(),
                                notes = notes.trim(),
                                totpSecret = totpSecret.trim().takeIf { it.isNotEmpty() },
                                tags = selectedTags,
                                customFields = customFields,
                            ))
                        },
                        enabled = title.isNotBlank(),
                    ) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title *") }, modifier = Modifier.fillMaxWidth(), isError = title.isBlank())
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showPasswordGenerator = true }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Generate password")
                        }
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    }
                }
            )
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            OutlinedTextField(value = totpSecret, onValueChange = { totpSecret = it }, label = { Text("TOTP Secret (base32)") }, modifier = Modifier.fillMaxWidth())

            // Category (chip-based, non-editable)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selectedTags.forEach { tag ->
                        InputChip(
                            selected = true,
                            onClick = { selectedTags = selectedTags - tag },
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove category",
                                    modifier = Modifier.size(InputChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = { showTagPicker = true },
                        label = { Text("Add category") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }

            Text("Custom Fields (${customFields.size}/${CustomField.MAX_FIELDS})", style = MaterialTheme.typography.titleSmall)
            customFields.forEachIndexed { index, field ->
                key(index) {
                    CustomFieldRow(
                        field = field,
                        onFieldChanged = { updated -> customFields = customFields.toMutableList().also { it[index] = updated } },
                        onRemove = { customFields = customFields.toMutableList().also { it.removeAt(index) } },
                    )
                }
            }
            if (canAddField) {
                OutlinedButton(
                    onClick = { customFields = customFields + CustomField("", "", false) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Custom Field") }
            }
        }
    }

    if (showTagPicker) {
        TagPickerDialog(
            availableTags = availableTags,
            selectedTags = selectedTags,
            onToggleTag = { tag ->
                selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
            },
            onAddCustomTag = { newTag ->
                if (newTag !in selectedTags) selectedTags = selectedTags + newTag
                onAddTag(newTag)
            },
            onDismiss = { showTagPicker = false },
        )
    }

    if (showPasswordGenerator) {
        PasswordGeneratorSheet(
            onUsePassword = { generated -> password = generated },
            onDismiss = { showPasswordGenerator = false },
        )
    }
}

@Composable
private fun TagsViewCard(tags: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Category", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    SuggestionChip(onClick = {}, label = { Text(tag) })
                }
            }
        }
    }
}

@Composable
private fun TagPickerDialog(
    availableTags: List<Tag>,
    selectedTags: List<String>,
    onToggleTag: (String) -> Unit,
    onAddCustomTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customTagText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                availableTags.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = tag.name in selectedTags,
                            onCheckedChange = { onToggleTag(tag.name) },
                        )
                        Text(
                            tag.name,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (availableTags.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = customTagText,
                        onValueChange = { customTagText = it },
                        label = { Text("New category") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            val trimmed = customTagText.trim()
                            if (trimmed.isNotEmpty()) {
                                onAddCustomTag(trimmed)
                                customTagText = ""
                            }
                        },
                        enabled = customTagText.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add custom category")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun CustomFieldRow(field: CustomField, onFieldChanged: (CustomField) -> Unit, onRemove: () -> Unit) {
    var key by remember { mutableStateOf(field.key) }
    var value by remember { mutableStateOf(field.value) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = key, onValueChange = { key = it; onFieldChanged(field.copy(key = it)) }, label = { Text("Key") }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = value, onValueChange = { value = it; onFieldChanged(field.copy(value = it)) }, label = { Text("Value") }, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) { Icon(Icons.Default.Remove, "Remove field") }
    }
}
