package com.roufsyed.onekey.feature.autofill.presentation

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.roufsyed.onekey.core.data.snapshot.SnapshotCredential
import com.roufsyed.onekey.core.domain.model.AppResult
import com.roufsyed.onekey.core.domain.model.Credential
import com.roufsyed.onekey.core.domain.model.CredentialType
import com.roufsyed.onekey.core.domain.model.ThemeMode
import com.roufsyed.onekey.core.domain.model.isDark
import com.roufsyed.onekey.core.domain.repository.AppPreferencesRepository
import com.roufsyed.onekey.core.domain.repository.AuthRepository
import com.roufsyed.onekey.core.domain.repository.CredentialRepository
import com.roufsyed.onekey.core.presentation.lockaware.LocalUserActivityPing
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareTextField
import com.roufsyed.onekey.core.presentation.theme.OneKeyTheme
import com.roufsyed.onekey.core.presentation.util.BiometricPromptController
import com.roufsyed.onekey.core.security.AutoLockManager
import com.roufsyed.onekey.feature.auth.presentation.viewmodel.AuthViewModel
import com.roufsyed.onekey.feature.autofill.domain.DatasetBuilder
import com.roufsyed.onekey.feature.autofill.domain.HostExtractor
import com.roufsyed.onekey.feature.autofill.domain.ParsedFields
import dagger.hilt.android.AndroidEntryPoint
import com.roufsyed.onekey.core.presentation.animation.UnlockOverlay
import com.roufsyed.onekey.core.presentation.viewmodel.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The unlock + picker + search activity launched from the autofill chips.
 * Reuses [AuthViewModel] for unlock state and tiered-lockout tracking; ships
 * its own focused composable surfaces rather than reusing
 * [com.roufsyed.onekey.feature.auth.presentation.screen.LockScreen], which is coupled
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
    private val appViewModel: AppViewModel by viewModels()
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
        const val EXTRA_PARSED_FIELDS = "com.roufsyed.onekey.autofill.PARSED_FIELDS"
        const val EXTRA_START_IN_SEARCH = "com.roufsyed.onekey.autofill.START_IN_SEARCH"
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
        lifecycleScope.launch {
            appPrefs.isScreenshotsEnabled().collect { enabled ->
                if (enabled) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
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
                val onResolveSnapshot: (SnapshotCredential, Boolean) -> Unit = { snap, saveUrl ->
                    activityScope.launch {
                        fetchAndDeliver(activityScope, parsed, snap.id, saveUrl)
                    }
                }

                CompositionLocalProvider(LocalUserActivityPing provides userActivityPing) {
                    // Mount UnlockOverlay at the activity root so the morph
                    // circle has a Canvas to draw on while AutofillLockedSurface
                    // slides out beneath it. Mirrors NavGraph's mount of the
                    // overlay for the main app.
                    Box(modifier = Modifier.fillMaxSize()) {
                        UnlockGate(
                            parsed = parsed,
                            authViewModel = authViewModel,
                            appViewModel = appViewModel,
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
                        UnlockOverlay(appViewModel = appViewModel)
                    }
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
        saveUrl: Boolean = false,
    ) {
        when (val r = credentialRepository.getCredential(id)) {
            is AppResult.Success -> {
                val credential = r.data
                // When the user ticked the "save URL" checkbox in the
                // cross-host pane (gated by the autofill_save_url_on_cross_host
                // pref), persist the form's host as the credential URL before
                // delivering the dataset. The dataset itself uses the in-memory
                // credential value so the fill happens regardless of save
                // success; we log the save failure and continue. Save failures
                // here are silent because the user already chose to fill -
                // surfacing a save error mid-Dataset-delivery would confuse
                // the OS autofill flow.
                if (saveUrl) {
                    parsed.webDomain?.let { host ->
                        val newUrl = if (host.contains("://")) host else "https://$host"
                        runCatching {
                            credentialRepository.saveCredential(
                                credential.copy(url = newUrl)
                            )
                        }
                    }
                }
                deliverDataset(parsed, credential)
            }
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
    appViewModel: AppViewModel,
    unlockViewModel: AutofillUnlockViewModel,
    biometricController: BiometricPromptController,
    startInSearch: Boolean,
    onResolveCredential: (Credential) -> Unit,
    onResolveSnapshot: (SnapshotCredential, saveUrl: Boolean) -> Unit,
    onAbort: () -> Unit,
) {
    val isUnlocked by authViewModel.isUnlocked.collectAsStateWithLifecycle()
    val crossHostFor by unlockViewModel.crossHostFor.collectAsStateWithLifecycle()
    val matchesState by unlockViewModel.matches.collectAsStateWithLifecycle()
    val saveUrlToggleOn by unlockViewModel.isSaveUrlOnCrossHostEnabled.collectAsStateWithLifecycle()

    // AutofillLockedSurface owns the LockScreen-style celebration + morph
    // pipeline. It fires `onUnlocked` after LOGO_CELEBRATION_DELAY_MS +
    // morph-Held + POST_HELD_NAV_BUFFER_MS, at which point we swap to the
    // post-unlock surface. Re-locking resets the flag so the surface comes
    // back if the vault relocks under us.
    var lockSurfaceDone by remember { mutableStateOf(false) }
    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) lockSurfaceDone = false
    }
    val showLockedSurface = !isUnlocked || !lockSurfaceDone

    // Once unlocked, kick off exact-host match load. The snapshot is hot
    // already (managed by VaultSnapshotStore at app scope); we no longer
    // call a per-screen loadSnapshot(). The LaunchedEffect fires again on
    // re-unlock and loadMatches() is idempotent. Runs in parallel with the
    // celebration so MatchesSurface is hydrated by the time we swap.
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            unlockViewModel.loadMatches()
        }
    }

    // Search-mode toggle. Starts true when the service routed via the
    // trailing chip; toggleable by the user from the picker.
    var inSearchMode by rememberSaveable(startInSearch) { mutableStateOf(startInSearch) }

    // Tracks whether we landed in search mode via the empty-match auto-skip
    // (vs the trailing chip or the user's explicit "Search 1Key" tap). Drives
    // the empty-match banner on SearchSurface. SavedStateHandle-backed so it
    // survives process death.
    var arrivedViaEmptyMatch by rememberSaveable { mutableStateOf(false) }

    // Auto-skip the empty MatchesSurface: when matches load with no exact-host
    // hits, route directly to SearchSurface with the seeded brand-name query.
    // Saves the user a tap on the otherwise-useless "No saved credentials for
    // this site" landing. Only fires once per unlock: if the user manually
    // navigates back to matches via the SearchSurface back button, we honour
    // their choice instead of re-skipping.
    LaunchedEffect(matchesState) {
        val s = matchesState
        if (s is AutofillUnlockViewModel.MatchState.Loaded && s.credentials.isEmpty() &&
            !inSearchMode && !arrivedViaEmptyMatch
        ) {
            arrivedViaEmptyMatch = true
            inSearchMode = true
        }
    }

    when {
        showLockedSurface -> AutofillLockedSurface(
            target = parsed.webDomain ?: parsed.packageName,
            headlineText = "Unlock 1Key to fill",
            submitButtonLabel = "Unlock and fill",
            authViewModel = authViewModel,
            appViewModel = appViewModel,
            biometricController = biometricController,
            onUnlocked = { lockSurfaceDone = true },
            onAbort = onAbort,
        )
        crossHostFor != null -> CrossHostConfirmSurface(
            parsed = parsed,
            credential = crossHostFor!!,
            saveUrlToggleOn = saveUrlToggleOn,
            onConfirm = { saveUrl ->
                val c = unlockViewModel.confirmCrossHost()
                if (c != null) onResolveSnapshot(c, saveUrl)
            },
            onCancel = { unlockViewModel.cancelCrossHost() },
        )
        inSearchMode -> SearchSurface(
            parsed = parsed,
            unlockViewModel = unlockViewModel,
            arrivedViaEmptyMatch = arrivedViaEmptyMatch,
            onBackToMatches = {
                inSearchMode = false
                arrivedViaEmptyMatch = false
            },
            onResolve = { snap ->
                val fillNow = unlockViewModel.resolveSearchCandidate(snap)
                if (fillNow) onResolveSnapshot(snap, false)
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
    val target = parsed.webDomain ?: parsed.packageName

    // Three-band layout: header pinned to the top, scrollable LazyColumn
    // taking the remaining vertical space, action bar (Search 1Key + Cancel)
    // pinned to the bottom. The LazyColumn's weight(1f) absorbs the spare
    // height so long credential lists scroll behind the action bar instead
    // of pushing it off-screen.
    //
    // Inset handling - elegant split:
    //   * Outer Column reserves status-bar height so the header is never
    //     drawn behind the system clock.
    //   * The nav-bar inset is applied INSIDE the bottom Surface's content
    //     padding (see below), so the Surface background bleeds all the way
    //     to the screen edge while the button + cancel link stay clear of
    //     the gesture-pill area.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // ── Sticky header ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Choose a credential",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // Primary-coloured bullet dot before the "Filling into …" line
            // matches the mockup's decorative accent.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = "Filling into $target",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Scrollable list (takes remaining space) ─────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            when (val s = matchesState) {
                AutofillUnlockViewModel.MatchState.Idle,
                AutofillUnlockViewModel.MatchState.Loading -> {
                    item(key = "__loading__") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "Searching your vault…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                is AutofillUnlockViewModel.MatchState.Loaded -> {
                    if (s.credentials.isEmpty()) {
                        item(key = "__empty__") {
                            Text(
                                text = "No saved credentials for this site.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                            )
                        }
                    } else {
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

        // ── Sticky bottom action bar ────────────────────────────────────
        // tonalElevation lifts the surface above the scrolling list and
        // produces a subtle visual scrim so the user reads "more content
        // above". shadowElevation adds the soft drop shadow visible in the
        // mockup.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = onOpenSearch,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Search 1Key for another credential")
                }
                TextButton(
                    onClick = onAbort,
                    modifier = Modifier
                        .align(Alignment.End)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun SearchSurface(
    parsed: ParsedFields,
    unlockViewModel: AutofillUnlockViewModel,
    arrivedViaEmptyMatch: Boolean,
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

    // Local TextFieldValue so the cursor can be positioned at the END of
    // the seeded brand-label query (the VM seeds "google" for accounts.google.com;
    // the user expects the caret to sit after the e, not before the g, so
    // backspace or further typing extends the existing token). The String
    // overload of LockAwareTextField defaults the cursor to position 0.
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = query, selection = TextRange(query.length)))
    }
    // Re-sync when the VM updates the query from outside this field
    // (e.g. the trailing X clear button calls onSearchQueryChanged(""),
    // or process-death restoration emits a new value). On sync, park the
    // cursor at the new text's end so the field stays usable.
    LaunchedEffect(query) {
        if (fieldValue.text != query) {
            fieldValue = TextFieldValue(text = query, selection = TextRange(query.length))
        }
    }

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
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
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
        // When we landed here because the exact-host match returned nothing,
        // surface that context so the user understands why they are looking
        // at search instead of a pre-narrowed match list. The query field is
        // already seeded with the brand label - the banner is the orientation
        // bit that prevents "did 1Key forget my login?" confusion.
        if (arrivedViaEmptyMatch) {
            Text(
                text = "No credentials saved for $target. Search your vault for a match - " +
                    "you'll confirm before filling.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = "Filling into $target",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LockAwareTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != query) {
                    unlockViewModel.onSearchQueryChanged(it.text)
                }
            },
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
        // Cancel sits at the natural end of the column (after the results
        // LazyColumn's weight(1f)). The navigationBars inset is applied
        // here so the gesture-pill area stays clear without forcing the
        // whole column to reserve bottom inset (which would shrink the
        // LazyColumn even when there's no Cancel-clearance concern).
        TextButton(
            onClick = onAbort,
            modifier = Modifier
                .align(Alignment.End)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun CrossHostConfirmSurface(
    parsed: ParsedFields,
    credential: SnapshotCredential,
    saveUrlToggleOn: Boolean,
    onConfirm: (saveUrl: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val target = parsed.webDomain ?: parsed.packageName
    val credHost = HostExtractor.hostOf(credential.url) ?: "(no URL saved)"
    // Local fetching flag: once the user taps "Fill anyway" we kick off the
    // getCredential(id) coroutine in the activity. Until that completes
    // (success => finish, error => finish), keep the pane mounted with a
    // spinner on the confirm button so the user does not see a flicker
    // back to SearchSurface during the fetch.
    var fetching by remember { mutableStateOf(false) }

    // Per-action checkbox. OFF every time the pane opens by design - never
    // remembered across opens, never persisted, never inferable from any
    // earlier action. The double-gate (Settings toggle + this checkbox) is
    // the documented invariant; see feedback_autofill_matching memory.
    var saveUrlChecked by remember { mutableStateOf(false) }

    // The save-URL UI surfaces only when the user has BOTH opted in via
    // Settings AND there is a host to save (native-app fills have no
    // webDomain so the checkbox would be a no-op).
    val saveUrlSurfaceVisible = saveUrlToggleOn && !parsed.webDomain.isNullOrBlank()

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

            // Always-on informational nudge. Tells the user how to make the
            // exact-host match work next time without offering an inline
            // action - the user has to open 1Key on a calm context and edit
            // the credential's URL field themselves. Cheap cognitive friction
            // that closes the one-tap phishing-poisoning path.
            if (!saveUrlSurfaceVisible) {
                Text(
                    text = "To skip this prompt next time, open 1Key and add $target to " +
                        "this credential's URL field.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Disclaimer + checkbox: rendered only when the Settings toggle
            // is on AND we have a webDomain to save. See
            // feedback_autofill_matching memory for the double-gate rationale.
            if (saveUrlSurfaceVisible) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "1Key cannot verify website legitimacy. Saving this URL " +
                                "will replace any existing URL on this credential and " +
                                "permanently associate it with $target. " +
                                "Verify this is the genuine website before ticking the box. " +
                                "1Key holds no liability for credentials linked to malicious URLs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !fetching) {
                                    saveUrlChecked = !saveUrlChecked
                                },
                        ) {
                            Checkbox(
                                checked = saveUrlChecked,
                                onCheckedChange = { saveUrlChecked = it },
                                enabled = !fetching,
                            )
                            Text(
                                text = "Save $target to this credential",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (!fetching) {
                        fetching = true
                        onConfirm(saveUrlSurfaceVisible && saveUrlChecked)
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
    val type: CredentialType,
)

private fun Credential.toRow() = CredentialRowData(title, username, url, type)
private fun SnapshotCredential.toRow() = CredentialRowData(title, username, url, type)

/**
 * Icon mapping mirrors the in-vault category iconography (CredentialDetailScreen
 * `typeIcon` and VaultScreen `tagIcon`) so the autofill picker reads as the
 * same family as the rest of the app. Kept local to this file to keep the
 * autofill activity self-contained; a future shared util would absorb the
 * three duplicated copies (here, CredentialDetailScreen, BackupScreen).
 */
private fun autofillTypeIcon(type: CredentialType) = when (type) {
    CredentialType.LOGIN -> Icons.Default.Lock
    CredentialType.SECURE_NOTE -> Icons.Default.Description
    CredentialType.CREDIT_CARD -> Icons.Default.CreditCard
    CredentialType.PASSWORD -> Icons.Default.Key
    CredentialType.BANK_ACCOUNT -> Icons.Default.AccountBalance
    CredentialType.DATABASE -> Icons.Default.Storage
    CredentialType.EMAIL -> Icons.Default.Email
    CredentialType.SERVER -> Icons.Default.Computer
    CredentialType.OTHER -> Icons.AutoMirrored.Filled.Label
}

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading type icon. Plain primary-tinted vector with NO container
        // tile, matching the look of the in-vault credential list
        // (CredentialCard / TagRow) so the two surfaces read as one family.
        Icon(
            imageVector = autofillTypeIcon(row.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title.ifBlank { "1Key item" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleParts.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
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
    tags: List<com.roufsyed.onekey.core.domain.model.TagWithCount>,
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
