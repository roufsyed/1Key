package com.roufsyed.onekey.feature.settings.presentation.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
import com.roufsyed.onekey.feature.settings.presentation.dialog.LeaveKitSaveWarningDialog
import com.roufsyed.onekey.feature.settings.presentation.viewmodel.SecretKeySettingsEvent
import com.roufsyed.onekey.feature.settings.presentation.viewmodel.SecretKeySettingsViewModel

/**
 * Post-enable / post-rotate prompt that forces the user to save their
 * Emergency Kit before continuing. Reads the canonical printed form of the
 * Secret Key from the in-memory holder via the VM (which threads
 * [com.roufsyed.onekey.core.security.SecretKeyHolder.withBytes] through to the kit
 * formatter). The lambda zeros the defensive copy when it exits.
 *
 * # Why we read from the holder (not a navigation argument)
 *
 * Challenger Issue 8: routing the raw 16 SK bytes through a navigation
 * argument would either expose them in a `savedStateHandle` (heap residency
 * indefinitely) or require a typed-argument codec that defeats the point of
 * keeping the SK in a holder. The holder is the canonical in-memory home;
 * this screen pulls from it and never captures the result in a recomposable
 * `remember`.
 *
 * # Required checkbox
 *
 * The "I have saved my kit safely" checkbox gates the Done button. The
 * checkbox is rememberSaveable so a rotation does not lose the user's
 * acknowledgement, but it is reset to false on every fresh entry into the
 * screen (because the route is composable-scoped: navigating in pushes a
 * fresh composition).
 *
 * # Save flow
 *
 * The Save Kit button launches a SAF [ActivityResultContracts.CreateDocument]
 * with a default filename. On the URI callback, the VM writes the PDF
 * bytes through [android.content.ContentResolver.openOutputStream]. The
 * PDF is rendered by [com.roufsyed.onekey.feature.secretkey.pdf.EmergencyKitPdfGenerator]
 * inside the holder's `withBytes` lambda so the bytes are zeroed as soon as
 * the renderer returns.
 *
 * # Print path
 *
 * Stage 5 surfaces the Print path as a placeholder action that shows a
 * "Coming soon" snackbar. The actual platform-print wiring lands in a
 * future stage; the design reserves the slot here so the UI never has to
 * be reshuffled when print drops in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyKitSavePromptScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: SecretKeySettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val canonicalDisplay by vm.canonicalKitPrintedForm.collectAsStateWithLifecycle()
    val isSavingKit by vm.isSavingKit.collectAsStateWithLifecycle()
    val lastKitDownloadAt by vm.lastKitDownloadAt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Required checkbox state. rememberSaveable preserves the ack across a
    // rotation so the user does not lose progress. New entry into the
    // screen starts with the box unchecked because navigation pushes a
    // fresh composition.
    var ackSaved by rememberSaveable { mutableStateOf(false) }
    // Confirmation dialog shown when the user tries to back out without
    // having completed both the actual PDF save AND the ack checkbox.
    // Once both are true, dismissing the screen is intended behaviour
    // and the dialog never fires.
    var showLeaveWarning by rememberSaveable { mutableStateOf(false) }
    val saveIncomplete = !ackSaved || lastKitDownloadAt == null

    // System back / IME back: if the user has not finished saving,
    // intercept and surface the warning dialog instead of popping.
    BackHandler(enabled = saveIncomplete) {
        showLeaveWarning = true
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? ->
        // The SAF picker has returned (with or without a URI). Clear the
        // picker-active flag so background/inactivity locks can resume.
        // Without this the auto-lock timer would have started ticking
        // already if we leaked the suppression, and the holder's SK
        // would be zeroed before the save coroutine writes the PDF.
        vm.notifyPickerDone()
        if (uri != null) {
            vm.saveKitToUri(uri, context)
        }
    }

    // Populate the printed-form Secret Key string the moment the screen
    // mounts. Without this call the VM's canonicalKitPrintedForm StateFlow
    // stays null, the "Loading..." placeholder never goes away, and the
    // Save button stays disabled because its enabled condition keys on
    // canonicalDisplay != null. The KDoc on the screen has always claimed
    // this was wired up; this LaunchedEffect is the actual wiring.
    LaunchedEffect(Unit) {
        vm.refreshCanonicalKitPrintedForm()
    }

    LaunchedEffect(Unit) {
        vm.event.collect { event ->
            when (event) {
                is SecretKeySettingsEvent.KitSaved -> {
                    snackbarHostState.showSnackbar("Emergency Kit saved")
                }
                is SecretKeySettingsEvent.KitSaveFailed -> {
                    snackbarHostState.showSnackbar(
                        event.message.ifBlank { "Could not save Emergency Kit." }
                    )
                }
                SecretKeySettingsEvent.PrintNotYetSupported -> {
                    // The Print button calls vm.requestPrintKit() which is
                    // currently a stub that emits this event. Surface the
                    // explanatory snackbar so the user knows the button
                    // worked but the feature has not landed yet.
                    snackbarHostState.showSnackbar(
                        "Print support is coming soon. Save the PDF for now.",
                    )
                }
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Save your Emergency Kit") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (saveIncomplete) {
                            showLeaveWarning = true
                        } else {
                            onBack()
                        }
                    }) {
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
            // Header / intro copy.
            Text(
                "Your Secret Key has been generated. Save your Emergency Kit " +
                    "now - it carries the 128-bit value 1Key uses (alongside " +
                    "your master password) to derive your vault key.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Canonical SK preview. Rendered in a monospaced font to mirror
            // the printed PDF; tap-to-copy is intentionally NOT here -
            // copy-to-clipboard would leak the SK to the system clipboard
            // and we keep the only on-device transit path the PDF/printer
            // flow.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Your Secret Key",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = canonicalDisplay ?: "Loading...",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Anyone with this value plus your master password can decrypt " +
                            "your V5 backups. Keep it private.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Save action. Primary path - PDF to user-chosen SAF location.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        // Suppress auto-lock BEFORE the SAF intent
                        // foregrounds the system Files app. Otherwise
                        // the background-lock timer fires during the
                        // file-picker round-trip and the SK plaintext
                        // is zeroed in the holder by the time the
                        // result callback returns, leaving the save
                        // coroutine with no SK to render the PDF.
                        vm.notifyPickerLaunched()
                        saveLauncher.launch(
                            vm.defaultKitFilename(),
                        )
                    },
                    enabled = !isSavingKit && canonicalDisplay != null,
                    modifier = Modifier.fillMaxWidth(0.5f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Save PDF")
                }
                OutlinedButton(
                    onClick = {
                        vm.requestPrintKit()
                    },
                    enabled = !isSavingKit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Print")
                }
            }

            // Save status line. Updates after a successful save so the
            // user has a visible confirmation BEFORE they tick the ack box.
            if (lastKitDownloadAt != null) {
                Text(
                    "Last saved on this device: ${vm.formatKitDownloadAt(lastKitDownloadAt!!)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Required checkbox. Gates Done.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = ackSaved,
                        onCheckedChange = { ackSaved = it },
                    )
                    Text(
                        "I have saved my Emergency Kit somewhere safe.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Reminder if the user has not saved yet. Stays in surface
            // styling (not error) until the user tries to leave without
            // saving - that pathway is owned by the parent navigation
            // guard, which can intercept the back gesture.
            if (lastKitDownloadAt == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Save the kit before you continue. If you lose it, " +
                                "V5 backups taken on this device cannot be restored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Done button. Enabled iff:
            //  - the user has at least one save (lastKitDownloadAt != null), AND
            //  - the required ack checkbox is ticked.
            Button(
                onClick = onDone,
                enabled = ackSaved && lastKitDownloadAt != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Done")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showLeaveWarning) {
        LeaveKitSaveWarningDialog(
            onContinueSaving = { showLeaveWarning = false },
            onLeaveAnyway = {
                showLeaveWarning = false
                onBack()
            },
        )
    }
}
