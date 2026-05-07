package com.onekey.feature.auth.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import com.onekey.feature.auth.presentation.viewmodel.ChangePasswordUiState
import com.onekey.feature.auth.presentation.viewmodel.ChangePasswordViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: ChangePasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showCurrent by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is ChangePasswordUiState.Success) onSuccess()
    }

    val confirmMismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
    val newTooShort = newPassword.isNotEmpty() && newPassword.length < 8
    val canSubmit = currentPassword.isNotEmpty() &&
            newPassword.length >= 8 &&
            newPassword == confirmPassword &&
            state !is ChangePasswordUiState.Loading

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change Master Password") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        }
    ) { padding ->
        // Hero/form split: scrollable explainer + 3 password fields above, sticky
        // submit (and any state-level error) below with `.imePadding()`. With a
        // single verticalScroll and a button at the end, focusing any field would
        // scroll the submit out of the visible area when the keyboard opens.
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Only your password verification is updated on this device. Your vault key is unchanged — all existing credentials remain accessible immediately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PasswordField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it; viewModel.clearError() },
                    label = "Current Password",
                    showPassword = showCurrent,
                    onToggleVisibility = { showCurrent = !showCurrent },
                    imeAction = ImeAction.Next,
                )

                PasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = "New Password",
                    showPassword = showNew,
                    onToggleVisibility = { showNew = !showNew },
                    imeAction = ImeAction.Next,
                    isError = newTooShort,
                    supportingText = if (newTooShort) "Minimum 8 characters" else null,
                )

                PasswordField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = "Confirm New Password",
                    showPassword = showConfirm,
                    onToggleVisibility = { showConfirm = !showConfirm },
                    imeAction = ImeAction.Done,
                    isError = confirmMismatch,
                    supportingText = if (confirmMismatch) "Passwords do not match" else null,
                )
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 24.dp),
            ) {
                if (state is ChangePasswordUiState.Error) {
                    Text(
                        (state as ChangePasswordUiState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        viewModel.changePassword(
                            currentPassword.toCharArray(),
                            newPassword.toCharArray(),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSubmit,
                ) {
                    if (state is ChangePasswordUiState.Loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Change Password")
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showPassword: Boolean,
    onToggleVisibility: () -> Unit,
    imeAction: ImeAction,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    LockAwareOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
            }
        },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
