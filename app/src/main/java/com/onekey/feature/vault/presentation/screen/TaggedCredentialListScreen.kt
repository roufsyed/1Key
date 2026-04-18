package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.onekey.feature.vault.presentation.viewmodel.TaggedCredentialListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaggedCredentialListScreen(
    onBack: () -> Unit,
    onCredentialClick: (String) -> Unit,
    viewModel: TaggedCredentialListViewModel = hiltViewModel(),
) {
    val credentials = viewModel.credentials.collectAsLazyPagingItems()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                count = credentials.itemCount,
                key = credentials.itemKey { it.id },
            ) { index ->
                val credential = credentials[index]
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
