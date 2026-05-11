package com.onekey.feature.autofill.presentation

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.onekey.core.presentation.lockaware.LockAwareTextField
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.security.AutoLockManager
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Confirms a captured username/password pair into the vault.
 *
 * Lifecycle highlights:
 *  - Launched by [com.onekey.feature.autofill.service.OneKeyAutofillService]
 *    with `FLAG_ACTIVITY_NEW_TASK` and a single opaque token extra. The
 *    plaintext payload lives in [com.onekey.feature.autofill.domain.AutofillCaptureBuffer]
 *    in-process and is consumed at most once.
 *  - On a missing or already-consumed token (process death recovery), the
 *    activity finishes silently. No error UI — the system has no save flow to
 *    "fail back to" once we're outside its bottom-sheet.
 *  - Locked vault: shows a focused master-password unlock pane (same shape as
 *    [AutofillUnlockActivity]). Biometric/PIN deferred to v1.1.
 *  - Unlocked: searches for an existing credential at the same host/package
 *    with the same username; if found the primary action becomes "Update".
 *
 * Window flags:
 *  - `FLAG_SECURE` is set in `onCreate` and only cleared once the screenshots
 *    preference resolves to `true` — mirrors MainActivity. The save sheet
 *    inherently shows a plaintext password label, so it must not leak to
 *    Recent Apps.
 */
@AndroidEntryPoint
class AutofillSaveActivity : FragmentActivity() {

    @Inject lateinit var appPrefs: AppPreferencesRepository
    @Inject lateinit var autoLockManager: AutoLockManager

    private val authViewModel: AuthViewModel by viewModels()
    private val viewModel: AutofillSaveViewModel by viewModels()

    companion object {
        const val EXTRA_TOKEN = "com.onekey.autofill.SAVE_TOKEN"
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLockManager.onUserActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default-secure-until-hydrated, mirrors MainActivity.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            appPrefs.isScreenshotsEnabled().collect { enabled ->
                if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        viewModel.hydrate(token)

        setContent {
            val isDark by appPrefs.isDarkTheme().collectAsStateWithLifecycle(initialValue = false)
            OneKeyTheme(darkTheme = isDark) {
                val userActivityPing = remember(autoLockManager) {
                    { autoLockManager.onUserActivity() }
                }
                CompositionLocalProvider(LocalUserActivityPing provides userActivityPing) {
                    SaveGate(
                        viewModel = viewModel,
                        authViewModel = authViewModel,
                        onFinish = {
                            setResult(RESULT_OK)
                            finish()
                        },
                        onAbort = {
                            viewModel.dismiss()
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveGate(
    viewModel: AutofillSaveViewModel,
    authViewModel: AuthViewModel,
    onFinish: () -> Unit,
    onAbort: () -> Unit,
) {
    val capture by viewModel.capture.collectAsStateWithLifecycle()
    val isUnlocked by authViewModel.isUnlocked.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()

    LaunchedEffect(outcome) {
        if (outcome is AutofillSaveViewModel.SaveOutcome.Saved) onFinish()
    }

    LaunchedEffect(isUnlocked, capture) {
        if (isUnlocked && capture is AutofillSaveViewModel.SaveState.Hydrated) {
            viewModel.searchExisting()
        }
    }

    when (val state = capture) {
        AutofillSaveViewModel.SaveState.Idle -> {
            // Brief loading pane — hydrate is synchronous from the activity
            // but a recreated viewmodel can still observe Idle for a frame.
            CenteredText("Preparing…")
        }
        AutofillSaveViewModel.SaveState.MissingCapture -> {
            LaunchedEffect(Unit) { onAbort() }
        }
        is AutofillSaveViewModel.SaveState.Hydrated -> {
            if (!isUnlocked) {
                LockedSavePane(authViewModel = authViewModel, onAbort = onAbort)
            } else {
                ConfirmSavePane(
                    viewModel = viewModel,
                    capture = state,
                    onAbort = onAbort,
                )
            }
        }
    }
}

@Composable
private fun CenteredText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LockedSavePane(
    authViewModel: AuthViewModel,
    onAbort: () -> Unit,
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val lockoutMs by authViewModel.passwordLockoutUntilMs.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    val isLockedOut = lockoutMs?.let { it > now } == true
    val isLoading = state is AuthUiState.Loading
    val errorMessage = (state as? AuthUiState.Error)?.message

    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Unlock 1Key to save",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            LockAwareTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                enabled = !isLockedOut,
                visualTransformation = if (visible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                label = { Text("Master password") },
            )
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { authViewModel.unlockWithPassword(password.toCharArray()) },
                enabled = !isLockedOut && !isLoading && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isLoading) "Unlocking…" else "Unlock and continue")
            }
            TextButton(onClick = onAbort) { Text("Cancel") }
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide password" else "Show password")
            }
            if (isLockedOut) {
                Text(
                    text = "Too many wrong attempts. Try again later.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ConfirmSavePane(
    viewModel: AutofillSaveViewModel,
    capture: AutofillSaveViewModel.SaveState.Hydrated,
    onAbort: () -> Unit,
) {
    val matched by viewModel.matchedExisting.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val label by viewModel.displayLabel.collectAsStateWithLifecycle()
    val isSaving = outcome is AutofillSaveViewModel.SaveOutcome.Saving
    val errorMessage = (outcome as? AutofillSaveViewModel.SaveOutcome.Failed)?.message
    val displayUsername = capture.capture.username?.takeIf { it.isNotBlank() } ?: "(no username)"
    val displayTarget = label ?: capture.capture.webDomain ?: capture.capture.packageName

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (matched != null) "Update password in 1Key?" else "Save to 1Key?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = displayTarget,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Username: $displayUsername",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Password: ••••••••",
                style = MaterialTheme.typography.bodyMedium,
            )
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { viewModel.save(updateInPlace = matched != null) },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        isSaving -> "Saving…"
                        matched != null -> "Update existing"
                        else -> "Save as new"
                    }
                )
            }
            if (matched != null) {
                TextButton(
                    onClick = { viewModel.save(updateInPlace = false) },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save as a separate entry")
                }
            }
            TextButton(onClick = onAbort, modifier = Modifier.align(Alignment.End)) {
                Text("Not now")
            }
        }
    }
}
