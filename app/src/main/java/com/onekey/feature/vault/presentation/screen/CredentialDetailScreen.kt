package com.onekey.feature.vault.presentation.screen

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.onekey.core.presentation.util.oneKeyTopBarColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.CredentialHistoryEntry
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.domain.model.CustomField
import com.onekey.core.domain.model.OtpParams
import com.onekey.core.domain.model.OtpType
import com.onekey.core.domain.model.Tag
import com.onekey.core.presentation.lockaware.LockAwareDialog
import com.onekey.core.presentation.lockaware.LockAwareDropdownMenu
import com.onekey.core.presentation.lockaware.LockAwareModalBottomSheet
import com.onekey.core.presentation.lockaware.LockAwareOutlinedTextField
import com.onekey.core.presentation.markdown.FallbackReason
import com.onekey.core.presentation.markdown.LinkConfirmDialog
import com.onekey.core.presentation.markdown.LinkRequest
import com.onekey.core.presentation.markdown.MarkdownEditorField
import com.onekey.core.presentation.markdown.MarkdownNotesView
import com.onekey.core.presentation.util.toFormattedDateTime
import com.onekey.core.presentation.util.toRelativeTime
import com.onekey.feature.twofa.domain.OtpAuthUriParser
import com.onekey.feature.twofa.presentation.screen.TotpWidget
import com.onekey.feature.vault.presentation.viewmodel.CredentialDetailUiState
import com.onekey.feature.vault.presentation.viewmodel.CredentialDetailViewModel
import com.onekey.feature.vault.presentation.viewmodel.DeleteKind
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private data class FieldSuggestion(val label: String, val sensitive: Boolean)

private fun fieldSuggestionsFor(type: CredentialType): List<FieldSuggestion> = when (type) {
    CredentialType.BANK_ACCOUNT -> listOf(
        FieldSuggestion("Account Holder", sensitive = false),
        FieldSuggestion("Account Number", sensitive = true),
        FieldSuggestion("Bank Name", sensitive = false),
        FieldSuggestion("IFSC / Routing", sensitive = true),
        FieldSuggestion("IBAN", sensitive = true),
        FieldSuggestion("Branch", sensitive = false),
        FieldSuggestion("PIN", sensitive = true),
    )
    CredentialType.CREDIT_CARD -> listOf(
        FieldSuggestion("Cardholder", sensitive = false),
        FieldSuggestion("Card Number", sensitive = true),
        FieldSuggestion("Expiry", sensitive = false),
        FieldSuggestion("CVV", sensitive = true),
        FieldSuggestion("PIN", sensitive = true),
        FieldSuggestion("Billing Zip", sensitive = false),
        FieldSuggestion("Network", sensitive = false),
    )
    CredentialType.SERVER -> listOf(
        FieldSuggestion("Host", sensitive = false),
        FieldSuggestion("Port", sensitive = false),
        FieldSuggestion("SSH Key Path", sensitive = false),
        FieldSuggestion("API Token", sensitive = true),
    )
    CredentialType.DATABASE -> listOf(
        FieldSuggestion("Host", sensitive = false),
        FieldSuggestion("Port", sensitive = false),
        FieldSuggestion("Database", sensitive = false),
        FieldSuggestion("Connection String", sensitive = true),
    )
    CredentialType.EMAIL -> listOf(
        FieldSuggestion("IMAP Host", sensitive = false),
        FieldSuggestion("SMTP Host", sensitive = false),
        FieldSuggestion("App Password", sensitive = true),
    )
    else -> emptyList()
}

// Types that don't surface auth (username/password/url/totp) inputs at all. Notes still
// flows through `notes` - sometimes promoted to a primary content area.
private val NO_AUTH_TYPES = setOf(
    CredentialType.SECURE_NOTE,
    CredentialType.CREDIT_CARD,
    CredentialType.OTHER,
)

// One-line syntax cheat-sheet shown under the markdown editor's notes field so
// users discover the supported subset without needing the discarded toolbar.
// Kept short because supportingText is rendered in labelSmall and wraps on
// narrow screens otherwise.
private const val MARKDOWN_SYNTAX_HINT =
    "Markdown: **bold** _italic_ # heading - list `code` [text](url)"

