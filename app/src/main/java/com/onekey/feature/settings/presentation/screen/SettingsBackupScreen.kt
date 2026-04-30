package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    onBack: () -> Unit,
    onBackup: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Import") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Backup & Import")
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("Backup & Import") },
                    supportingContent = { Text("Export your vault or import credentials from another app") },
                    leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable(onClick = onBackup),
                )
            }
        }
    }
}
