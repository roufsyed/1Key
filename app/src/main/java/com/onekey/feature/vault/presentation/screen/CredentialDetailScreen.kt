package com.onekey.feature.vault.presentation.screen

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
import com.onekey.core.domain.model.CustomField
import com.onekey.feature.twofa.presentation.screen.TotpWidget
import com.onekey.feature.vault.presentation.viewmodel.CredentialDetailUiState
import com.onekey.feature.vault.presentation.viewmodel.CredentialDetailViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: CredentialDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                    onSave = viewModel::save,
                    onBack = onBack,
                )
            } else {
                CredentialViewContent(
                    credential = state.credential,
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onCopyPassword: (String) -> Unit,
    onCopyUsername: (String) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(credential.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (credential.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (credential.isFavorite) "Remove from favorites" else "Add to favorites",
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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
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
    onSave: (Credential) -> Unit,
    onBack: () -> Unit,
) {
    var title by remember(credential.id) { mutableStateOf(credential.title) }
    var username by remember(credential.id) { mutableStateOf(credential.username) }
    var password by remember(credential.id) { mutableStateOf(credential.password) }
    var url by remember(credential.id) { mutableStateOf(credential.url) }
    var notes by remember(credential.id) { mutableStateOf(credential.notes) }
    var totpSecret by remember(credential.id) { mutableStateOf(credential.totpSecret ?: "") }
    var tagsText by remember(credential.id) { mutableStateOf(credential.tags.joinToString(", ")) }
    var customFields by remember(credential.id) { mutableStateOf(credential.customFields) }
    var showPassword by remember { mutableStateOf(false) }

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
                                tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() },
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
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                }
            )
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            OutlinedTextField(value = totpSecret, onValueChange = { totpSecret = it }, label = { Text("TOTP Secret (base32)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = tagsText, onValueChange = { tagsText = it }, label = { Text("Tags (comma-separated)") }, modifier = Modifier.fillMaxWidth())

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
