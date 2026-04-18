package com.onekey.feature.twofa.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.twofa.presentation.viewmodel.TotpEntry
import com.onekey.feature.twofa.presentation.viewmodel.TwoFaListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFaListScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    viewModel: TwoFaListViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("2FA Codes") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                    }
                },
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No 2FA accounts", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add a TOTP secret to a credential to see codes here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.credential.id }) { entry ->
                    TotpEntryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun TotpEntryCard(entry: TotpEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(entry.credential.title, style = MaterialTheme.typography.titleMedium)
            if (entry.credential.username.isNotEmpty()) {
                Text(
                    entry.credential.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 32.sp,
                        letterSpacing = 4.sp,
                    ),
                    color = if (entry.remainingSeconds <= 5) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = if (entry.remainingSeconds <= 5) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                    )
                    Text("${entry.remainingSeconds}s", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
