package com.onekey.feature.twofa.presentation.screen

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.twofa.presentation.viewmodel.TotpEntry
import com.onekey.feature.twofa.presentation.viewmodel.TwoFaListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoFaListScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    onScanQr: () -> Unit,
    viewModel: TwoFaListViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<TotpEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("2FA Codes") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null)
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onScanQr,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        "Tap the camera button to scan a QR code",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { it.credential.id }) { entry ->
                    TotpEntryCard(
                        entry = entry,
                        onLongClick = { pendingDelete = entry },
                        onCopyCode = { code ->
                            val cm = context.getSystemService(
                                android.content.ClipboardManager::class.java
                            )
                            val clip = ClipData.newPlainText("2FA Code", code).apply {
                                // Mark as sensitive on API 33+: suppresses paste preview toast
                                // and signals the OS not to persist the clip.
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    description.extras = PersistableBundle().apply {
                                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                    }
                                }
                            }
                            cm?.setPrimaryClip(clip)

                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Code copied — clears in 30s",
                                    duration = SnackbarDuration.Short,
                                )
                            }

                            // Auto-clear clipboard after one TOTP window to minimise exposure.
                            scope.launch {
                                delay(30_000L)
                                val cm2 = context.getSystemService(
                                    android.content.ClipboardManager::class.java
                                )
                                val current = cm2?.primaryClip?.getItemAt(0)?.text?.toString()
                                if (current == code) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        cm2.clearPrimaryClip()
                                    } else {
                                        cm2?.setPrimaryClip(ClipData.newPlainText("", ""))
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove 2FA account?") },
            text = { Text("\"${entry.credential.title}\" will be removed. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteEntry(entry.credential.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TotpEntryCard(
    entry: TotpEntry,
    onLongClick: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onCopyCode(entry.code) }, onLongClick = onLongClick),
    ) {
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
