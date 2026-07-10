package com.roufsyed.onekey.feature.settings.presentation.screen

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.feature.settings.presentation.viewmodel.SyncSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSyncScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val isEnabled by viewModel.isSyncEnabled.collectAsStateWithLifecycle()
    val locationUri by viewModel.syncLocationUri.collectAsStateWithLifecycle()
    val notificationEnabled by viewModel.isCompletionNotificationEnabled.collectAsStateWithLifecycle()
    val lastSuccessAt by viewModel.lastSuccessAt.collectAsStateWithLifecycle()
    val showConsent by viewModel.showConsentDialog.collectAsStateWithLifecycle()
    val lastFailureText = viewModel.lastFailureText()

    // SAF folder pickers. Two launchers because the result handlers differ:
    // first-time pick flips the toggle ON; "change location" only updates URI.
    val firstPickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onLocationPicked(uri) }

    val changePickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onChangeLocationPicked(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ToggleRow(
                        title = "Sync",
                        subtitle = "Backs up your vault each time you unlock with your master password.",
                        checked = isEnabled,
                        onCheckedChange = { wantOn ->
                            if (wantOn) {
                                viewModel.onTurnOnRequested()
                            } else {
                                viewModel.turnOffSync()
                            }
                        },
                    )

                    // Sub-options are always visible but disabled (dimmed and
                    // non-interactive) when the master Sync toggle is off. Hiding
                    // them was disorienting; users couldn't see the shape of what
                    // they were about to enable.
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LastSyncedRow(
                        lastSuccessAt = lastSuccessAt,
                        failureText = lastFailureText,
                        enabled = isEnabled,
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    LocationRow(
                        locationName = viewModel.displayLocation(),
                        onChange = { changePickLauncher.launch(null) },
                        enabled = isEnabled,
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow(
                        title = "Completion notification",
                        subtitle = "Show a quiet notification after each successful backup.",
                        checked = notificationEnabled,
                        onCheckedChange = viewModel::setCompletionNotificationEnabled,
                        enabled = isEnabled,
                    )
                }
            }

            Text(
                text = "Sync is local-only. The encrypted backup file is written to a folder you choose; nothing is uploaded by 1Key. Master-password loss = vault loss, even with a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showConsent) {
        SyncConsentDialog(
            onContinue = {
                viewModel.onConsentDismissed()
                firstPickLauncher.launch(null)
            },
            onDismiss = { viewModel.onConsentDismissed() },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun LastSyncedRow(lastSuccessAt: Long, failureText: String?, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = when {
            failureText != null -> Icons.Filled.ErrorOutline to Color(0xFFC77700)
            lastSuccessAt > 0L -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
            else -> Icons.Filled.Sync to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Last synced", style = MaterialTheme.typography.titleSmall)
            val subtitle = failureText
                ?: if (lastSuccessAt > 0L) {
                    DateUtils.getRelativeTimeSpanString(
                        lastSuccessAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                } else {
                    "Never"
                }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocationRow(locationName: String?, onChange: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onChange) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Sync location", style = MaterialTheme.typography.titleSmall)
            Text(
                text = locationName ?: "Tap to choose",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onChange, enabled = enabled) { Text("Change") }
    }
}

@Composable
private fun SyncConsentDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    LockAwareDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on Sync?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "When this is on, every time you unlock 1Key by typing your master " +
                        "password, the app will quietly save an encrypted backup of your vault " +
                        "to a location you choose. You will see a small \"Syncing...\" bar at " +
                        "the top, then a green \"Synced\" tick.",
                )
                Text(
                    "What stays the same: your master password is never stored, cached, or " +
                        "copied anywhere. The app uses it only for the brief moment you type " +
                        "it to unlock, then forgets it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Why only when you type your master password? Encrypting a backup needs " +
                        "your password. Biometric and PIN unlock skip that step, so on those " +
                        "unlocks we simply do not back up - to keep the promise that your " +
                        "master password lives only in your head.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Where the file goes: a folder you pick on the next screen. A local " +
                        "folder, a USB drive, or a cloud-synced folder you control. The file " +
                        "is encrypted with your master password before it leaves the app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Things to know: each backup overwrites the previous one at the same " +
                        "filename. If you forget your master password, even recent backups " +
                        "are unrecoverable. That is intentional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
