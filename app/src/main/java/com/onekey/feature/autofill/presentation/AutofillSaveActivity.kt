package com.onekey.feature.autofill.presentation

import android.content.Intent
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
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.onekey.core.presentation.lockaware.LockAwareTextField
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.presentation.util.BiometricPromptController
import com.onekey.core.security.AutoLockManager
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
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
 *    activity finishes silently. No error UI - the system has no save flow to
 *    "fail back to" once we're outside its bottom-sheet.
 *  - Locked vault: routes through [AutofillLockedSurface], the same shared
 *    biometric/PIN/master-password unlock surface used by [AutofillUnlockActivity].
 *  - Unlocked: searches for an existing credential at the same host/package
 *    with the same username; if found the primary action becomes "Update".
 *
 * Window flags:
 *  - `FLAG_SECURE` is set unconditionally and never cleared. The user's
 *    "Allow screenshots" preference does NOT extend to autofill surfaces -
 *    these show the captured credential pair and the unlock affordances.
 *  - `filterTouchesWhenObscured = true` defends against overlay tap-jacking
 *    on the unlock and save-confirm controls.
 *
 * The [BiometricPromptController] is activity-scoped (a member field) so a
 * configuration change cannot drop the cancel handle while the system
 * prompt is showing; [onDestroy] and [onNewIntent] both cancel.
 */
@AndroidEntryPoint
class AutofillSaveActivity : FragmentActivity() {

    @Inject lateinit var appPrefs: AppPreferencesRepository
    @Inject lateinit var autoLockManager: AutoLockManager

    private val authViewModel: AuthViewModel by viewModels()
    private val viewModel: AutofillSaveViewModel by viewModels()

    /**
     * Activity-scoped controller for the [androidx.biometric.BiometricPrompt].
     * See [AutofillUnlockActivity.biometricController] - same rationale: a
     * configuration change cannot drop the cancel handle, and the controller
     * wraps the [AutoLockManager] inactivity-suppression pair so the idle
     * timer doesn't relock the vault mid-prompt.
     */
    private var biometricController: BiometricPromptController? = null

    companion object {
        const val EXTRA_TOKEN = "com.onekey.autofill.SAVE_TOKEN"
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLockManager.onUserActivity()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The save activity is `singleInstance` (manifest), so a second
        // save request would re-enter this instance with a new token while
        // the user is still on the prior flow. Cancel the prompt and finish
        // cleanly - the framework starts a fresh task with the new extras.
        biometricController?.cancel()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        biometricController?.cancel()
        biometricController = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE unconditionally - the save sheet displays the captured
        // username and a masked password, plus surfaces master-password /
        // PIN unlock fields. Consistent with [AutofillUnlockActivity], the
        // user's "Allow screenshots" preference does NOT extend to autofill
        // surfaces, which are short-lived and surface credential data.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Defense against overlay tap-jacking on the unlock affordances.
        window.decorView.filterTouchesWhenObscured = true

        biometricController = BiometricPromptController(this, autoLockManager)

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
                        biometricController = biometricController!!,
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
    biometricController: BiometricPromptController,
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
            // Brief loading pane - hydrate is synchronous from the activity
            // but a recreated viewmodel can still observe Idle for a frame.
            CenteredText("Preparing…")
        }
        AutofillSaveViewModel.SaveState.MissingCapture -> {
            // Reached when: (a) the OS killed our process between the
            // service's store(...) and the activity's hydrate, leaving the
            // capture buffer empty on restore; or (b) a second app's save
            // submission overwrote the slot during a slow unlock. Either way
            // we have nothing to save - but silently finishing leaves the
            // user confused. A short Toast tells them to retry; the dismiss
            // path is otherwise unchanged.
            val ctx = LocalContext.current
            LaunchedEffect(Unit) {
                Toast.makeText(
                    ctx,
                    "Save request expired - please try again.",
                    Toast.LENGTH_LONG,
                ).show()
                onAbort()
            }
        }
        is AutofillSaveViewModel.SaveState.Hydrated -> {
            if (!isUnlocked) {
                AutofillLockedSurface(
                    target = state.capture.webDomain ?: state.capture.packageName,
                    headlineText = "Unlock 1Key to save",
                    submitButtonLabel = "Unlock and continue",
                    authViewModel = authViewModel,
                    biometricController = biometricController,
                    onAbort = onAbort,
                )
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
