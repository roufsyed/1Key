package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.onekey.feature.vault.presentation.viewmodel.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavouritesScreen(
    onCredentialClick: (String) -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val favorites = viewModel.favorites.collectAsLazyPagingItems()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Favourites") })
        }
    ) { padding ->
        val isEmpty = favorites.loadState.refresh is LoadState.NotLoading &&
            favorites.itemCount == 0

        if (isEmpty) {
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
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = favorites.itemCount,
                    key = favorites.itemKey { it.id },
                ) { index ->
                    val credential = favorites[index]
                    if (credential != null) {
                        CredentialCard(
                            credential = credential,
                            onClick = { onCredentialClick(credential.id) },
                            onTagClick = {},
                        )
                    }
                }
            }
        }
    }
}
