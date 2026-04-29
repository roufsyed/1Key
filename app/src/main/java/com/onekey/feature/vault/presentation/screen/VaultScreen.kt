package com.onekey.feature.vault.presentation.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.onekey.core.domain.model.CredentialType
import com.onekey.core.domain.model.TagWithCount
import com.onekey.feature.vault.presentation.viewmodel.VaultViewModel
import kotlinx.coroutines.delay

// Sentinel used to signal "show all credentials" to TaggedCredentialListScreen.
const val TAG_ALL = "_all"
const val TAG_FAVORITES = "_favorites"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onAddClick: (CredentialType) -> Unit,
    onTagClick: (String) -> Unit,
    onCredentialClick: (String) -> Unit,
    onRecycleBinClick: () -> Unit,
    onManageTagsClick: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val tagCounts by viewModel.tagCounts.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val recycleBinCount by viewModel.recycleBinCount.collectAsStateWithLifecycle()
    val isVaultFooterVisible by viewModel.isVaultFooterVisible.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val hideTopBarOnScroll by viewModel.hideTopBarOnScroll.collectAsStateWithLifecycle()
    // Always collected unconditionally to satisfy Compose snapshot rules.
    val searchResults = viewModel.searchResults.collectAsLazyPagingItems()

    var isSearchActive by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }

    val topAppBarState = rememberTopAppBarState()
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

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            delay(50) // allow the TextField to enter composition before requesting focus
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = {
                                Text(
                                    "Search credentials…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
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
            } else {
                TopAppBar(
                    title = { Text("1Key") },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!isSearchActive) {
                FloatingActionButton(
                    onClick = { showBottomSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add credential")
                }
            }
        },
    ) { padding ->
        if (isSearchActive) {
            SearchResultsContent(
                query = searchQuery,
                isLoading = searchResults.loadState.refresh is LoadState.Loading,
                isEmpty = searchResults.loadState.refresh is LoadState.NotLoading
                    && searchResults.itemCount == 0
                    && searchQuery.isNotEmpty(),
                padding = padding,
                results = {
                    items(
                        count = searchResults.itemCount,
                        key = searchResults.itemKey { it.id },
                    ) { index ->
                        val credential = searchResults[index]
                        if (credential != null) {
                            CredentialCard(
                                credential = credential,
                                onClick = { onCredentialClick(credential.id) },
                                onTagClick = {},
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 88.dp), // clear FAB
            ) {
                // ── All items ──────────────────────────────────────────────────────
                item(key = "all") {
                    TagRow(
                        icon = Icons.Default.GridView,
                        name = "All Items",
                        count = totalCount,
                        onClick = { onTagClick(TAG_ALL) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

                // ── Favorites ─────────────────────────────────────────────────────
                item(key = "favorites") {
                    TagRow(
                        icon = Icons.Default.FavoriteBorder,
                        name = "Favorites",
                        count = favoriteCount,
                        onClick = { onTagClick(TAG_FAVORITES) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

                // ── Section label ─────────────────────────────────────────────────
                item(key = "section_header") {
                    Text(
                        "Categories",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                // ── Tag rows ──────────────────────────────────────────────────────
                items(items = tagCounts, key = { it.tag.name }) { tagWithCount ->
                    TagRow(
                        icon = tagIcon(tagWithCount.tag.name),
                        name = tagWithCount.tag.name,
                        count = tagWithCount.count,
                        onClick = { onTagClick(tagWithCount.tag.name) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

                // ── Recycle bin ───────────────────────────────────────────────────
                item(key = "recycle_bin") {
                    TagRow(
                        icon = Icons.Default.Delete,
                        name = "Recycle Bin",
                        count = recycleBinCount,
                        onClick = onRecycleBinClick,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

                // ── Manage tags ───────────────────────────────────────────────────
                item(key = "manage_tags") {
                    TagRow(
                        icon = Icons.Default.Tune,
                        name = "Manage Tags",
                        count = tagCounts.size,
                        onClick = onManageTagsClick,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

                if (isVaultFooterVisible) {
                    item(key = "privacy_footer") {
                        Text(
                            "Your vault is encrypted and stored only on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
        ) {
            Text(
                "What are you saving?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                NEW_CREDENTIAL_TYPE_ORDER.forEach { type ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                tagIcon(type.displayName),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(type.displayName) },
                        modifier = Modifier.clickable {
                            showBottomSheet = false
                            onAddClick(type)
                        },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.Label,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = {
                        Text(
                            "Other",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable {
                        showBottomSheet = false
                        onAddClick(CredentialType.OTHER)
                    },
                )
            }
        }
    }
}

// Display order for the "add credential" sheet. OTHER is rendered separately as a fallback.
private val NEW_CREDENTIAL_TYPE_ORDER = listOf(
    CredentialType.LOGIN,
    CredentialType.SECURE_NOTE,
    CredentialType.CREDIT_CARD,
    CredentialType.PASSWORD,
    CredentialType.BANK_ACCOUNT,
    CredentialType.DATABASE,
    CredentialType.EMAIL,
    CredentialType.SERVER,
)

@Composable
private fun SearchResultsContent(
    query: String,
    isLoading: Boolean,
    isEmpty: Boolean,
    padding: PaddingValues,
    results: LazyListScope.() -> Unit,
) {
    when {
        query.isBlank() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Type to search credentials…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        isEmpty -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No results for \u201c$query\u201d",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Try a different search term",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
                content = results,
            )
        }
    }
}

@Composable
internal fun TagRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    count: Int,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(name, fontWeight = FontWeight.Medium)
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

internal fun tagIcon(name: String) = when (name) {
    "Login"        -> Icons.Default.Lock
    "Secure Note"  -> Icons.Default.Description
    "Credit Card"  -> Icons.Default.CreditCard
    "Password"     -> Icons.Default.Key
    "Bank Account" -> Icons.Default.AccountBalance
    "Database"     -> Icons.Default.Storage
    "Email Account"-> Icons.Default.Email
    "Server"       -> Icons.Default.Computer
    else           -> Icons.Default.Label
}
