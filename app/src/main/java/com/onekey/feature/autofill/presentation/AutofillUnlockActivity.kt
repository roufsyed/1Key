package com.onekey.feature.autofill.presentation

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.onekey.core.presentation.lockaware.LockAwareTextField
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.security.AutoLockManager
import com.onekey.feature.auth.presentation.viewmodel.AuthUiState
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.onekey.feature.autofill.domain.DatasetBuilder
import com.onekey.feature.autofill.domain.HostExtractor
import com.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The unlock + picker + search activity launched from the autofill chips.
 * Reuses [AuthViewModel] for unlock state and tiered-lockout tracking; ships
 * its own focused composable surfaces rather than reusing
 * [com.onekey.feature.auth.presentation.screen.LockScreen], which is coupled
 * to `AppViewModel`'s unlock-morph animation.
 *
 * Invariants:
 *  - `FLAG_SECURE` is set unconditionally and never cleared. The "Allow
 *    screenshots" preference does not extend to the autofill picker — the
 *    picker surfaces arbitrary vault entries the user did not explicitly
 *    consent to expose to Recent Apps thumbnails or screen recorders.
 *  - The result Intent uses `AutofillManager.EXTRA_AUTHENTICATION_RESULT`
 *    to deliver a replacement [android.service.autofill.Dataset]. Captured
 *    values from the form never persist.
 *  - On a missing or malformed `EXTRA_PARSED_FIELDS`, the activity finishes
 *    `RESULT_CANCELED` instead of crashing. The framework treats this as
 *    "auth aborted" and shows no error.
 *  - Cross-host fills (a credential picked via search whose stored host does
 *    not match the form's webDomain) go through an explicit confirmation
 *    pane. The exact-host policy in [PackageMatcher] prevents *automatic*
 *    cross-host fills; the manual one is gated here so the user positively
 *    consents.
 *  - `onNewIntent` finishes the activity. The autofill `singleInstance` task
 *    would otherwise hold stale ParsedFields if the user re-triggers from a
 *    different field/app while the activity is in the background.
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
        const val EXTRA_START_IN_SEARCH = "com.onekey.autofill.START_IN_SEARCH"
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoLockManager.onUserActivity()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleInstance launchMode plus the framework's PendingIntent reuse
        // can route a second autofill request into the existing activity
        // instance, carrying *new* ParsedFields for a different app/field.
        // Our ViewModel cached the original ParsedFields at construction;
        // resolving search results against it would deliver a Dataset bound
        // to the wrong AutofillIds. Finishing forces the framework to start
        // a fresh task with the new extras.
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Autofill picker is a sensitive surface that exposes arbitrary vault
        // entries via search. Keep it secure regardless of the user's
        // "Allow screenshots" preference (which was scoped to the main UI).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

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
                        startInSearch = viewModel.startInSearch,
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
    startInSearch: Boolean,
    onResolve: (Credential) -> Unit,
    onAbort: () -> Unit,
) {
    val isUnlocked by authViewModel.isUnlocked.collectAsStateWithLifecycle()
    val crossHostFor by unlockViewModel.crossHostFor.collectAsStateWithLifecycle()

    // Once unlocked, kick off exact-host match load AND the decrypted vault
    // snapshot. Both are idempotent inside the ViewModel — relock clears
    // them, and the LaunchedEffect fires again on re-unlock.
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            unlockViewModel.pendingComplete = true
            unlockViewModel.loadMatches()
            unlockViewModel.loadSnapshot()
        }
    }

    // Search-mode toggle. Starts true when the service routed via the
    // trailing chip; toggleable by the user from the picker.
    var inSearchMode by rememberSaveable(startInSearch) { mutableStateOf(startInSearch) }

    when {
        !isUnlocked -> LockedSurface(parsed = parsed, authViewModel = authViewModel, onAbort = onAbort)
        crossHostFor != null -> CrossHostConfirmSurface(
            parsed = parsed,
            credential = crossHostFor!!,
            onConfirm = {
                val c = unlockViewModel.confirmCrossHost()
                if (c != null) onResolve(c)
            },
            onCancel = { unlockViewModel.cancelCrossHost() },
        )
        inSearchMode -> SearchSurface(
            parsed = parsed,
            unlockViewModel = unlockViewModel,
            onBackToMatches = { inSearchMode = false },
            onResolve = { credential ->
                val fillNow = unlockViewModel.resolveCandidate(credential, fromExactMatchList = false)
                if (fillNow) onResolve(credential)
                // else: crossHostFor flipped; UnlockGate re-routes to confirm pane
            },
            onAbort = onAbort,
        )
        else -> MatchesSurface(
            parsed = parsed,
            unlockViewModel = unlockViewModel,
            onResolve = { credential ->
                // Exact-host match path always fills directly. resolveCandidate
                // short-circuits to `true` for fromExactMatchList=true.
                unlockViewModel.resolveCandidate(credential, fromExactMatchList = true)
                onResolve(credential)
            },
            onOpenSearch = { inSearchMode = true },
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
            // `visible` toggle is intentionally inline — the dedicated
            // SecurePasswordTextField wrapper used elsewhere requires richer
            // scaffolding; for this short-lived activity a plain
            // LockAwareTextField + PasswordVisualTransformation is sufficient.
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
    onResolve: (Credential) -> Unit,
    onOpenSearch: () -> Unit,
    onAbort: () -> Unit,
) {
    val matchesState by unlockViewModel.matches.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
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
                            text = "No saved credentials for this site.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        s.credentials.forEach { credential ->
                            CredentialRow(
                                credential = credential,
                                onClick = { onResolve(credential) },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onOpenSearch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Search 1Key for another credential")
            }
            TextButton(onClick = onAbort, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun SearchSurface(
    parsed: ParsedFields,
    unlockViewModel: AutofillUnlockViewModel,
    onBackToMatches: () -> Unit,
    onResolve: (Credential) -> Unit,
    onAbort: () -> Unit,
) {
    val query by unlockViewModel.searchQuery.collectAsStateWithLifecycle()
    val resultsState by unlockViewModel.searchResults.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Search 1Key",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBackToMatches) { Text("Back") }
        }
        val target = parsed.webDomain ?: parsed.packageName
        Text(
            text = "Filling into $target",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LockAwareTextField(
            value = query,
            onValueChange = { unlockViewModel.onSearchQueryChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            // autoCorrectEnabled = false avoids the IME persisting query
            // text into the user's personalised-learning dictionary. The
            // search query may include partial credential titles which are
            // sensitive even though they're not the password itself.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = false,
            ),
            label = { Text("Title, username, or site") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { unlockViewModel.onSearchQueryChanged("") }) {
                        Text("×", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else null,
        )
        when (val s = resultsState) {
            AutofillUnlockViewModel.SearchState.Idle,
            AutofillUnlockViewModel.SearchState.Loading -> {
                Text(
                    text = "Loading vault…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is AutofillUnlockViewModel.SearchState.Loaded -> {
                if (s.credentials.isEmpty()) {
                    Text(
                        text = if (query.isBlank()) "Nothing saved yet."
                        else "No credentials match \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.credentials, key = { it.id }) { credential ->
                            CredentialRow(
                                credential = credential,
                                onClick = { onResolve(credential) },
                            )
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

@Composable
private fun CrossHostConfirmSurface(
    parsed: ParsedFields,
    credential: Credential,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val target = parsed.webDomain ?: parsed.packageName
    val credHost = HostExtractor.hostOf(credential.url) ?: "(no URL)"

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
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = "Fill from a different site?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Form: $target",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Credential: ${credential.title} ($credHost)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "1Key only fills automatically when the saved site matches the form. " +
                    "This credential was saved for a different site — only continue if you're " +
                    "certain they're the same login.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Fill anyway") }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun CredentialRow(
    credential: Credential,
    onClick: () -> Unit,
) {
    val host = HostExtractor.hostOf(credential.url)
    val subtitleParts = listOfNotNull(
        credential.username.takeIf { it.isNotBlank() } ?: "(no username)",
        host,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Text(
            text = credential.title.ifBlank { "1Key item" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitleParts.joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
