package com.onekey.feature.autofill.presentation

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.onekey.core.presentation.lockaware.LockAwareTextField
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.security.AutoLockManager
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.onekey.feature.autofill.domain.DatasetBuilder
import com.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The unlock + picker activity launched when the user taps a locked-vault
 * autofill chip. Reuses [AuthViewModel] for unlock state and tiered-lockout
 * tracking; ships its own focused composable surface rather than reusing
 * [com.onekey.feature.auth.presentation.screen.LockScreen], which is
 * coupled to `AppViewModel`'s unlock-morph animation.
 *
 * Invariants:
 *  - `FLAG_SECURE` is set on the window from `onCreate` (default-secure) and
 *    cleared only if the user has explicitly enabled screenshots, exactly
 *    mirroring `MainActivity`. Without this the autofill unlock screen
 *    leaks into Recent Apps thumbnails.
 *  - The result Intent uses `AutofillManager.EXTRA_AUTHENTICATION_RESULT`
 *    to deliver a replacement [android.service.autofill.Dataset] to the
 *    framework. We never persist captured values from the autofill flow.
 *  - On a missing or malformed `EXTRA_PARSED_FIELDS`, we finish
 *    `RESULT_CANCELED` instead of crashing. The framework treats this as
 *    "auth aborted" and shows no error.
 *  - Process-death-mid-unlock: a `pendingComplete` flag is saved into the
 *    ViewModel's `SavedStateHandle` so a recreated activity that finds the
 *    vault already unlocked auto-completes without a second password prompt.
 */
@AndroidEntryPoint
class AutofillUnlockActivity : FragmentActivity() {

    @Inject lateinit var appPrefs: AppPreferencesRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var autoLockManager: AutoLockManager
    @Inject lateinit var datasetBuilder: DatasetBuilder

    private val authViewModel: AuthViewModel by viewModels()
    private val viewModel: AutofillUnlockViewModel by viewModels()

    companion object {
        const val EXTRA_PARSED_FIELDS = "com.onekey.autofill.PARSED_FIELDS"
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLockManager.onUserActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default-secure-until-hydrated, mirrors MainActivity:50-59.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            appPrefs.isScreenshotsEnabled().collect { enabled ->
                if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        if (viewModel.initial is AutofillUnlockViewModel.InitialState.Invalid) {
            // Missing or malformed extras — finish quietly. The framework
            // shows no error; the chip just no-ops.
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            val isDark by appPrefs.isDarkTheme().collectAsStateWithLifecycle(initialValue = false)
            OneKeyTheme(darkTheme = isDark) {
                val userActivityPing = remember(autoLockManager) {
                    { autoLockManager.onUserActivity() }
                }
                CompositionLocalProvider(LocalUserActivityPing provides userActivityPing) {
                    UnlockGate(
                        parsed = (viewModel.initial as AutofillUnlockViewModel.InitialState.Ready).parsed,
                        authViewModel = authViewModel,
                        unlockViewModel = viewModel,
                        onResolve = { credential ->
                            val dataset = datasetBuilder.buildCredentialDataset(
                                (viewModel.initial as AutofillUnlockViewModel.InitialState.Ready).parsed,
                                credential,
                            )
                            val data = Intent().putExtra(
                                AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                                dataset,
                            )
                            setResult(RESULT_OK, data)
                            finish()
                        },
                        onAbort = {
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
private fun UnlockGate(
    parsed: ParsedFields,
    authViewModel: AuthViewModel,
    unlockViewModel: AutofillUnlockViewModel,
    onResolve: (com.onekey.core.domain.model.Credential) -> Unit,
    onAbort: () -> Unit,
) {
    val isUnlocked by authViewModel.isUnlocked.collectAsStateWithLifecycle()

    // Once unlocked, kick off match loading. Idempotent — re-launching the
    // load when already loaded is a no-op inside the ViewModel.
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            unlockViewModel.pendingComplete = true
            unlockViewModel.loadMatches()
        }
    }

    if (!isUnlocked) {
        LockedSurface(parsed = parsed, authViewModel = authViewModel, onAbort = onAbort)
    } else {
        MatchesSurface(
            parsed = parsed,
            unlockViewModel = unlockViewModel,
            onResolve = onResolve,
            onAbort = onAbort,
        )
    }
}

@Composable
private fun LockedSurface(
    parsed: ParsedFields,
    authViewModel: AuthViewModel,
    onAbort: () -> Unit,
) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val lockoutMs by authViewModel.passwordLockoutUntilMs.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    val isLockedOut = lockoutMs?.let { it > now } == true
    val isLoading = state is AuthUiState.Loading

    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val errorMessage = (state as? AuthUiState.Error)?.message

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
                text = "Unlock 1Key to fill",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            val target = parsed.webDomain ?: parsed.packageName
            Text(
                text = "for $target",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                onClick = {
                    authViewModel.unlockWithPassword(password.toCharArray())
                },
                enabled = !isLockedOut && !isLoading && password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isLoading) "Unlocking…" else "Unlock and fill")
            }
            TextButton(onClick = onAbort) {
                Text("Cancel")
            }
            if (isLockedOut) {
                Text(
                    text = "Too many wrong attempts. Try again later.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // `visible` toggle is intentionally an inline state — the
            // dedicated SecurePasswordTextField wrapper used elsewhere
            // requires a richer scaffolding; for this short-lived activity
            // a plain LockAwareTextField + PasswordVisualTransformation is
            // sufficient.
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide password" else "Show password")
            }
        }
    }
}

@Composable
private fun MatchesSurface(
    parsed: ParsedFields,
    unlockViewModel: AutofillUnlockViewModel,
    onResolve: (com.onekey.core.domain.model.Credential) -> Unit,
    onAbort: () -> Unit,
) {
    val matchesState by unlockViewModel.matches.collectAsStateWithLifecycle()

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
                text = "Choose a credential",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val target = parsed.webDomain ?: parsed.packageName
            Text(
                text = "Filling into $target",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val s = matchesState) {
                AutofillUnlockViewModel.MatchState.Idle,
                AutofillUnlockViewModel.MatchState.Loading -> {
                    Text(
                        text = "Searching your vault…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is AutofillUnlockViewModel.MatchState.Loaded -> {
                    if (s.credentials.isEmpty()) {
                        Text(
                            text = "No saved credentials for this site. Open 1Key to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        s.credentials.forEach { credential ->
                            Button(
                                onClick = { onResolve(credential) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        credential.title.ifBlank { "1Key item" },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        credential.username.ifBlank { "(no username)" },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onAbort, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel")
            }
        }
    }
}

