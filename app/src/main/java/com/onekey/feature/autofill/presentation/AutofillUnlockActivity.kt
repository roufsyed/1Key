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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.data.snapshot.SnapshotCredential
import com.onekey.core.domain.model.AppResult
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.ThemeMode
import com.onekey.core.domain.model.isDark
import com.onekey.core.domain.repository.AppPreferencesRepository
import com.onekey.core.domain.repository.AuthRepository
import com.onekey.core.domain.repository.CredentialRepository
import com.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.onekey.core.presentation.lockaware.LockAwareTextField
import com.onekey.core.presentation.theme.OneKeyTheme
import com.onekey.core.presentation.util.BiometricPromptController
import com.onekey.core.security.AutoLockManager
import com.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.onekey.feature.autofill.domain.DatasetBuilder
import com.onekey.feature.autofill.domain.HostExtractor
import com.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 *    screenshots" preference does not extend to the autofill picker: the
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
 *    pane. The exact-host policy in `PackageMatcher` prevents *automatic*
 *    cross-host fills; the manual one is gated here so the user positively
 *    consents.
 *  - `onNewIntent` finishes the activity. The autofill `singleInstance` task
 *    would otherwise hold stale ParsedFields if the user re-triggers from a
 *    different field/app while the activity is in the background.
 *  - Search results carry only [SnapshotCredential] (lean projection). The
 *    full [Credential] is fetched by id on demand at Dataset-delivery time
 *    via [credentialRepository] so password/notes/OTP-secret never enter
 *    long-lived UI state.
 */
@AndroidEntryPoint
class AutofillUnlockActivity : FragmentActivity() {

    @Inject lateinit var appPrefs: AppPreferencesRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var autoLockManager: AutoLockManager
    @Inject lateinit var datasetBuilder: DatasetBuilder

    /**
     * On-demand fetch path for the full [Credential] at Dataset-delivery
     * time. The search surface consumes the lean [SnapshotCredential]
     * projection; only the moment a Dataset is actually built do we need
     * the password / notes / OTP secret. Fetching by id is a single Room
     * point read plus one per-row decrypt, cheap relative to the
     * decrypt-all the previous design required.
     */
    @Inject lateinit var credentialRepository: CredentialRepository

    private val authViewModel: AuthViewModel by viewModels()
    private val viewModel: AutofillUnlockViewModel by viewModels()

    /**
     * Activity-scoped controller for the [androidx.biometric.BiometricPrompt].
     * Held here (not inside Compose state) so a configuration change cannot
     * drop the cancel handle while the system prompt is still showing. The
     * controller also wraps the [AutoLockManager] inactivity-suppression
     * pair so the idle timer doesn't relock the vault under a long-held
     * prompt. Built lazily in [onCreate]; cancelled in [onDestroy] and
     * [onNewIntent].
     */
    private var biometricController: BiometricPromptController? = null

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
        //
        // Cancel any in-flight biometric prompt FIRST so the dismissed
        // callback fires while the activity is still alive: otherwise the
        // BiometricFragment's callback can post into a destroyed lifecycle.
        biometricController?.cancel()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        // Cancel any active biometric prompt and release the inactivity
        // suppression. Idempotent: `cancel()` is a no-op if the prompt
        // already terminated.
        biometricController?.cancel()
        biometricController = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Autofill picker is a sensitive surface that exposes arbitrary vault
        // entries via search. Keep it secure regardless of the user's
        // "Allow screenshots" preference (which was scoped to the main UI).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Defense against overlay tap-jacking. The autofill picker can route
        // a credential into a different app via the dataset return; a fake
        // overlay covering "Cancel" or "Use master password" would let an
        // attacker steer that. The flag prevents touches with an obscured
        // window from being delivered to the underlying view.
        window.decorView.filterTouchesWhenObscured = true

        biometricController = BiometricPromptController(this, autoLockManager)

