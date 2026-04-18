package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.model.TagWithCount
import com.onekey.feature.vault.presentation.viewmodel.VaultViewModel

// Sentinel used to signal "show all credentials" to TaggedCredentialListScreen.
const val TAG_ALL = "_all"
const val TAG_FAVORITES = "_favorites"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onAddClick: (String) -> Unit,
    onTwoFaClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTagClick: (String) -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val tagCounts by viewModel.tagCounts.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("1Key") },
                actions = {
                    IconButton(onClick = onTwoFaClick) {
                        Icon(Icons.Default.Key, contentDescription = "2FA Codes")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showBottomSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add credential")
            }
        }
    ) { padding ->
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
        }
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
        ) {
            Text(
                "Choose a category",
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
                tagCounts.forEach { tagWithCount ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                tagIcon(tagWithCount.tag.name),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(tagWithCount.tag.name) },
                        modifier = Modifier.clickable {
                            showBottomSheet = false
                            onAddClick(tagWithCount.tag.name)
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
                            "No Category",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable {
                        showBottomSheet = false
                        onAddClick("")
                    },
                )
            }
        }
    }
}

@Composable
private fun TagRow(
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

private fun tagIcon(name: String) = when (name) {
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
