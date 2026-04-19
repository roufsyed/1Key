package com.onekey.feature.vault.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.vault.presentation.viewmodel.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsBrowseScreen(
    onTagClick: (String) -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val tagCounts by viewModel.tagCounts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tags") })
        }
    ) { padding ->
        if (tagCounts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No tags yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(tagCounts, key = { it.tag.name }) { tagWithCount ->
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
    }
}
