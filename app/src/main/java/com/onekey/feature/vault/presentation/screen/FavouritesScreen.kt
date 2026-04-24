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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.CredentialSortOrder
import com.onekey.feature.vault.presentation.viewmodel.CredentialListEvent
import com.onekey.feature.vault.presentation.viewmodel.FavouritesViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavouritesScreen(
    onCredentialClick: (String) -> Unit,
    viewModel: FavouritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.credentials.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val letterIndex by viewModel.letterIndex.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.event.collectLatest { event ->
            when (event) {
                is CredentialListEvent.DeleteError ->
                    snackbarHostState.showSnackbar("Failed to delete ${event.count} item(s)")
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { sortOrder }.drop(1).collect { listState.scrollToItem(0) }
    }

    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Favourites") },
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
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
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val favList = favorites
        if (favList == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        if (favList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No favourites yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open a credential and tap the star to add it here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val showIndexer = sortOrder == CredentialSortOrder.ALPHABETICAL
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (showIndexer) Modifier.padding(end = 28.dp) else Modifier),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = favList,
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
                            onTagClick = {},
                        )
                    }
                }

                if (showIndexer) {
                    AlphabetIndexer(
                        letterIndex = letterIndex,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onLetterClick = { pos ->
                            scope.launch { listState.scrollToItem(pos) }
                        },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${selectedIds.size} item${if (selectedIds.size == 1) "" else "s"}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteSelected()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
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

        // Floating letter badge — rendered outside the 28dp strip via negative offset
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
