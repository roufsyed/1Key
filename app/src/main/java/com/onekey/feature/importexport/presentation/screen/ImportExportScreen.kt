package com.onekey.feature.importexport.presentation.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.domain.usecase.ExportFormat
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportUiState
import com.onekey.feature.importexport.presentation.viewmodel.ImportExportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportScreen(
    onBack: () -> Unit,
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedFormat by remember { mutableStateOf(ExportFormat.JSON) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        viewModel.notifyPickerDone()
        uri?.let { viewModel.export(it, selectedFormat, context) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        viewModel.notifyPickerDone()
        uri?.let { viewModel.import(it, selectedFormat, context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import / Export") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Format", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format },
                        label = { Text(format.name) },
                    )
                }
            }

            Divider()
            Text("Export", style = MaterialTheme.typography.titleMedium)
            Text("Exports all credentials as ${selectedFormat.name}. The exported file is NOT encrypted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    viewModel.notifyPickerLaunched()
                    exportLauncher.launch("1key_backup.${selectedFormat.name.lowercase()}")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImportExportUiState.Loading,
            ) { Text("Export Vault") }

            Divider()
            Text("Import", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = {
                    viewModel.notifyPickerLaunched()
                    importLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImportExportUiState.Loading,
            ) { Text("Import Credentials") }

            when (val s = state) {
                is ImportExportUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is ImportExportUiState.Success -> Text(s.message, color = MaterialTheme.colorScheme.primary)
                is ImportExportUiState.ImportSuccess -> {
                    val r = s.result
                    val skippedCount = r.skipped.size
                    val failedCount = r.failed.size
                    Text(
                        buildString {
                            append("Imported ${r.imported} ${if (r.imported == 1) "credential" else "credentials"}")
                            if (skippedCount > 0) append(" · $skippedCount skipped")
                            if (failedCount > 0) append(" · $failedCount failed")
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is ImportExportUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }
    }
}
