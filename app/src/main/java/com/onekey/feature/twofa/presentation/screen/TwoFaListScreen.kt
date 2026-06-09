package com.onekey.feature.twofa.presentation.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import com.onekey.core.presentation.util.oneKeyTopBarColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.feature.twofa.presentation.viewmodel.HotpListEntry
import com.onekey.feature.twofa.presentation.viewmodel.RotatingOtpEntry
import com.onekey.feature.twofa.presentation.viewmodel.TwoFaListEntry
import com.onekey.feature.twofa.presentation.viewmodel.TwoFaListViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFaListScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    onScanQr: () -> Unit,
    viewModel: TwoFaListViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val hideTopBarOnScroll by viewModel.hideTopBarOnScroll.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Store the credential id (saveable) rather than the full entry so the
    // confirm-delete dialog survives rotation. The matching entry is resolved from the
    // current `entriesList` at render time - if the entry has since disappeared, the
    // dialog simply doesn't show, which is the correct behavior.
    var pendingDeleteCredentialId by rememberSaveable { mutableStateOf<String?>(null) }
    // Sheets driven by the FAB. `showFabPicker` opens the Scan-vs-Manual chooser;
    // picking Manual flips `showManualSheet` while QR routes through `onScanQr`.
    // Both saveable so a config change mid-entry doesn't lose the open state.
    var showFabPicker by rememberSaveable { mutableStateOf(false) }
    var showManualSheet by rememberSaveable { mutableStateOf(false) }

    // Pre-seed and clamp the scroll state to the smaller (56 dp) bar height.
    // M3 1.3.2 sets heightOffsetLimit via a SideEffect inside TopAppBar that
    // fires after the first measure - a saved heightOffset from a previous
    // session (default limit -Float.MAX_VALUE) could otherwise drive the
    // bar's layout height negative on the first frame and crash layout(...).
    val barHeightLimitPx = with(LocalDensity.current) { -56.dp.toPx() }
    val topAppBarState = rememberTopAppBarState(initialHeightOffsetLimit = barHeightLimitPx)
    if (topAppBarState.heightOffsetLimit != barHeightLimitPx) {
        topAppBarState.heightOffsetLimit = barHeightLimitPx
    }
    if (topAppBarState.heightOffset < barHeightLimitPx) {
        topAppBarState.heightOffset = barHeightLimitPx
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = topAppBarState,
        canScroll = { hideTopBarOnScroll },
    )
    LaunchedEffect(hideTopBarOnScroll) {
        if (!hideTopBarOnScroll) {
            topAppBarState.heightOffset = 0f
            topAppBarState.contentOffset = 0f
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("2FA Codes") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showFabPicker = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add 2FA code")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val entriesList = entries
        if (entriesList == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (entriesList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No 2FA accounts", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap the + button to scan or enter a setup key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .preferredFrameRate(FrameRateCategory.High),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entriesList, key = { it.credential.id }) { entry ->
                    val onCopy: (String) -> Unit = { code ->
                        // Routes through SecureClipboardManager - its app-singleton
                        // scope makes the 30s clear survive navigation away from this
                        // screen, which is exactly when the user needs it (they
                        // copy the code, then switch apps to paste it).
                        viewModel.copyCode(code)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Code copied - clipboard will be cleared automatically in 30s",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                    when (entry) {
                        is RotatingOtpEntry -> RotatingOtpRow(
                            entry = entry,
                            onLongClick = { pendingDeleteCredentialId = entry.credential.id },
                            onCopyCode = onCopy,
                        )
                        is HotpListEntry -> HotpRow(
                            entry = entry,
                            onLongClick = { pendingDeleteCredentialId = entry.credential.id },
                            onGenerate = { viewModel.generateNextHotpCode(entry) },
                            onCopyCode = onCopy,
                        )
                    }
                }
            }
        }
    }

    if (showFabPicker) {
        AddOtpFabSheet(
            onScanQr = onScanQr,
            onEnterManually = { showManualSheet = true },
            onDismiss = { showFabPicker = false },
        )
    }

    if (showManualSheet) {
        ManualOtpEntrySheet(
            onDismiss = { showManualSheet = false },
            onSaved = { showManualSheet = false },
        )
    }

    pendingDeleteCredentialId?.let { id ->
        // If the entry has since vanished (e.g. removed in another flow) we skip rendering;
        // the saved id remains harmless in savedInstanceState until a new long-press
        // overwrites it. Cleaning it up via LaunchedEffect would be tidier but adds churn
        // for a corner case nobody hits in practice.
        val entry = entries?.firstOrNull { it.credential.id == id } ?: return@let
        LockAwareDialog(
            onDismissRequest = { pendingDeleteCredentialId = null },
            title = {
                Text(if (entry.isLinkedCredential) "Remove 2FA code?" else "Remove 2FA account?")
            },
            text = {
                Text(
                    if (entry.isLinkedCredential)
                        "Only 2FA code will be removed from \"${entry.credential.title}\". The linked credential will be untouched."
                    else
                        "\"${entry.credential.title}\" will be removed. This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeTotp(entry)
                        pendingDeleteCredentialId = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCredentialId = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RotatingOtpRow(
    entry: RotatingOtpEntry,
    onLongClick: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    // Card-style row to match the recycle-bin list. The OTP code stays the visual
    // hero - same monospace + countdown ring - now sitting on the `surface` tier
    // with a 1 dp tonal lift above the screen body. Steam entries (5 alphanumerics)
    // render as a single block via formatOtpCode; 6/7/8-digit codes get chunked.
    val params = entry.credential.otpParams
    val displayCode = if (params != null) formatOtpCode(params, entry.code) else entry.code
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = { onCopyCode(entry.code) }, onLongClick = onLongClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            EntryHeader(entry)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayCode,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 32.sp,
                        letterSpacing = 4.sp,
                    ),
                    color = if (entry.remainingSeconds <= 5) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = if (entry.remainingSeconds <= 5) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                    )
                    Text("${entry.remainingSeconds}s", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * HOTP row variant. Mirrors [RotatingOtpRow]'s visual rhythm - same title /
 * username / linked-badge header - but trades the timer ring for a
 * "Generate next code" affordance. The body shows the most-recently-generated
 * code or a placeholder dash sequence until the user taps generate.
 *
 * Tapping the entire row (anywhere outside the button) copies the last code, the
 * same gesture rotating rows use. Tapping the button triggers the atomic counter
 * advance via the VM. Long-press still routes to the delete dialog.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HotpRow(
    entry: HotpListEntry,
    onLongClick: () -> Unit,
    onGenerate: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val params = entry.credential.otpParams
    val displayCode = entry.code?.let { code ->
        if (params != null) formatOtpCode(params, code) else code
    } ?: "- - -"
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                // Row-tap copies the last generated code; if no code exists yet we
                // bounce the tap to the generate flow so the row isn't dead.
                onClick = {
                    val code = entry.code
                    if (code != null) onCopyCode(code) else onGenerate()
                },
                onLongClick = onLongClick,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            EntryHeader(entry)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayCode,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 32.sp,
                        letterSpacing = 4.sp,
                    ),
                    color = if (entry.code != null) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = onGenerate,
                    enabled = !entry.generating,
                    modifier = Modifier.size(44.dp),
                ) {
                    if (entry.generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Generate next code",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryHeader(entry: TwoFaListEntry) {
    Text(entry.credential.title, style = MaterialTheme.typography.titleMedium)
    if (entry.credential.username.isNotEmpty()) {
        Text(
            entry.credential.username,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (entry.isLinkedCredential) {
        val categoryLabel = (entry.credential.tags.firstOrNull() ?: "Login") + " credential"
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                categoryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
