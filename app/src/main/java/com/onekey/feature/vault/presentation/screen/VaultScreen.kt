package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.onekey.core.domain.model.Credential
import com.onekey.core.domain.model.Tag
import com.onekey.feature.vault.presentation.viewmodel.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onCredentialClick: (String) -> Unit,
    onAddClick: () -> Unit,
    onTwoFaClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTagClick: (String) -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val credentials: LazyPagingItems<Credential> = viewModel.credentials.collectAsLazyPagingItems()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

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
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add credential")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChanged,
            )
            TagFilterRow(
                tags = uiState.tags,
                onTagClick = onTagClick,
            )
            CredentialList(
                items = credentials,
                favorites = favorites,
                onItemClick = onCredentialClick,
                onTagClick = onTagClick,
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search credentials…") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, null)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
    )
}

@Composable
private fun TagFilterRow(
    tags: List<Tag>,
    onTagClick: (String) -> Unit,
) {
    if (tags.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = tags, key = { it.name }) { tag ->
            AssistChip(
                onClick = { onTagClick(tag.name) },
                label = { Text(tag.name) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CredentialList(
    items: LazyPagingItems<Credential>,
    favorites: List<Credential>,
    onItemClick: (String) -> Unit,
    onTagClick: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (favorites.isNotEmpty()) {
            item {
                Text(
                    "Favorites",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }
            items(items = favorites, key = { "fav_${it.id}" }) { credential ->
                CredentialCard(
                    credential = credential,
                    onClick = { onItemClick(credential.id) },
                    onTagClick = onTagClick,
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "All Credentials",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
        ) { index ->
            val credential = items[index]
            if (credential != null) {
                CredentialCard(
                    credential = credential,
                    onClick = { onItemClick(credential.id) },
                    onTagClick = onTagClick,
                )
            }
        }
    }
}

@Composable
internal fun CredentialCard(
    credential: Credential,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(credential.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (credential.isFavorite) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (credential.username.isNotEmpty()) {
                    Text(
                        credential.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (credential.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        credential.tags.take(3).forEach { tag ->
                            AssistChip(
                                onClick = { onTagClick(tag) },
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
