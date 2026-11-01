package com.roufsyed.onekey.feature.twofa.presentation.screen

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
import androidx.compose.material.icons.filled.Security
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.roufsyed.onekey.core.presentation.monogram.SiteMonogram
import androidx.compose.material3.*
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
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
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.feature.twofa.presentation.viewmodel.HotpListEntry
import com.roufsyed.onekey.feature.twofa.presentation.viewmodel.RotatingOtpEntry
import com.roufsyed.onekey.feature.twofa.presentation.viewmodel.TwoFaListEntry
import com.roufsyed.onekey.feature.twofa.presentation.viewmodel.TwoFaListViewModel
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
    // Same card language as the Passkeys tab: monogram tile, title, account,
    // linked-entry badge, then the code and its timer on the right. Steam
    // entries (5 alphanumerics) render as a single block via formatOtpCode;
    // 6/7/8-digit codes get chunked.
    val params = entry.credential.otpParams
    val displayCode = if (params != null) formatOtpCode(params, entry.code) else entry.code
    val expiring = entry.remainingSeconds <= 5
    OtpCard(entry = entry, onClick = { onCopyCode(entry.code) }, onLongClick = onLongClick) {
        OtpCode(displayCode, color = if (expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp,
                color = if (expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${entry.remainingSeconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = if (expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * HOTP row variant: the same card, with a "Generate next code" button where the
 * timer would be. The code is the most recently generated one, or a dash
 * placeholder until the user taps generate. Tapping the row copies the last
 * code, or generates one when there is none yet; long-press still deletes.
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
    OtpCard(
        entry = entry,
        onClick = {
            val code = entry.code
            if (code != null) onCopyCode(code) else onGenerate()
        },
        onLongClick = onLongClick,
    ) {
        OtpCode(displayCode, color = if (entry.code != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        FilledIconButton(
            onClick = onGenerate,
            enabled = !entry.generating,
            modifier = Modifier.size(40.dp),
        ) {
            if (entry.generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Generate next code")
            }
        }
    }
}

/**
 * Shell shared by both row kinds: the monogram tile, the title, the account,
 * the linked-entry badge, then [trailing] (code plus timer, or code plus
 * button). One row, so the code reads as part of the account rather than as a
 * second block beneath it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OtpCard(
    entry: TwoFaListEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteMonogram(label = entry.credential.title, fallbackIcon = Icons.Default.Security)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.credential.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.credential.username.isNotEmpty()) {
                    Text(
                        entry.credential.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.isLinkedCredential) {
                    val categoryLabel = (entry.credential.tags.firstOrNull() ?: "Login") + " credential"
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(categoryLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** The code itself: monospace, sized to share a line with the account column. */
@Composable
private fun OtpCode(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 26.sp,
            letterSpacing = 2.sp,
        ),
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}