private fun typeIcon(type: CredentialType) = when (type) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailScreen(
    onBack: () -> Unit,
    onDeleted: (DeleteKind, String) -> Unit,
    onSavedNew: (String) -> Unit,
    viewModel: CredentialDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Display-only snapshot, exposed alongside uiState rather than mixed into it
    // so the credential data carried by uiState stays purely the live row.
    val displayAccessedAt by viewModel.displayAccessedAt.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is CredentialDetailUiState.Saved -> onSavedNew(state.newCredentialId)
            is CredentialDetailUiState.Deleted -> onDeleted(state.kind, state.credentialId)
            else -> Unit
        }
    }

    when (val state = uiState) {
        is CredentialDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is CredentialDetailUiState.Success -> {
            // Hoisted above the isEditing branch so the edit content can drive the live-preview
            // editor swap with the same signals the view content uses for its render-toggle.
            val isNotesMarkdownEnabled by viewModel.isNotesMarkdownEnabled.collectAsStateWithLifecycle()
            val plainSourceIds by viewModel.plainSourceCredentialIds.collectAsStateWithLifecycle()
            if (state.isEditing) {
                CredentialEditContent(
                    credential = state.credential,
                    availableTags = availableTags,
                    isNotesMarkdownEnabled = isNotesMarkdownEnabled,
                    plainSourceCredentialIds = plainSourceIds,
                    onSave = viewModel::save,
                    onAddTag = viewModel::addTag,
                    onBeginCameraSession = viewModel::beginCameraSession,
                    onEndCameraSession = viewModel::endCameraSession,
                    onBack = onBack,
                )
            } else {
                val binEnabled by viewModel.isRecycleBinEnabled.collectAsStateWithLifecycle()
                val clipboardCountdown by viewModel.clipboardCountdown.collectAsStateWithLifecycle()
                CredentialViewContent(
                    credential = state.credential,
                    displayAccessedAt = displayAccessedAt,
                    history = history,
                    binEnabled = binEnabled,
                    isInRecycleBin = state.credential.deletedAt != null,
                    clipboardCountdown = clipboardCountdown,
                    isNotesMarkdownEnabled = isNotesMarkdownEnabled,
                    plainSourceCredentialIds = plainSourceIds,
                    notesEvent = viewModel.notesEvent,
                    onMaybeAutoFlip = viewModel::maybeAutoFlip,
                    onToggleViewMode = viewModel::toggleViewMode,
                    onEdit = viewModel::startEditing,
                    onDelete = viewModel::delete,
                    onDeleteNow = viewModel::deleteNow,
                    onRestore = viewModel::restore,
                    onCopyUsername = viewModel::copyUsername,
                    onCopyPassword = viewModel::copyPassword,
                    onBack = onBack,
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

    val pendingConflict by viewModel.pendingRestoreConflict.collectAsStateWithLifecycle()
    pendingConflict?.let { conflict ->
        RestoreConflictDialog(
            title = conflict.binItem.title,
            username = conflict.binItem.username,
            onMerge = viewModel::resolveRestoreByMerging,
            onKeepBoth = viewModel::resolveRestoreByKeepingBoth,
            onCancel = viewModel::cancelRestoreConflict,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialViewContent(
    credential: Credential,
    displayAccessedAt: Long?,
    history: List<CredentialHistoryEntry>,
    binEnabled: Boolean,
    isInRecycleBin: Boolean,
    clipboardCountdown: Int?,
    isNotesMarkdownEnabled: Boolean,
    plainSourceCredentialIds: Set<String>,
    notesEvent: SharedFlow<String>,
    onMaybeAutoFlip: (String, Long?) -> Unit,
    onToggleViewMode: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDeleteNow: () -> Unit,
    onRestore: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    // rememberSaveable so config changes (rotation, multi-window resize, fold) don't
    // collapse the delete-confirm dialog, drop the password back to masked, or fold the
    // history panel back up while the user is mid-interaction.
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }

    // Snackbar plumbing for notes-display transient events (auto-flip toast,
    // blocked-link copy). Lives at the screen level so notes-section and dialog
    // routing emit through a single channel.
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Pending link confirmation. `LinkRequest` is set when the user taps an
    // allow-listed scheme inside MarkdownNotesView; the dialog clears it on
    // confirm or dismiss. Plain `remember` (not Saveable) because LinkRequest
    // carries an android.net.Uri which is not Parcelable through SavedState.
    var pendingLink by remember { mutableStateOf<LinkRequest?>(null) }

    // Whether the overflow menu's toggle item should be shown. Hide when the
    // global flag is off (no toggle has any effect) or when the credential has
    // no notes content (nothing to render). Recycle-bin gating already lives in
    // the surrounding `if (!isInRecycleBin)` block.
    val showViewModeToggle = isNotesMarkdownEnabled && credential.notes.isNotEmpty()

    // Single subscription drains every transient event the VM emits and routes
    // it to the SnackbarHost. The auto-flip emission lands here on the first
    // composition of an imported credential's detail screen; blocked-link
    // taps also funnel through the same flow.
    LaunchedEffect(notesEvent) {
        notesEvent.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = typeIcon(credential.type),
                            contentDescription = credential.type.displayName,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(credential.title)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    if (!isInRecycleBin) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                if (credential.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (credential.isFavorite) "Remove from favourites" else "Add to favourites",
                                tint = if (credential.isFavorite) MaterialTheme.colorScheme.primary
                                       else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Default.Delete, "Delete") }
                        if (showViewModeToggle) {
                            // Overflow opens a single-item dropdown that flips between
                            // rendered markdown and plain-source for *this* credential.
                            // LockAwareDropdownMenu (not raw M3 DropdownMenu) because the
                            // UnsafeUnlockableSurface lint blocks raw surfaces outside the
                            // lockaware package.
                            IconButton(onClick = { overflowMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            LockAwareDropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = { overflowMenuExpanded = false },
                            ) {
                                val inPlainMode = credential.id in plainSourceCredentialIds
                                DropdownMenuItem(
                                    text = { Text(if (inPlainMode) "View rendered" else "View source") },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onToggleViewMode(credential.id)
                                    },
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Flat divider-separated sections - same visual language as the home and list
        // screens. No card chrome, no per-row gap; dividers carry the structural rhythm.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            androidx.compose.animation.AnimatedVisibility(visible = clipboardCountdown != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Clipboard clears in ${clipboardCountdown}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (isInRecycleBin) {
                RecycleBinBanner(onRestore = onRestore)
            }
            if (credential.type in NO_AUTH_TYPES) {
                if (credential.notes.isNotEmpty()) {
                    NotesSection(
                        credential = credential,
                        label = if (credential.type == CredentialType.SECURE_NOTE) "Content" else "Notes",
                        isNotesMarkdownEnabled = isNotesMarkdownEnabled,
                        isInPlainSourceMode = credential.id in plainSourceCredentialIds,
                        snackbarHostState = snackbarHostState,
                        onMaybeAutoFlip = onMaybeAutoFlip,
                        onLinkTapped = { pendingLink = it },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            } else {
                if (credential.username.isNotEmpty()) {
                    DetailField(
                        label = "Username",
                        value = credential.username,
                        trailing = {
                            IconButton(onClick = onCopyUsername) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy username")
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (credential.password.isNotEmpty()) {
                    DetailField(
                        label = "Password",
                        value = if (showPassword) credential.password else "••••••••",
                        sensitive = showPassword,
                        trailing = {
                            Row {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                }
                                IconButton(onClick = onCopyPassword) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy password")
                                }
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (credential.url.isNotEmpty()) {
                    DetailField("URL", credential.url)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (credential.notes.isNotEmpty()) {
                    NotesSection(
                        credential = credential,
                        label = "Notes",
                        isNotesMarkdownEnabled = isNotesMarkdownEnabled,
                        isInPlainSourceMode = credential.id in plainSourceCredentialIds,
                        snackbarHostState = snackbarHostState,
                        onMaybeAutoFlip = onMaybeAutoFlip,
                        onLinkTapped = { pendingLink = it },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                // TotpWidget renders rotating codes (TOTP / Steam). HOTP entries take
                // the dedicated "Generate next code" UI on the 2FA list (added in C5);
                // the rotating widget is gated on type here to keep the editor screen
                // focused on its primary case and prevent a HOTP secret from silently
                // ticking under a 30-second timer.
                credential.otpParams
                    ?.takeIf {
                        credential.type != CredentialType.BANK_ACCOUNT &&
                            (it.type == OtpType.TOTP || it.type == OtpType.STEAM)
                    }
                    ?.let { params ->
                        TotpWidget(params = params)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
            }
            credential.customFields.forEach { field ->
                DetailField(
                    label = field.key,
                    value = if (field.isSensitive) "••••••••" else field.value,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (credential.tags.isNotEmpty()) {
                TagsView(tags = credential.tags)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            MetadataSection(credential = credential, displayAccessedAt = displayAccessedAt)

            if (history.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                HistorySection(
                    history = history,
                    expanded = historyExpanded,
                    onToggle = { historyExpanded = !historyExpanded },
                )
            }
        }
    }

    if (showDeleteDialog) {
        BulkDeleteDialog(
            count = 1,
            binEnabled = binEnabled,
            onMoveToBin = { showDeleteDialog = false; onDelete() },
            onDeleteNow = { showDeleteDialog = false; onDeleteNow() },
            onCancel = { showDeleteDialog = false },
        )
    }

    pendingLink?.let { request ->
        LinkConfirmDialog(
            request = request,
            onConfirm = {
                // FLAG_ACTIVITY_NEW_TASK is required because the screen sits
                // under MainActivity but the intent dispatches to an external
                // browser - without the flag some OEM browsers reject the
                // launch from a non-Activity-typed context.
                // runCatching swallows ActivityNotFoundException per the
                // LinkConfirmDialog KDoc contract (no browser installed).
                runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        request.parsedUri,
                    ).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                pendingLink = null
            },
            onDismiss = { pendingLink = null },
        )
    }
}

@Composable
private fun RecycleBinBanner(onRestore: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "In recycle bin",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "Restore to keep this credential, or empty the bin to delete it for good.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRestore) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Restore")
            }
        }
    }
}

@Composable
private fun MetadataSection(credential: Credential, displayAccessedAt: Long?) {
    // Cache the formatted strings so SimpleDateFormat-style work doesn't re-run on every
    // recomposition (e.g. on every reveal-toggle and history-expand).
    val createdText = remember(credential.createdAt) {
        if (credential.createdAt > 0L) "Created: ${credential.createdAt.toFormattedDateTime()}" else null
    }
    val updatedText = remember(credential.updatedAt) {
        if (credential.updatedAt > 0L) "Modified: ${credential.updatedAt.toFormattedDateTime()}" else null
    }
    // displayAccessedAt is the snapshot the ViewModel froze at screen-open, so
    // the user sees the *previous* access (their last visit) rather than "now"
    // - we don't read credential.accessedAt here on purpose, that field is the
    // live DB value the bump just advanced to. Hidden when null (new
    // unsaved credential, or extreme edge case where the snapshot read failed).
    val accessedText = remember(displayAccessedAt) {
        displayAccessedAt
            ?.takeIf { it > 0L }
            ?.let { "Last accessed: ${it.toFormattedDateTime()}" }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        createdText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        updatedText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        accessedText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistorySection(
    history: List<CredentialHistoryEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header row is the entire tap target - matches the section-row affordance the
        // home and list screens use. No card chrome.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("History", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${history.size} ${if (history.size == 1) "revision" else "revisions"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse history" else "Expand history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                history.forEachIndexed { index, entry ->
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HistoryEntryRow(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(entry: CredentialHistoryEntry) {
    var showPassword by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            entry.modifiedAt.toRelativeTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (entry.title.isNotEmpty()) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
        }
        if (entry.username.isNotEmpty()) {
            Text(
                "User: ${entry.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.password.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showPassword) "Pass: ${entry.password}" else "Pass: ••••••••",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (entry.url.isNotEmpty()) {
            Text(
                "URL: ${entry.url}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Notes block. Routes a credential's notes content between markdown-rendered
 * and plain-source rendering, surfaces the 64 KiB over-cap banner, and runs the
 * one-shot auto-flip side effect for first-view-post-import credentials.
 *
 * Visual chrome matches [DetailField] (header in `labelSmall` + primary tint;
 * body in `bodyMedium`) so the row blends with neighbouring sections.
 *
 * Decision tree (see Phase 7 design state_diagram):
 *   - global flag OFF or per-credential override ON -> SelectionContainer + Text
 *     (the existing v1.0.0 path, no banner emitted).
 *   - otherwise -> [MarkdownNotesView] with the fallback callback wired so
 *     SIZE_CAP_EXCEEDED flips the local banner state and PARSE_FAILED stays
 *     silent per design.
 *
 * Banner state is `rememberSaveable` keyed on credential id + notes content so
 * a config change does not re-fire the callback (or lose the visible banner),
 * but navigating to a different credential or editing the notes below the cap
 * resets to false. Inputs that satisfy the cap on the next emission simply
 * skip the [LaunchedEffect] branch and the banner stays false.
 *
 * @param credential live credential carrying the notes text and importedAt hint.
 * @param label header copy ("Notes" or "Content" depending on the credential
 *   type at the call site).
 * @param isNotesMarkdownEnabled global Settings master switch; `false` collapses
 *   to plain rendering regardless of per-credential state.
 * @param isInPlainSourceMode `true` when the user has flipped this credential
 *   to plain-source mode via the overflow menu.
 * @param snackbarHostState shared host so blocked-link toasts surface at the
 *   screen level rather than nested inside this composable.
 * @param onMaybeAutoFlip first-view-post-import heuristic dispatch; runs once
 *   per credential.id transition.
 * @param onLinkTapped routes an allow-listed link tap up to the screen so the
 *   [LinkConfirmDialog] can mount above the Scaffold.
 */
@Composable
private fun NotesSection(
    credential: Credential,
    label: String,
    isNotesMarkdownEnabled: Boolean,
    isInPlainSourceMode: Boolean,
    snackbarHostState: SnackbarHostState,
    onMaybeAutoFlip: (String, Long?) -> Unit,
    onLinkTapped: (LinkRequest) -> Unit,
) {
    // Run the auto-flip exactly once per credential.id transition. The VM's
    // own membership-in-autoFlippedCredentialIds gate makes this idempotent
    // across recompositions too, so the duplicate guard is belt-and-braces.
    LaunchedEffect(credential.id) {
        onMaybeAutoFlip(credential.id, credential.importedAt)
    }

    // Banner state lives here, not on the VM, because the cap classification
    // is a property of the rendered source. Keyed on credential.id and notes
    // content so a different credential or an edit that brings notes back
    // under the cap automatically clears stale banner state on next composition.
    var sizeCapBannerVisible by rememberSaveable(credential.id, credential.notes) {
        mutableStateOf(false)
    }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

        if (sizeCapBannerVisible) {
            // Visual language identical to RecycleBinBanner so banner shapes
            // stay consistent across this screen. Non-dismissible - the cap is
            // a property of the source, not a user choice.
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "This note exceeded 64 KiB. Showing as plain text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        if (!isNotesMarkdownEnabled || isInPlainSourceMode) {
            // Existing v1.0.0 plain-text path. Selection container so the user
            // can long-press to copy the source verbatim.
            SelectionContainer {
                Text(credential.notes, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            MarkdownNotesView(
                source = credential.notes,
                onLinkTapped = onLinkTapped,
                onBlockedLink = { scheme ->
                    // Blocked-link copy is a view-local concern - the VM does
                    // not need to know which schemes the user tapped. Route
                    // directly through the shared SnackbarHostState via a
                    // launched coroutine since showSnackbar is suspending.
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Link scheme not allowed: $scheme")
                    }
                },
                onFallback = { reason ->
                    when (reason) {
                        FallbackReason.SIZE_CAP_EXCEEDED -> {
                            sizeCapBannerVisible = true
                        }
                        // PARSE_FAILED stays silent per design - the user can
                        // still read and copy the raw source through the
                        // verbatim fallback that MarkdownNotesView already
                        // renders internally.
                        FallbackReason.PARSE_FAILED -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String,
    trailing: @Composable (() -> Unit)? = null,
    sensitive: Boolean = false,
) {
    // Flat row to match home / list pattern. Container handles the divider after.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            // Sensitive values bypass SelectionContainer so an OS-menu long-press copy
            // can't sidestep SecureClipboardManager - the in-app copy button is the
            // only way to put the value on the clipboard, and it auto-clears.
            // clearAndSetSemantics replaces the accessibility node text with a neutral
            // description so screen readers and automation services cannot read the value.
            if (sensitive) {
                DisableSelection {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = "Sensitive value hidden"
                        },
                    )
                }
            } else {
                SelectionContainer {
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialEditContent(
    credential: Credential,
    availableTags: List<Tag>,
    isNotesMarkdownEnabled: Boolean,
    plainSourceCredentialIds: Set<String>,
    onSave: (Credential) -> Unit,
    onAddTag: (String) -> Unit,
    onBeginCameraSession: () -> Unit,
    onEndCameraSession: () -> Unit,
    onBack: () -> Unit,
) {
    // rememberSaveable on the simple String/Boolean fields so a config change (rotation,
    // multi-window resize, fold) doesn't wipe partially-typed edits. Bundle persistence
    // is acceptable here - Android writes savedInstanceState through file-based encryption
    // at rest, so the typed-but-unsaved values aren't sitting on disk in cleartext.
    //
    // selectedTags and customFields stay on plain `remember` because List<Tag> and
    // List<CustomField> need a Saver/Parcelable to round-trip through Bundle, and the
    // benefit isn't worth that scope for now.
    var title by rememberSaveable(credential.id) { mutableStateOf(credential.title) }
    var username by rememberSaveable(credential.id) { mutableStateOf(credential.username) }
    var password by rememberSaveable(credential.id) { mutableStateOf(credential.password) }
    var url by rememberSaveable(credential.id) { mutableStateOf(credential.url) }
    // notesField is a TextFieldValue (not a bare String) so the live-preview markdown editor can
    // preserve cursor position across recompositions. We still pass the underlying text into the
    // save and diff paths via `notesField.text`; OCR fills overwrite the field by re-wrapping the
    // assigned string in a fresh TextFieldValue so the cursor parks at end-of-replacement.
    var notesField by rememberSaveable(credential.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(credential.notes))
    }
    // Triggers a transient "notes capped" Snackbar each time the live editor rejects an over-cap
    // value. Incremented by the cap-exceeded callback; the LaunchedEffect below shows the message.
    var notesCapTrigger by rememberSaveable(credential.id) { mutableStateOf(0) }
    // The editor's secret text-field reads/writes only the secret string. Algorithm /
    // digits / period / type stay at the entry's current values (or default TOTP for
    // new credentials). Power users use the dedicated 2FA list's manual-entry sheet
    // (added in C7) to set advanced params; that flow is the single supported path
    // for non-default OTP setup.
    var totpSecret by rememberSaveable(credential.id) {
        mutableStateOf(credential.otpParams?.secret ?: "")
    }
    var selectedTags by remember(credential.id) { mutableStateOf(credential.tags) }
    var customFields by remember(credential.id) { mutableStateOf(credential.customFields) }
    // Stable per-field ids so add/remove keeps each row's slot identity bound to *its*
    // logical field. With positional `key(index)`, removing a row shifted the rows below
    // up into the prior slot and they inherited that slot's local state (e.g. the reveal
    // toggle in CustomFieldRow).
    var customFieldIds by remember(credential.id) {
        mutableStateOf(List(credential.customFields.size) { it.toLong() })
    }
    var nextCustomFieldId by remember(credential.id) {
        mutableStateOf(credential.customFields.size.toLong())
    }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showTagPicker by rememberSaveable { mutableStateOf(false) }
    var showPasswordGenerator by rememberSaveable { mutableStateOf(false) }
    var showTotpScanner by rememberSaveable { mutableStateOf(false) }
    var showOcrScanner by rememberSaveable { mutableStateOf(false) }
    // Latched true once OCR successfully fills fields. Holds inactivity-suppression for
    // the rest of the editor session - the user just spent N seconds at the camera and
    // will spend more reading the auto-filled values; the idle timer can't tell that
    // apart from "user walked away". Released when the editor leaves composition (save,
    // back, or vault-lock-driven nav). Background-lock is unaffected: turning the screen
    // off mid-review still locks the vault.
    var ocrUsed by rememberSaveable { mutableStateOf(false) }
    if (ocrUsed) {
        DisposableEffect(Unit) {
            onBeginCameraSession()
            onDispose { onEndCameraSession() }
        }
    }

    val canAddField by remember { derivedStateOf { customFields.size < CustomField.MAX_FIELDS } }

    val hasUnsavedChanges by remember {
        derivedStateOf {
            title.trim() != credential.title ||
            username.trim() != credential.username ||
            password != credential.password ||
            url.trim() != credential.url ||
            notesField.text.trim() != credential.notes ||
            totpSecret.trim() != (credential.otpParams?.secret ?: "") ||
            selectedTags != credential.tags ||
            customFields != credential.customFields
        }
    }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    /**
     * Builds the saveable OTP params from the editor's text field. Empty input clears
     * the enrolment. Non-empty input preserves the credential's existing algorithm /
     * digits / period / type / counter when present (so editing a Steam or HOTP entry's
     * secret doesn't silently downgrade it to default TOTP), and falls back to default
     * TOTP for a fresh enrolment.
     */
    fun buildOtpParamsForSave(): OtpParams? {
        val trimmed = totpSecret.trim().takeIf { it.isNotEmpty() } ?: return null
        return credential.otpParams?.copy(secret = trimmed) ?: OtpParams.defaultTotp(trimmed)
    }

    // Snackbar host for transient editor messages (currently only the "notes capped at 64 KiB"
    // notice from the live-preview markdown editor). Lives at the edit-scaffold level so the
    // message floats above the IME and the camera/scanner sheets.
    val editorSnackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notesCapTrigger) {
        if (notesCapTrigger > 0) {
            editorSnackbarHostState.showSnackbar("Notes capped at 64 KiB. Split into multiple entries.")
        }
    }

    val showLiveEditor = isNotesMarkdownEnabled && credential.id !in plainSourceCredentialIds

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text(if (credential.id.isEmpty()) "New Credential" else "Edit Credential") },
                navigationIcon = {
                    IconButton(onClick = { if (hasUnsavedChanges) showDiscardDialog = true else onBack() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(credential.copy(
                                title = title.trim(),
                                username = username.trim(),
                                password = password,
                                url = url.trim(),
                                notes = notesField.text.trim(),
                                otpParams = buildOtpParamsForSave(),
                                tags = selectedTags,
                                customFields = customFields,
                            ))
                        },
                        // Save is enabled only when there is something to save. Gating
                        // on hasUnsavedChanges prevents the user from spending a tap
                        // (and a Room write + re-encrypt) on a no-op save. Title-blank
                        // guard stays since saving an empty title is still rejected by
                        // SaveCredentialUseCase - we surface that as a disabled button
                        // rather than letting it slip through to an error toast.
                        enabled = title.isNotBlank() && hasUnsavedChanges,
                    ) { Text("Save") }
                }
            )
        },
        snackbarHost = { SnackbarHost(editorSnackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LockAwareOutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title *") }, modifier = Modifier.fillMaxWidth(), isError = title.isBlank())

            // Type-aware body. NO_AUTH types (Secure Note, Credit Card, Other) drop
            // auth fields entirely and either promote notes to a primary content area
            // or render notes inline. BANK_ACCOUNT hides TOTP since 2FA-on-bank doesn't
            // fit this app's TOTP shape. Login-shaped types keep the full layout.
            when {
                credential.type in NO_AUTH_TYPES -> {
                    // Live-preview markdown editor when the global toggle is on and the user
                    // hasn't opted into plain-source mode for this credential; otherwise the
                    // existing plain field with monospace styling so raw markers stay legible.
                    if (showLiveEditor) {
                        MarkdownEditorField(
                            value = notesField,
                            onValueChange = { notesField = it },
                            label = {
                                Text(if (credential.type == CredentialType.SECURE_NOTE) "Content" else "Notes")
                            },
                            supportingText = { Text(MARKDOWN_SYNTAX_HINT) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = if (credential.type == CredentialType.SECURE_NOTE) 12 else 4,
                            maxLines = 30,
                            trailingIcon = {
                                IconButton(onClick = { showOcrScanner = true }) {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = "Scan from photo")
                                }
                            },
                            onSizeCapExceeded = { notesCapTrigger += 1 },
                        )
                    } else {
                        LockAwareOutlinedTextField(
                            value = notesField,
                            onValueChange = { notesField = it },
                            label = {
                                Text(if (credential.type == CredentialType.SECURE_NOTE) "Content" else "Notes")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = if (credential.type == CredentialType.SECURE_NOTE) 12 else 4,
                            maxLines = 30,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            trailingIcon = {
                                IconButton(onClick = { showOcrScanner = true }) {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = "Scan from photo")
                                }
                            },
                        )
                    }
                }
                else -> {
                    LockAwareOutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showOcrScanner = true }) {
                                Icon(Icons.Default.DocumentScanner, contentDescription = "Scan from photo")
                            }
                        },
                    )
                    LockAwareOutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { showPasswordGenerator = true }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = "Generate password")
                                }
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                }
                            }
                        }
                    )
                    LockAwareOutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
                    if (showLiveEditor) {
                        MarkdownEditorField(
                            value = notesField,
                            onValueChange = { notesField = it },
                            label = { Text("Notes") },
                            supportingText = { Text(MARKDOWN_SYNTAX_HINT) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            trailingIcon = {
                                IconButton(onClick = { showOcrScanner = true }) {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = "Scan from photo")
                                }
                            },
                            onSizeCapExceeded = { notesCapTrigger += 1 },
                        )
                    } else {
                        LockAwareOutlinedTextField(
                            value = notesField,
                            onValueChange = { notesField = it },
                            label = { Text("Notes") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            trailingIcon = {
                                IconButton(onClick = { showOcrScanner = true }) {
                                    Icon(Icons.Default.DocumentScanner, contentDescription = "Scan from photo")
                                }
                            },
                        )
                    }
                    if (credential.type != CredentialType.BANK_ACCOUNT) {
                        LockAwareOutlinedTextField(
                            value = totpSecret,
                            onValueChange = { totpSecret = it },
                            label = { Text("TOTP Secret (base32)") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showTotpScanner = true }) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR code")
                                }
                            },
                        )
                    }
                }
            }

            // Category (chip-based, non-editable)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selectedTags.forEach { tag ->
                        InputChip(
                            selected = true,
                            onClick = { selectedTags = selectedTags - tag },
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove category",
                                    modifier = Modifier.size(InputChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = { showTagPicker = true },
                        label = { Text("Add category") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }

            // Type-specific quick-add suggestions. Tapping a chip drops a pre-labeled
            // CustomField with a sensible sensitive default - no reserved storage, no
            // schema impact. Already-added labels are filtered out to avoid duplicates.
            val suggestions = fieldSuggestionsFor(credential.type)
            val activeSuggestions = suggestions.filter { suggestion ->
                customFields.none { it.key.equals(suggestion.label, ignoreCase = true) }
            }
            if (activeSuggestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Suggested fields",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        activeSuggestions.forEach { suggestion ->
                            AssistChip(
                                onClick = {
                                    if (canAddField) {
                                        customFields = customFields + CustomField(
                                            key = suggestion.label,
                                            value = "",
                                            isSensitive = suggestion.sensitive,
                                        )
                                    }
                                },
                                label = { Text(suggestion.label) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Text("Custom Fields (${customFields.size}/${CustomField.MAX_FIELDS})", style = MaterialTheme.typography.titleSmall)
            customFields.forEachIndexed { index, field ->
                key(customFieldIds[index]) {
                    CustomFieldRow(
                        field = field,
                        onFieldChanged = { updated -> customFields = customFields.toMutableList().also { it[index] = updated } },
                        onRemove = {
                            customFields = customFields.toMutableList().also { it.removeAt(index) }
                            customFieldIds = customFieldIds.toMutableList().also { it.removeAt(index) }
                        },
                    )
                }
            }
            if (canAddField) {
                OutlinedButton(
                    onClick = {
                        customFields = customFields + CustomField("", "", false)
                        customFieldIds = customFieldIds + nextCustomFieldId
                        nextCustomFieldId += 1
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Custom Field") }
            }
        }
    }

    if (showTagPicker) {
        TagPickerDialog(
            availableTags = availableTags,
            selectedTags = selectedTags,
            onToggleTag = { tag ->
                selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
            },
            onAddCustomTag = { newTag ->
                if (newTag !in selectedTags) selectedTags = selectedTags + newTag
                onAddTag(newTag)
            },
            onDismiss = { showTagPicker = false },
        )
    }

    if (showPasswordGenerator) {
        PasswordGeneratorSheet(
            onUsePassword = { generated -> password = generated },
            onDismiss = { showPasswordGenerator = false },
        )
    }

    if (showTotpScanner) {
        // Suppress inactivity auto-lock for the duration the sheet is in
        // composition. onDispose runs whether the sheet is dismissed by the
        // user, by a successful scan, by back-navigation, or by the parent
        // screen tearing down (e.g. config change without survival).
        DisposableEffect(Unit) {
            onBeginCameraSession()
            onDispose { onEndCameraSession() }
        }
        TotpQrScannerSheet(
            onSecretScanned = { secret -> totpSecret = secret },
            onDismiss = { showTotpScanner = false },
        )
    }

    if (showOcrScanner) {
        DisposableEffect(Unit) {
            onBeginCameraSession()
            onDispose { onEndCameraSession() }
        }
        OcrScannerSheet(
            targets = ocrTargetsFor(credential.type),
            onResult = { assignments ->
                // Latch the post-OCR review window - see comment at `ocrUsed` declaration.
                ocrUsed = true
                assignments.username?.let { username = it }
                assignments.password?.let { password = it }
                assignments.url?.let { url = it }
                assignments.notes?.let { notesField = TextFieldValue(it, selection = TextRange(it.length)) }
                if (assignments.customFields.isNotEmpty()) {
                    // Upsert: replace existing fields with matching key, append the rest.
                    // Sensitivity is taken from the OCR target table (e.g. CVV / API Token ->
                    // sensitive; Cardholder / Branch -> not), so the user doesn't have to toggle.
                    val updatedFields = customFields.toMutableList()
                    val updatedIds = customFieldIds.toMutableList()
                    for (cf in assignments.customFields) {
                        val existingIndex = updatedFields.indexOfFirst { it.key == cf.key }
                        if (existingIndex >= 0) {
                            updatedFields[existingIndex] = updatedFields[existingIndex].copy(
                                value = cf.value,
                                isSensitive = cf.sensitive,
                            )
                        } else {
                            updatedFields.add(
                                CustomField(key = cf.key, value = cf.value, isSensitive = cf.sensitive)
                            )
                            updatedIds.add(nextCustomFieldId)
                            nextCustomFieldId += 1
                        }
                    }
                    customFields = updatedFields
                    customFieldIds = updatedIds
                }
            },
            onDismiss = { showOcrScanner = false },
        )
    }

    if (showDiscardDialog) {
        LockAwareDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("You have unsaved changes. Save or discard them?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onSave(credential.copy(
                            title = title.trim(),
                            username = username.trim(),
                            password = password,
                            url = url.trim(),
                            notes = notesField.text.trim(),
                            otpParams = buildOtpParamsForSave(),
                            tags = selectedTags,
                            customFields = customFields,
                        ))
                    },
                    enabled = title.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("Discard") }
            },
        )
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TotpQrScannerSheet(
    onSecretScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    LockAwareModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Scan 2FA QR Code",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Point the camera at a 2FA QR code to fill in the secret automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(16.dp))
            if (hasPermission) {
                TotpCameraPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    onSecretDetected = { secret ->
                        onSecretScanned(secret)
                        onDismiss()
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "Camera permission required to scan QR codes",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Button(onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) }) {
                            Text("Grant Permission")
                        }
                    }
                }
            }
        }
    }
}

@ExperimentalGetImage
@Composable
private fun TotpCameraPreview(
    modifier: Modifier = Modifier,
    onSecretDetected: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val onDetectedState = rememberUpdatedState(onSecretDetected)
    val detected = remember { AtomicBoolean(false) }

    // Transient "not a 2FA QR" pill state, throttled so ML Kit's 30 fps re-detection
    // of the same wrong QR doesn't restart the dismiss timer in a tight loop.
    var showInvalidQr by remember { mutableStateOf(false) }
    val onInvalidQrDetected = rememberUpdatedState({ showInvalidQr = true })
    var lastInvalidAtMs by remember { mutableStateOf(0L) }
    LaunchedEffect(showInvalidQr) {
        if (showInvalidQr) {
            kotlinx.coroutines.delay(2_500)
            showInvalidQr = false
        }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            barcodeScanner.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !detected.get()) {
                                val input = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees,
                                )
                                barcodeScanner.process(input)
                                    .addOnSuccessListener { barcodes ->
                                        val raw = barcodes.firstOrNull()?.rawValue
                                            ?: return@addOnSuccessListener
                                        val parsed = OtpAuthUriParser.parse(raw)
                                        if (parsed == null) {
                                            val now = android.os.SystemClock.elapsedRealtime()
                                            if (now - lastInvalidAtMs >= 2_500L) {
                                                lastInvalidAtMs = now
                                                onInvalidQrDetected.value()
                                            }
                                            return@addOnSuccessListener
                                        }
                                        if (detected.compareAndSet(false, true)) {
                                            // The in-editor scanner only carries the secret string back
                                            // to the form field - algorithm/digits/period/counter come
                                            // through the dedicated 2FA list manual-entry sheet (C7) or
                                            // a fresh credential created via QrScannerScreen, both of
                                            // which preserve full params end-to-end.
                                            onDetectedState.value(parsed.params.secret)
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    runCatching {
                        val provider = cameraProviderFuture.get()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                    ),
            )
        }

        AnimatedVisibility(
            visible = showInvalidQr,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "Not a 2FA QR code - try a different one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TagsView(tags: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Category", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tags.forEach { tag ->
                SuggestionChip(onClick = {}, label = { Text(tag) })
            }
        }
    }
}

@Composable
private fun TagPickerDialog(
    availableTags: List<Tag>,
    selectedTags: List<String>,
    onToggleTag: (String) -> Unit,
    onAddCustomTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customTagText by remember { mutableStateOf("") }

    LockAwareDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category") },
        textBodyScrollable = false,
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                availableTags.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = tag.name in selectedTags,
                            onCheckedChange = { onToggleTag(tag.name) },
                        )
                        Text(
                            tag.name,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (availableTags.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LockAwareOutlinedTextField(
                        value = customTagText,
                        onValueChange = { customTagText = it },
                        label = { Text("New category") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            val trimmed = customTagText.trim()
                            if (trimmed.isNotEmpty()) {
                                onAddCustomTag(trimmed)
                                customTagText = ""
                            }
                        },
                        enabled = customTagText.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add custom category")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun CustomFieldRow(field: CustomField, onFieldChanged: (CustomField) -> Unit, onRemove: () -> Unit) {
    // Field content is read straight from the prop and propagated through the callback -
    // no internal mirror. Internal state used to drift when the parent removed an earlier
    // row: each surviving row's `remember`d text would carry over into the wrong slot
    // because Compose reused slots positionally. Reading the prop directly removes that
    // class of bug entirely.
    //
    // The reveal toggle is genuinely view-only; key it on isSensitive so flipping the
    // sensitivity switch resets visibility to the correct default for the new mode.
    var showValue by remember(field.isSensitive) { mutableStateOf(!field.isSensitive) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Custom Field",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove field",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            LockAwareOutlinedTextField(
                value = field.key,
                onValueChange = { onFieldChanged(field.copy(key = it)) },
                label = { Text("Label") },
                placeholder = { Text("e.g. API Key, PIN, Recovery Code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            LockAwareOutlinedTextField(
                value = field.value,
                onValueChange = { onFieldChanged(field.copy(value = it)) },
                label = { Text("Value") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (field.isSensitive && !showValue) PasswordVisualTransformation()
                                       else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.isSensitive) KeyboardType.Password else KeyboardType.Text,
                ),
                trailingIcon = if (field.isSensitive) {
                    {
                        IconButton(onClick = { showValue = !showValue }) {
                            Icon(
                                if (showValue) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showValue) "Hide value" else "Show value",
                            )
                        }
                    }
                } else null,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Sensitive", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Masks this value in view mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = field.isSensitive,
                    onCheckedChange = { checked ->
                        onFieldChanged(field.copy(isSensitive = checked))
                    },
                )
            }
        }
    }
}
