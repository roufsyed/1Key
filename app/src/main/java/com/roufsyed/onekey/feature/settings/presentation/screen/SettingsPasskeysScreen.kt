package com.roufsyed.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roufsyed.onekey.core.presentation.lockaware.LockAwareDialog
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
import com.roufsyed.onekey.feature.settings.presentation.viewmodel.PasskeySettingsViewModel
import com.roufsyed.onekey.feature.settings.presentation.viewmodel.PasskeySettingsViewModel.SystemStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPasskeysScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: PasskeySettingsViewModel = hiltViewModel(),
) {
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isShowTab by viewModel.isShowTab.collectAsStateWithLifecycle()
    val systemStatus by viewModel.systemStatus.collectAsStateWithLifecycle()
    val trusted by viewModel.trustedBrowsers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAddBrowser by remember { mutableStateOf(false) }
    var pendingUntrust by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSystemSettings() {
        for (intent in viewModel.systemSettingsIntents()) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
                title = { Text("Passkeys") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
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
            if (!viewModel.supportedOnThisDevice) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                        headlineContent = { Text("Passkeys need Android 14 or later") },
                        supportingContent = { Text("This phone runs an older Android release, which has no way for apps other than Google's to store passkeys.") },
                    )
                }
                return@Column
            }

            SectionHeader("System")
            Card(modifier = Modifier.fillMaxWidth()) {
                val (headline, body, ok) = when (systemStatus) {
                    SystemStatus.ENABLED -> Triple("1Key is a passkey provider", "Sites and apps can save passkeys to your vault and sign in with them.", true)
                    SystemStatus.AUTOFILL_ONLY -> Triple(
                        "Passkeys are not switched on yet",
                        "1Key is your autofill service, but Android only enables the abilities an app had when you chose it. " +
                            "Open Android settings, pick a different service, then pick 1Key again.",
                        false,
                    )
                    SystemStatus.NOT_ENABLED -> Triple("1Key is not enabled for passkeys", "Turn on 1Key under Passwords, passkeys and accounts in Android settings.", false)
                    SystemStatus.UNKNOWN -> Triple("Enable 1Key in Android settings", "Android could not say whether 1Key is enabled. Check under Passwords, passkeys and accounts.", true)
                    SystemStatus.UNSUPPORTED -> Triple("Not supported", "", false)
                }
                ListItem(
                    headlineContent = { Text(headline, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(body) },
                    leadingContent = {
                        Icon(
                            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { openSystemSettings() }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (systemStatus == SystemStatus.ENABLED) "Change" else "Open")
                        }
                    },
                )
            }

            SectionHeader("In-app behaviour")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text("Act as a passkey provider") },
                        supportingContent = {
                            Text(
                                if (isEnabled) "1Key answers passkey requests from browsers and apps"
                                else "1Key stays silent even when enabled in Android settings",
                            )
                        },
                        trailingContent = { Switch(checked = isEnabled, onCheckedChange = { viewModel.setEnabled(it) }) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Show Passkeys tab") },
                        supportingContent = {
                            Text(
                                if (isShowTab) "Passkeys visible in bottom navigation"
                                else "Passkeys hidden from bottom navigation",
                            )
                        },
                        trailingContent = { Switch(checked = isShowTab, onCheckedChange = { viewModel.setShowTab(it) }) },
                    )
                }
            }

            SectionHeader("Trusted browsers")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    val summary = viewModel.allowlistSummary
                    ListItem(
                        headlineContent = { Text("Built-in list") },
                        supportingContent = {
                            Column {
                                Text(
                                    "1Key trusts ${summary.google} browsers from Google's list" +
                                        (if (summary.community > 0) " and ${summary.community} community browsers" else "") +
                                        (if (summary.fdroid > 0) ", plus ${summary.fdroid} F-Droid builds" else "") + ".",
                                )
                                summary.snapshotDate?.let { date ->
                                    Text(
                                        "Snapshot dated $date" + if (summary.stale) ". Older than 60 days; a newer 1Key release will refresh it." else "",
                                        color = if (summary.stale) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    if (trusted.isEmpty()) {
                        ListItem(
                            headlineContent = { Text("No browsers added by you") },
                            supportingContent = { Text("Only needed for a browser 1Key does not recognise.") },
                        )
                    } else {
                        trusted.forEach { browser ->
                            ListItem(
                                headlineContent = { Text(browser.label) },
                                supportingContent = {
                                    Text(
                                        browser.packageName +
                                            " · ${browser.certificateCount} ${if (browser.certificateCount == 1) "certificate" else "certificates"}" +
                                            if (!browser.installed) " · not installed" else "",
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { pendingUntrust = browser.packageName }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove ${browser.label}", tint = MaterialTheme.colorScheme.error)
                                    }
                                },
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        modifier = Modifier.clickable { showAddBrowser = true },
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        headlineContent = { Text("Add an installed browser") },
                        supportingContent = { Text("A trusted browser can ask 1Key to sign in to any website it shows. Add only browsers you installed on purpose.") },
                    )
                }
            }

            SectionHeader("Import")
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenBackup),
                    leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                    headlineContent = { Text("Import passkeys from KeePassXC or KeePassDX") },
                    supportingContent = {
                        Text(
                            "In KeePassXC export the database as an XML file, or one passkey with Export passkey. " +
                                "A KeePassDX database can be opened in KeePassXC first. Then use Backup and Import here.",
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                )
            }

            SectionHeader("What works where")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrivacyLine("Passkeys are created and used in browsers 1Key recognises, and in apps that created them. Everything stays in your encrypted vault and in your encrypted backups.")
                    PrivacyLine("Scanning a QR code to sign in on a laptop needs Google Play services on this phone. 1Key cannot do that part itself because it never connects to the internet.")
                    PrivacyLine("On phones without Google Play services, Chrome and Brave do not offer third-party passkeys. Firefox, Vanadium and Cromite do.")
                    PrivacyLine("1Key cannot check which app owns a website without a network, so an app can use only the passkeys it created itself. Passkeys made in a browser stay with browsers.")
                    PrivacyLine("Unencrypted exports never contain passkeys.")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAddBrowser) {
        val candidates = remember { viewModel.installedBrowsersToOffer() }
        LockAwareDialog(
            onDismissRequest = { showAddBrowser = false },
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            title = { Text("Trust an installed browser") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (candidates.isEmpty()) {
                        Text("Every installed browser is already trusted, either by 1Key's built-in list or by you.")
                    } else {
                        Text(
                            "1Key records the browser's signing certificate. If the browser is ever updated with a different key it stops being trusted until you add it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        candidates.forEach { browser ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    viewModel.trust(browser.packageName)
                                    showAddBrowser = false
                                },
                                headlineContent = { Text(browser.label) },
                                supportingContent = { Text(browser.packageName) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddBrowser = false }) { Text("Close") } },
        )
    }

    pendingUntrust?.let { pkg ->
        LockAwareDialog(
            onDismissRequest = { pendingUntrust = null },
            title = { Text("Stop trusting this browser?") },
            text = { Text("$pkg will no longer be able to ask 1Key for passkeys.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.untrust(pkg)
                    pendingUntrust = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingUntrust = null }) { Text("Cancel") } },
        )
    }
}