        if (viewModel.initial is AutofillUnlockViewModel.InitialState.Invalid) {
            // Missing or malformed extras: finish quietly. The framework
            // shows no error; the chip just no-ops.
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            val themeMode by appPrefs.getThemeMode()
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val isDark = themeMode.isDark()
            OneKeyTheme(darkTheme = isDark) {
                val userActivityPing = remember(autoLockManager) {
                    { autoLockManager.onUserActivity() }
                }
                // Scope tied to the Composable's lifetime so an on-demand
                // getCredential(id) coroutine is cancelled if the user
                // navigates away or the activity finishes mid-fetch.
                val activityScope = rememberCoroutineScope()
                val parsed = (viewModel.initial as AutofillUnlockViewModel.InitialState.Ready).parsed

                // Two distinct resolve paths:
                //  * onResolveCredential: exact-host MatchesSurface already
                //    holds the full Credential; just build the Dataset.
                //  * onResolveSnapshot: search and cross-host pane both
                //    carry only SnapshotCredential, so we fetch by id and
                //    build the Dataset on success. AppResult.Error is
                //    treated as "vault locked or row deleted between read
                //    and fetch" => RESULT_CANCELED.
                val onResolveCredential: (Credential) -> Unit = { credential ->
                    deliverDataset(parsed, credential)
                }
                val onResolveSnapshot: (SnapshotCredential) -> Unit = { snap ->
                    activityScope.launch {
                        fetchAndDeliver(activityScope, parsed, snap.id)
                    }
                }

                CompositionLocalProvider(LocalUserActivityPing provides userActivityPing) {
                    UnlockGate(
                        parsed = parsed,
                        authViewModel = authViewModel,
                        unlockViewModel = viewModel,
                        biometricController = biometricController!!,
                        startInSearch = viewModel.startInSearch,
                        onResolveCredential = onResolveCredential,
                        onResolveSnapshot = onResolveSnapshot,
                        onAbort = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Builds a Dataset from a full [Credential] and finishes RESULT_OK. */
    private fun deliverDataset(parsed: ParsedFields, credential: Credential) {
        val dataset = datasetBuilder.buildCredentialDataset(parsed, credential)
        val data = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * Fetches the full credential by id, then either delivers the Dataset or
     * aborts on any error (most commonly: vault re-locked between snapshot
     * read and tap, or the row was deleted concurrently). RESULT_CANCELED
     * is the framework's "auth aborted" path: no Dataset, no error toast.
     */
    private suspend fun fetchAndDeliver(
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        parsed: ParsedFields,
        id: String,
    ) {
        when (val r = credentialRepository.getCredential(id)) {
            is AppResult.Success -> deliverDataset(parsed, r.data)
            is AppResult.Error -> {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }
}

@Composable
private fun UnlockGate(
    parsed: ParsedFields,
    authViewModel: AuthViewModel,
    unlockViewModel: AutofillUnlockViewModel,
    biometricController: BiometricPromptController,
    startInSearch: Boolean,
    onResolveCredential: (Credential) -> Unit,
    onResolveSnapshot: (SnapshotCredential) -> Unit,
    onAbort: () -> Unit,
) {
    val isUnlocked by authViewModel.isUnlocked.collectAsStateWithLifecycle()
    val crossHostFor by unlockViewModel.crossHostFor.collectAsStateWithLifecycle()

    // Once unlocked, kick off exact-host match load. The snapshot is hot
    // already (managed by VaultSnapshotStore at app scope); we no longer
    // call a per-screen loadSnapshot(). The LaunchedEffect fires again on
    // re-unlock and loadMatches() is idempotent.
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            unlockViewModel.loadMatches()
        }
    }

    // Search-mode toggle. Starts true when the service routed via the
    // trailing chip; toggleable by the user from the picker.
    var inSearchMode by rememberSaveable(startInSearch) { mutableStateOf(startInSearch) }

    when {
        !isUnlocked -> AutofillLockedSurface(
            target = parsed.webDomain ?: parsed.packageName,
            headlineText = "Unlock 1Key to fill",
            submitButtonLabel = "Unlock and fill",
            authViewModel = authViewModel,
            biometricController = biometricController,
            onAbort = onAbort,
        )
        crossHostFor != null -> CrossHostConfirmSurface(
            parsed = parsed,
            credential = crossHostFor!!,
            onConfirm = {
                val c = unlockViewModel.confirmCrossHost()
                if (c != null) onResolveSnapshot(c)
            },
            onCancel = { unlockViewModel.cancelCrossHost() },
        )
        inSearchMode -> SearchSurface(
            parsed = parsed,
            unlockViewModel = unlockViewModel,
            onBackToMatches = { inSearchMode = false },
            onResolve = { snap ->
                val fillNow = unlockViewModel.resolveSearchCandidate(snap)
                if (fillNow) onResolveSnapshot(snap)
                // else: crossHostFor flipped; UnlockGate re-routes to confirm pane
            },
            onAbort = onAbort,
        )
        else -> MatchesSurface(
            parsed = parsed,
            unlockViewModel = unlockViewModel,
            onResolve = onResolveCredential,
            onOpenSearch = { inSearchMode = true },
            onAbort = onAbort,
        )
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
                                row = credential.toRow(),
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
    onResolve: (SnapshotCredential) -> Unit,
    onAbort: () -> Unit,
) {
    val query by unlockViewModel.searchQuery.collectAsStateWithLifecycle()
    val resultsState by unlockViewModel.searchResults.collectAsStateWithLifecycle()
    val availableTags by unlockViewModel.availableTags.collectAsStateWithLifecycle()
    val selectedTag by unlockViewModel.selectedTag.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val isBypassed = resultsState is AutofillUnlockViewModel.SearchState.Bypassed

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // If the active tag disappears from the available-tags list (deleted in
    // another instance, or the category-filter pref flipped off) drop the
    // selection silently. Falls back to "All".
    LaunchedEffect(availableTags, selectedTag) {
        val tag = selectedTag
        if (tag != null && availableTags.none { it.tag.name == tag }) {
            unlockViewModel.onTagSelected(null)
        }
    }

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
            enabled = !isBypassed,
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
        if (availableTags.isNotEmpty()) {
            TagChipRow(
                tags = availableTags,
                selected = selectedTag,
                onSelect = { unlockViewModel.onTagSelected(it) },
                enabled = !isBypassed,
            )
        }
        when (val s = resultsState) {
            AutofillUnlockViewModel.SearchState.Idle -> {
                if (query.isBlank()) {
                    // True idle: nothing to filter, prompt the user to type.
                    Text(
                        text = "Type to search your vault.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Non-blank query but Idle: either the initial stateIn
                    // value before the first combine emission (~150 ms
                    // debounce after entry into search mode), or a transient
                    // lock window before UnlockGate routes us away. Show a
                    // spinner so the seeded query in the field is not
                    // contradicted by a "Type to search" hint.
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            AutofillUnlockViewModel.SearchState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            AutofillUnlockViewModel.SearchState.Bypassed -> {
                Text(
                    text = "Your vault is too large to search from autofill. " +
                        "Open 1Key to find this credential.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is AutofillUnlockViewModel.SearchState.Loaded -> {
                if (s.credentials.isEmpty()) {
                    Text(
                        text = emptyResultsCopy(query, selectedTag),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .preferredFrameRate(FrameRateCategory.High),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(s.credentials, key = { it.id }) { credential ->
                            CredentialRow(
                                row = credential.toRow(),
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
    credential: SnapshotCredential,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val target = parsed.webDomain ?: parsed.packageName
    val credHost = HostExtractor.hostOf(credential.url) ?: "(no URL)"
    // Local fetching flag: once the user taps "Fill anyway" we kick off the
    // getCredential(id) coroutine in the activity. Until that completes
    // (success => finish, error => finish), keep the pane mounted with a
    // spinner on the confirm button so the user does not see a flicker
    // back to SearchSurface during the fetch.
    var fetching by remember { mutableStateOf(false) }

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
                    "This credential was saved for a different site: only continue if you're " +
                    "certain they're the same login.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (!fetching) {
                        fetching = true
                        onConfirm()
                    }
                },
                enabled = !fetching,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (fetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Fill anyway")
                }
            }
            TextButton(
                onClick = onCancel,
                enabled = !fetching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel")
            }
        }
    }
}

/**
 * Local UI projection consumed by [CredentialRow]. Kept private to this file
 * so the row composable can render either a full [Credential] (host-match path)
 * or a lean [SnapshotCredential] (search path) without baking either type into
 * the row signature. Do not iterate over a `List<CredentialRowData>` at the
 * surface level: keep `.toRow()` at the call site so the parent lambda retains
 * the typed credential for [AutofillUnlockViewModel.resolveSearchCandidate].
 */
private data class CredentialRowData(
    val title: String,
    val username: String,
    val url: String,
)

private fun Credential.toRow() = CredentialRowData(title, username, url)
private fun SnapshotCredential.toRow() = CredentialRowData(title, username, url)

@Composable
private fun CredentialRow(
    row: CredentialRowData,
    onClick: () -> Unit,
) {
    val host = HostExtractor.hostOf(row.url)
    val subtitleParts = listOfNotNull(
        row.username.takeIf { it.isNotBlank() } ?: "(no username)",
        host,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Text(
            text = row.title.ifBlank { "1Key item" },
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

/**
 * Horizontally-scrolling filter chips for the autofill search screen: only
 * rendered when the user has opted into the category filter AND has at least
 * one tag. Single-select with tap-again-to-clear semantics: re-tapping the
 * active chip returns to "All" (the clear-filter state). Disabled when the
 * snapshot is in [AutofillUnlockViewModel.SearchState.Bypassed] state so the
 * user can't toggle a filter against a search surface that isn't running.
 */
@Composable
private fun TagChipRow(
    tags: List<com.onekey.core.domain.model.TagWithCount>,
    selected: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean = true,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
    ) {
        item(key = "__all__") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        items(tags, key = { it.tag.name }) { tagWithCount ->
            val isActive = selected == tagWithCount.tag.name
            FilterChip(
                selected = isActive,
                onClick = { onSelect(if (isActive) null else tagWithCount.tag.name) },
                label = { Text("${tagWithCount.tag.name} (${tagWithCount.count})") },
                enabled = enabled,
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

private fun emptyResultsCopy(query: String, tag: String?): String {
    val q = query.trim()
    return when {
        q.isBlank() && tag == null -> "Nothing saved yet."
        q.isBlank() && tag != null -> "No credentials tagged $tag."
        tag == null -> "No credentials match \"$q\"."
        else -> "No \"$q\" in $tag."
    }
}
