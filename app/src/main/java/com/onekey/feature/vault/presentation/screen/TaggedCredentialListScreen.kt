package com.onekey.feature.vault.presentation.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.onekey.core.presentation.util.oneKeyTopBarColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.presentation.lockaware.LockAwareDropdownMenu
import com.onekey.core.presentation.lockaware.LockAwareTextField
import com.onekey.feature.vault.presentation.viewmodel.CredentialListEvent
import com.onekey.feature.vault.presentation.viewmodel.CredentialListState
import com.onekey.feature.vault.presentation.viewmodel.TaggedCredentialListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaggedCredentialListScreen(
    onBack: () -> Unit,
    onCredentialClick: (String) -> Unit,
    onAddCredential: (type: CredentialType, initialTag: String) -> Unit,
    viewModel: TaggedCredentialListViewModel = hiltViewModel(),
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedAreAllFavourite by viewModel.selectedAreAllFavourite.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val binEnabled by viewModel.isRecycleBinEnabled.collectAsStateWithLifecycle()
    val letterIndex by viewModel.letterIndex.collectAsStateWithLifecycle()
    val hideTopBarOnScroll by viewModel.hideTopBarOnScroll.collectAsStateWithLifecycle()
    val isBypassed = listState is CredentialListState.Bypassed

    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // Bottom-sheet picker only fires for the meta-tags (TAG_ALL / TAG_FAVORITES) where
    // there's no implicit credential type. Default and custom tags route directly via
    // resolveAddTarget below - no sheet, no extra tap.
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Top bar collapses on scroll to give the list more room. canScroll is gated on
    // selection-mode so the action bar (Cancel / Delete) stays pinned while the user
    // is mid-action; we also snap the bar back to visible the moment selection starts
    // in case it had collapsed before.
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
        canScroll = { hideTopBarOnScroll && !isSelectionMode },
    )
    // Snap the bar back to fully visible when collapse is disabled, or when entering
    // selection mode - otherwise the user could land on a hidden bar with no way to act.
    LaunchedEffect(isSelectionMode, hideTopBarOnScroll) {
        if (isSelectionMode || !hideTopBarOnScroll) {
            topAppBarState.heightOffset = 0f
            topAppBarState.contentOffset = 0f
        }
    }

    LaunchedEffect(Unit) {
        viewModel.event.collectLatest { event ->
            when (event) {
                is CredentialListEvent.DeleteError ->
                    snackbarHostState.showSnackbar("Failed to delete ${event.count} item(s)")
                is CredentialListEvent.FavouriteUpdated -> {
                    val verb = if (event.markedAs) "Added" else "Removed"
                    val noun = if (event.count == 1) "credential" else "credentials"
                    val direction = if (event.markedAs) "to" else "from"
                    snackbarHostState.showSnackbar("$verb ${event.count} $noun $direction favourites")
                }
                is CredentialListEvent.FavouriteError ->
                    snackbarHostState.showSnackbar("Failed to update ${event.count} favourite(s)")
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { sortOrder }.drop(1).collect { lazyListState.scrollToItem(0) }
    }

    // Search lives in the TopAppBar (matches VaultScreen). rememberSaveable so a
    // rotation mid-search doesn't collapse it back into the list - the searchQuery
    // itself lives in the VM and survives independently.
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

    // Search-mode back only fires when selection isn't active - selection's
    // BackHandler above takes priority by virtue of mutually-exclusive `enabled`.
    BackHandler(enabled = isSearchActive && !isSelectionMode) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            delay(50) // allow the TextField to enter composition before requesting focus
            searchFocusRequester.requestFocus()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            when {
                isSelectionMode -> {
                    TopAppBar(
                        expandedHeight = 56.dp,
                        colors = oneKeyTopBarColors(),
                        title = { Text("${selectedIds.size} selected") },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear selection")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { viewModel.setFavouriteOnSelected(!selectedAreAllFavourite) },
                            ) {
                                Icon(
                                    imageVector = if (selectedAreAllFavourite)
                                        Icons.Default.Favorite
                                    else
                                        Icons.Default.FavoriteBorder,
                                    contentDescription = if (selectedAreAllFavourite)
                                        "Remove from favourites"
                                    else
                                        "Mark as favourite",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete selected",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
                isSearchActive -> {
                    // Search-mode TopAppBar (matches VaultScreen):
                    // - back arrow closes search and clears the query
                    // - LockAwareTextField as the title slot, transparent so it
                    //   reads as a search bar rather than a boxed input
                    // - clear-X action only when there's text to clear
                    TopAppBar(
                        expandedHeight = 56.dp,
                        colors = oneKeyTopBarColors(),
                        navigationIcon = {
                            IconButton(onClick = {
                                isSearchActive = false
                                viewModel.setSearchQuery("")
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close search",
                                )
                            }
                        },
                        title = {
                            LockAwareTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = {
                                    Text(
                                        "Search in ${viewModel.displayName}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                singleLine = true,
                                enabled = !isBypassed,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                            )
                        },
                        actions = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
                else -> {
                    TopAppBar(
                        expandedHeight = 56.dp,
                        colors = oneKeyTopBarColors(),
                        title = { Text(viewModel.displayName) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { isSearchActive = true },
                                enabled = !isBypassed,
                            ) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                                }
                                LockAwareDropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                ) {
                                    CredentialSortOrder.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                viewModel.setSortOrder(option)
                                                showSortMenu = false
                                            },
                                            leadingIcon = if (sortOrder == option) {
                                                { Icon(Icons.Default.Check, contentDescription = null) }
                                            } else null,
                                        )
                                    }
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Hidden during selection mode (FAB would sit over the action bar) and
            // during search (matches VaultScreen - keeps the search surface uncluttered).
            if (!isSelectionMode && !isSearchActive) {
                FloatingActionButton(
                    onClick = {
                        when (val raw = viewModel.rawTag) {
                            TAG_ALL, TAG_FAVORITES -> showAddSheet = true
                            else -> {
                                // Default tag whose name matches a CredentialType displayName
                                // (Login / Bank Account / ...) -> use that type. Custom tag with
                                // no matching display name -> fall back to LOGIN. Either way,
                                // pre-fill the new credential's tag with the current rawTag.
                                val type = CredentialType.entries.find { it.displayName == raw }
                                    ?: CredentialType.LOGIN
                                onAddCredential(type, raw)
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add credential")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Search input now lives in the TopAppBar (see the topBar block above).
            // The result content below is filtered by viewModel.searchQuery and
            // already handles the "no matches" / "empty tag" branches correctly.
            when (val s = listState) {
                CredentialListState.Locked,
                CredentialListState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                CredentialListState.Bypassed -> {
                    VaultTooLargeState()
                }
                is CredentialListState.Loaded -> {
                    if (s.credentials.isEmpty()) {
                        if (searchQuery.isNotBlank()) {
                            // Empty under an active search: distinct copy from the
                            // tag-is-genuinely-empty case so the user knows the
                            // search filtered the tag, not that the tag has nothing.
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "No matches for \"$searchQuery\"",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Try a different search term",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            val (title, subtitle) = emptyTagCopy(viewModel.rawTag, viewModel.displayName)
                            EmptyVaultState(
                                title = title,
                                subtitle = subtitle,
                                icon = tagIcon(viewModel.displayName),
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val showIndexer = sortOrder == CredentialSortOrder.ALPHABETICAL
                            LazyColumn(
                                state = lazyListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (showIndexer) Modifier.padding(end = 28.dp) else Modifier)
                                    .preferredFrameRate(FrameRateCategory.High),
                                // First item should sit flush under the TopAppBar - matches
                                // VaultScreen. Bottom padding clears the FAB.
                                contentPadding = PaddingValues(bottom = 88.dp),
                            ) {
                                items(
                                    items = s.credentials,
                                    key = { it.id },
                                ) { credential ->
                                    CredentialCard(
                                        credential = credential,
                                        isSelected = credential.id in selectedIds,
                                        onClick = {
                                            if (isSelectionMode) viewModel.toggleSelection(credential.id)
                                            else onCredentialClick(credential.id)
                                        },
                                        onLongClick = { viewModel.toggleSelection(credential.id) },
                                    )
                                }
                            }

                            if (showIndexer) {
                                AlphabetIndexer(
                                    letterIndex = letterIndex,
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    onLetterClick = { pos ->
                                        scope.launch { lazyListState.scrollToItem(pos) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        BulkDeleteDialog(
            count = selectedIds.size,
            binEnabled = binEnabled,
            onMoveToBin = {
                showDeleteDialog = false
                viewModel.deleteSelected()
            },
            onDeleteNow = {
                showDeleteDialog = false
                viewModel.deleteSelectedNow()
            },
            onCancel = { showDeleteDialog = false },
        )
    }

    if (showAddSheet) {
        AddCredentialBottomSheet(
            onTypePicked = { type ->
                showAddSheet = false
                // Match Vault home: picked type sets both the credential type AND its
                // initial tag (so e.g. picking LOGIN auto-tags as "Login"). OTHER skips
                // the auto-tag since there's no natural label for it.
                val initialTag = if (type == CredentialType.OTHER) "" else type.displayName
                onAddCredential(type, initialTag)
            },
            onDismiss = { showAddSheet = false },
        )
    }
}

@Composable
private fun AlphabetIndexer(
    letterIndex: Map<Char, Int>,
    modifier: Modifier = Modifier,
    onLetterClick: (Int) -> Unit,
) {
    val letters = remember(letterIndex) { letterIndex.keys.sorted() }
    var gestureActive by remember { mutableStateOf(false) }
    var displayLetter by remember { mutableStateOf(' ') }
    var displayIdx by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    val badgeAlpha by animateFloatAsState(
        targetValue = if (gestureActive) 1f else 0f,
        animationSpec = tween(durationMillis = if (gestureActive) 80 else 200),
        label = "badge_alpha",
    )
    val badgeScale by animateFloatAsState(
        targetValue = if (gestureActive) 1f else 0.8f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "badge_scale",
    )

    val vertPadding = 16.dp
    val badgeSize = 52.dp

    BoxWithConstraints(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight(),
    ) {
        val totalHeightDp = maxHeight
        val contentHeightDp = (totalHeightDp - vertPadding * 2).coerceAtLeast(1.dp)

        // Floating letter badge - rendered outside the 28dp strip via negative offset
        if (badgeAlpha > 0.01f && letters.isNotEmpty()) {
            val slotHeight = contentHeightDp / letters.size
            val safeIdx = displayIdx.coerceIn(0, letters.lastIndex)
            val badgeY = (vertPadding + slotHeight * safeIdx + slotHeight / 2 - badgeSize / 2)
                .coerceIn(0.dp, totalHeightDp - badgeSize)
            Box(
                modifier = Modifier
                    .offset(x = -(badgeSize + 12.dp), y = badgeY)
                    .size(badgeSize)
                    .scale(badgeScale)
                    .alpha(badgeAlpha)
                    .shadow(8.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayLetter.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        // Indexer strip
        Column(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .padding(vertical = vertPadding)
                .pointerInput(letters, letterIndex) {
                    awaitEachGesture {
                        var lastIdx = -1
                        fun notifyAt(y: Float) {
                            if (letters.isEmpty() || size.height == 0) return
                            val idx = (y / size.height * letters.size)
                                .toInt().coerceIn(0, letters.lastIndex)
                            displayIdx = idx
                            displayLetter = letters[idx]
                            gestureActive = true
                            if (idx != lastIdx) {
                                lastIdx = idx
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                letterIndex[letters[idx]]?.let { onLetterClick(it) }
                            }
                        }
                        // awaitEachGesture guarantees all pointers are up before restarting,
                        // so the first awaitPointerEvent() is always the initial press.
                        val downEvent = awaitPointerEvent()
                        val down = downEvent.changes.firstOrNull() ?: return@awaitEachGesture
                        down.consume()
                        notifyAt(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            notifyAt(change.position.y)
                        }
                        gestureActive = false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEachIndexed { i, letter ->
                key(letter) {
                    Text(
                        text = letter.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (gestureActive && i == displayIdx)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .wrapContentHeight()
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

private fun emptyTagCopy(rawTag: String, displayName: String): Pair<String, String> = when (rawTag) {
    TAG_ALL -> "Your vault is empty" to
        "Add your first credential from the home screen - tap the + button to get started."
    TAG_FAVORITES -> "No favourites yet" to
        "Tap the heart on any credential to keep it within easy reach here."
    else -> "No credentials in $displayName" to
        "When you add a credential to this category, it'll show up here."
}
