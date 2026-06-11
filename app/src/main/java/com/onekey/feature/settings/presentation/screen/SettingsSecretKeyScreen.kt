package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.core.presentation.util.oneKeyTopBarColors
import com.onekey.feature.settings.presentation.dialog.SecretKeyDisableConfirmDialog
import com.onekey.feature.settings.presentation.dialog.SecretKeyEnableConfirmDialog
import com.onekey.feature.settings.presentation.dialog.SecretKeyRotateConfirmDialog
import com.onekey.feature.settings.presentation.viewmodel.SecretKeySettingsEvent
import com.onekey.feature.settings.presentation.viewmodel.SecretKeySettingsViewModel
import com.onekey.feature.settings.presentation.viewmodel.SecretKeyStorageTier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings sub-route for Secret Key. Renders one of three states:
 *
 *  - State A (feature off / never enabled): primary CTA opens
 *    [SecretKeyEnableExplainerScreen] and the eventual master-password
 *    reauth.
 *  - State B (feature on but kit not yet saved after rotation): a
 *    persistent banner nudges the user to save a fresh kit. The rest of
 *    the state-C surface is visible.
 *  - State C (feature on, kit saved): rotate, disable, redownload-kit, and
 *    a storage-backing tile.
 *
 * The screen pulls every value from [SecretKeySettingsViewModel] - no
 * direct reads of the wrapper or the holder. That centralises the
 * "is SK live on this device" decision in the VM and keeps the screen a
 * pure presentation surface.
 *
 * Why a dedicated screen (not a section on SettingsSecurityScreen):
 *  - The SK lifecycle (enable explainer -> kit save -> rotate dialog -> kit
 *    save prompt again) is multi-page. Folding it into the Security screen
 *    would force every transition through a single screen's state machine,
 *    which the existing Security screen is not equipped to handle.
 *  - The post-rotate banner is persistent (until the user re-saves) and
 *    is cleaner as a dedicated screen-level surface than a row in a long
 *    Security list.
 *
 * The reauth dialog is the same [SecretKeyRotateConfirmDialog] /
 * [SecretKeyDisableConfirmDialog] surface used elsewhere. The post-success
 * path navigates to [EmergencyKitSavePromptScreen] (after enable or rotate)
 * or returns the user to this same screen (after disable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSecretKeyScreen(
    onBack: () -> Unit,
    onNavigateToEnableExplainer: () -> Unit,
    onNavigateToSaveKitPrompt: () -> Unit,
    vm: SecretKeySettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isBusy by vm.isBusy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showEnableDialog by remember { mutableStateOf(false) }
    var showRotateDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    // Wrong-password attempts remaining for the active dialog. null while
    // the user has not yet submitted; non-null after the VM emits a
    // WrongPassword event - the dialog re-enables in error styling.
    var enableAttemptsRemaining by remember { mutableStateOf<Int?>(null) }
    var rotateAttemptsRemaining by remember { mutableStateOf<Int?>(null) }
    var disableAttemptsRemaining by remember { mutableStateOf<Int?>(null) }
    var isVerifyingEnable by remember { mutableStateOf(false) }
    var isVerifyingRotate by remember { mutableStateOf(false) }
    var isVerifyingDisable by remember { mutableStateOf(false) }

    // The pre-enable explainer is a separate route; on its Continue tap it
    // sets vm.enableRequested = true and pops back here. We observe that
    // flag and surface the master-password reauth dialog. Clearing the
    // flag once we've shown the dialog prevents a stale signal from
    // re-firing the dialog on a recomposition.
    val enableRequested by vm.enableRequested.collectAsStateWithLifecycle()
    LaunchedEffect(enableRequested) {
        if (enableRequested) {
            enableAttemptsRemaining = null
            showEnableDialog = true
            vm.clearEnableRequested()
        }
    }

    LaunchedEffect(Unit) {
        vm.event.collect { event ->
            when (event) {
                SecretKeySettingsEvent.EnableSucceeded -> {
                    showEnableDialog = false
                    showRotateDialog = false
                    showDisableDialog = false
                    enableAttemptsRemaining = null
                    rotateAttemptsRemaining = null
                    disableAttemptsRemaining = null
                    isVerifyingEnable = false
                    isVerifyingRotate = false
                    isVerifyingDisable = false
                    onNavigateToSaveKitPrompt()
                }
                SecretKeySettingsEvent.RotateSucceeded -> {
                    showRotateDialog = false
                    rotateAttemptsRemaining = null
                    isVerifyingRotate = false
                    onNavigateToSaveKitPrompt()
                }
                SecretKeySettingsEvent.DisableSucceeded -> {
                    showDisableDialog = false
                    disableAttemptsRemaining = null
                    isVerifyingDisable = false
                    snackbarHostState.showSnackbar("Secret Key disabled on this device")
                }
                is SecretKeySettingsEvent.WrongPassword -> {
                    // The dialog stays open and re-enables. The user can
                    // retype without retriggering the parent action.
                    if (showEnableDialog) {
                        enableAttemptsRemaining = event.attemptsRemaining
                        isVerifyingEnable = false
                    }
                    if (showRotateDialog) {
                        rotateAttemptsRemaining = event.attemptsRemaining
                        isVerifyingRotate = false
                    }
                    if (showDisableDialog) {
                        disableAttemptsRemaining = event.attemptsRemaining
                        isVerifyingDisable = false
                    }
                }
                SecretKeySettingsEvent.VaultLocked -> {
                    // Lock screen takes over via VaultKeyHolder.lock(); the
                    // navigation is owned by OneKeyNavGraph's isUnlocked
                    // LaunchedEffect. We just clear local dialog state.
                    showEnableDialog = false
                    showRotateDialog = false
                    showDisableDialog = false
                    enableAttemptsRemaining = null
                    rotateAttemptsRemaining = null
                    disableAttemptsRemaining = null
                    isVerifyingEnable = false
                    isVerifyingRotate = false
                    isVerifyingDisable = false
                }
                is SecretKeySettingsEvent.Error -> {
                    isVerifyingEnable = false
                    isVerifyingRotate = false
                    isVerifyingDisable = false
                    snackbarHostState.showSnackbar(event.message)
                }
                SecretKeySettingsEvent.PrintNotYetSupported -> {
                    snackbarHostState.showSnackbar(
                        "Print support is coming soon. Save the PDF for now."
                    )
                }
                SecretKeySettingsEvent.KitSaved,
                is SecretKeySettingsEvent.KitSaveFailed -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Secret Key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // The pre-redesign screen had a standalone "Two-factor key
            // derivation" intro card AND a separate KitSaveReminderBanner
            // here. Both were redundant with the unified status card now
            // rendered inside EnabledStateContent / DisabledStateContent
            // - the card's title text + colour conveys state and the
            // body copy carries the same explanatory message in fewer
            // lines. The corruption banner stays because it represents
            // a recoverable failure mode, not a status.
            when {
                state.corruptionDetected -> {
                    // Storage corruption: SP flag says enabled but wrapper
                    // cannot decode the blob. Stay on the same screen but
                    // surface a high-prominence error tile.
                    CorruptionBanner()
                }
                else -> Unit
            }

            if (state.isPresent) {
                EnabledStateContent(
                    generationDateMs = state.generationDateMs,
                    lastKitDownloadAtMs = state.lastKitDownloadAtMs,
                    storageBacking = state.storageBacking,
                    isBusy = isBusy,
                    onRotate = {
                        rotateAttemptsRemaining = null
                        showRotateDialog = true
                    },
                    onDisable = {
                        disableAttemptsRemaining = null
                        showDisableDialog = true
                    },
                    onRedownloadKit = onNavigateToSaveKitPrompt,
                )
            } else {
                DisabledStateContent(
                    optedOut = state.optedOut,
                    isBusy = isBusy,
                    onEnable = onNavigateToEnableExplainer,
                )
            }
        }
    }

    if (showEnableDialog) {
        SecretKeyEnableConfirmDialog(
            onConfirm = { pwd ->
                isVerifyingEnable = true
                vm.enable(pwd)
            },
            onDismiss = {
                if (!isVerifyingEnable) {
                    showEnableDialog = false
                    enableAttemptsRemaining = null
                }
            },
            isVerifying = isVerifyingEnable,
            errorAttemptsRemaining = enableAttemptsRemaining,
        )
    }

    if (showRotateDialog) {
        SecretKeyRotateConfirmDialog(
            onConfirm = { pwd ->
                isVerifyingRotate = true
                vm.rotate(pwd)
            },
            onDismiss = {
                if (!isVerifyingRotate) {
                    showRotateDialog = false
                    rotateAttemptsRemaining = null
                }
            },
            isVerifying = isVerifyingRotate,
            errorAttemptsRemaining = rotateAttemptsRemaining,
        )
    }

    if (showDisableDialog) {
        SecretKeyDisableConfirmDialog(
            onConfirm = { pwd ->
                isVerifyingDisable = true
                vm.disable(pwd)
            },
            onDismiss = {
                if (!isVerifyingDisable) {
                    showDisableDialog = false
                    disableAttemptsRemaining = null
                }
            },
            isVerifying = isVerifyingDisable,
            errorAttemptsRemaining = disableAttemptsRemaining,
        )
    }
}

@Composable
private fun CorruptionBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Secret Key storage corrupted",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Your device says Secret Key is enabled but the wrapped value cannot " +
                    "be decrypted. The Keystore alias may have been wiped. Disable and " +
                    "re-enable Secret Key, or restore from a backup that includes the kit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun DisabledStateContent(
    optedOut: Boolean,
    isBusy: Boolean,
    onEnable: () -> Unit,
) {
    // Same vocabulary structure as the enabled card (key:value rows +
    // a "Future backup restores require:" sentence + a primary action).
    // No icons or emoji - state is conveyed by title text + a neutral
    // surface tint.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Master Password Only",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (optedOut) "Secret Key was skipped at vault creation"
                    else "Secret Key is not enabled on this vault",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Future backup restores require only your master password. " +
                    "If your master password leaks, encrypted backup files may " +
                    "be brute-forceable.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Add Secret Key for stronger protection. A 128-bit value " +
                    "generated on this device is mixed into the key derivation, " +
                    "so an attacker needs both your password AND your Secret Key " +
                    "to decrypt a backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onEnable,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enable Secret Key")
            }
        }
    }
}

@Composable
private fun EnabledStateContent(
    generationDateMs: Long?,
    lastKitDownloadAtMs: Long?,
    storageBacking: SecretKeyStorageTier,
    isBusy: Boolean,
    onRotate: () -> Unit,
    onDisable: () -> Unit,
    onRedownloadKit: () -> Unit,
) {
    // "Up to date" means a kit save exists AND is at least as recent
    // as the most recent generation (enable or rotate). Anything older
    // is staler than the active SK and would not protect new backups.
    val kitUpToDate = lastKitDownloadAtMs != null &&
        generationDateMs != null &&
        lastKitDownloadAtMs >= generationDateMs

    // Unified status card. Three sub-states keyed on the SK + kit pair:
    //   A.1 - kit current  -> title "Secret Key Protection Enabled"
    //   A.2 - kit not saved -> title "Save your Emergency Kit" (error tint)
    // The body uses aligned key-value rows ("Generated:", "Emergency Kit:",
    // "Storage:") followed by a single human-language sentence that tells
    // the user what's needed at restore time. This replaces the three
    // previous cards (intro / status / storage backing) that all spoke
    // around the same point.
    val cardContainer = if (kitUpToDate) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val titleColor = if (kitUpToDate) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (kitUpToDate) "Secret Key Protection Enabled"
                    else "Save your Emergency Kit",
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                )
                Text(
                    "128-bit Secret Key active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (generationDateMs != null) {
                    KeyValueRow("Generated:", formatGenerationDate(generationDateMs))
                }
                KeyValueRow(
                    "Emergency Kit:",
                    if (kitUpToDate) {
                        "Exported ${formatGenerationDate(lastKitDownloadAtMs!!)}"
                    } else {
                        "Not yet exported"
                    },
                )
                KeyValueRow("Storage:", storageBackingLabel(storageBacking))
            }
            if (kitUpToDate) {
                Text(
                    "Future backup restores require both your master password " +
                        "and your Secret Key.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Future backup restores require your master password, your " +
                        "Secret Key, AND your Emergency Kit. Without the kit, " +
                        "backups cannot be restored on a new device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(
                    onClick = onRedownloadKit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Emergency Kit")
                }
            }
        }
    }

    // Action cluster.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text("Save Emergency Kit again") },
                supportingContent = {
                    Text("Re-export the PDF if you want a fresh copy.")
                },
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(
                    onClick = onRedownloadKit,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download kit again")
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text("Rotate Secret Key") },
                supportingContent = {
                    Text(
                        "Generate a fresh 128-bit Secret Key and drop the old one. " +
                            "You must save a new Emergency Kit afterwards."
                    )
                },
                leadingContent = { Icon(Icons.Default.Autorenew, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(
                    onClick = onRotate,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Rotate Secret Key")
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text("Disable Secret Key") },
                supportingContent = {
                    Text(
                        "Turn off Secret Key on this device. Backups exported while " +
                            "Secret Key was on will still require the SK to restore."
                    )
                },
                leadingContent = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(
                    onClick = onDisable,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Disable Secret Key")
                }
            }
        }
    }
}

/**
 * Two-column aligned "label: value" row used inside the unified status
 * card. Pure text - no icons - so the visual rhythm comes from the
 * fixed-width label column plus a colon, mirroring the user's mockup.
 * The label is rendered in muted secondary text and the value in
 * normal on-surface colour so the eye locks onto the values.
 */
@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 128.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun storageBackingLabel(tier: SecretKeyStorageTier): String = when (tier) {
    SecretKeyStorageTier.STRONGBOX -> "Hardware-isolated (StrongBox)"
    SecretKeyStorageTier.TEE -> "Hardware-backed (TEE)"
    SecretKeyStorageTier.SOFTWARE -> "Software fallback"
    SecretKeyStorageTier.UNKNOWN -> "Checking..."
}

private fun formatGenerationDate(ms: Long): String {
    // Card design uses dd MMM yyyy ("11 Jun 2026") over the ISO-style
    // yyyy-MM-dd because the new status card lays the date out in
    // human-friendly aligned rows ("Generated: 11 Jun 2026"). ISO format
    // is more compact but reads as "implementation detail timestamp"
    // rather than "the day this happened".
    val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return fmt.format(Date(ms))
}
