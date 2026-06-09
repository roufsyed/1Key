package com.onekey.feature.settings.presentation.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onekey.feature.settings.presentation.viewmodel.AutofillSettingsViewModel
import com.onekey.feature.settings.presentation.viewmodel.SettingsHighlightKeys

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAutofillScreen(
    onBack: () -> Unit,
    viewModel: AutofillSettingsViewModel = hiltViewModel(),
) {
    val isEnabled by viewModel.isAutofillEnabled.collectAsStateWithLifecycle()
    val isCategoryFilterEnabled by viewModel.isCategoryFilterEnabled.collectAsStateWithLifecycle()
    val isSystemProvider by viewModel.isSystemAutofillProvider.collectAsStateWithLifecycle()
    val highlightKey by viewModel.highlightKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh the OS-level "is 1Key the current provider" status on every
    // ON_RESUME - the user toggles this in the system settings page, which
    // gives us no direct callback when they return.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearHighlight() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = { Text("Autofill") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
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
            SectionHeader("System")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SystemStatusRow(
                        isProvider = isSystemProvider,
                        onSetAsProvider = {
                            // ACTION_REQUEST_SET_AUTOFILL_SERVICE prompts the
                            // user to confirm 1Key as their provider. Some
                            // OEM builds reject this with ActivityNotFound -
                            // fall back to the generic autofill settings
                            // page in that case.
                            val primary = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            val ok = runCatching { context.startActivity(primary) }.isSuccess
                            if (!ok) {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                            }
                        },
                    )
                }
            }

            SectionHeader("In-app behaviour")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HighlightableRow(
                        isHighlighted = highlightKey == SettingsHighlightKeys.AUTOFILL_ENABLED,
                        onHighlightConsumed = viewModel::clearHighlight,
                    ) {
                        ListItem(
                            headlineContent = { Text("Enable autofill") },
                            supportingContent = {
                                Text(
                                    if (isEnabled) "1Key offers chips when supported fields are detected"
                                    else "1Key returns no chips even when set as your provider"
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { viewModel.setAutofillEnabled(it) },
                                )
                            },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("Show category filter") },
                        supportingContent = {
                            Text(
                                "Add a chip row below the search field so you can narrow by tag. " +
                                    "Off by default - most fills are resolved by host match before search."
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = isCategoryFilterEnabled,
                                onCheckedChange = { viewModel.setCategoryFilterEnabled(it) },
                            )
                        },
                    )
                }
            }

            SectionHeader("How autofill works in 1Key")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrivacyLine(
                        "Filling matches by exact host or app package - never by similarity. " +
                                "A saved login for github.com will never offer itself on a similarly-named site."
                    )
                    PrivacyLine(
                        "When the vault is locked, 1Key shows a single \"Search 1Key\" chip with no " +
                                "saved data attached. Tapping it unlocks the vault locally before any " +
                                "matches are revealed."
                    )
                    PrivacyLine(
                        "No network calls are made for autofill - same guarantee as the rest of 1Key. " +
                                "Field structure leaves your device only when the system delivers it to us, " +
                                "and we throw it away after each request."
                    )
                    PrivacyLine(
                        "1Key never offers chips inside its own UI, the system settings panel, the " +
                                "launcher, or any system permission prompt."
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SystemStatusRow(
    isProvider: Boolean,
    onSetAsProvider: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                if (isProvider) "1Key is your autofill provider" else "1Key is not your autofill provider",
                fontWeight = FontWeight.SemiBold,
            )
        },
        supportingContent = {
            Text(
                if (isProvider) {
                    "You can disable individual fills with the toggle below - or change provider in your phone's Settings."
                } else {
                    "Tap below to make 1Key your provider. The system will ask you to confirm."
                }
            )
        },
        leadingContent = {
            Icon(
                if (isProvider) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isProvider) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = if (!isProvider) {
            {
                TextButton(onClick = onSetAsProvider) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Enable")
                }
            }
        } else {
            {
                TextButton(onClick = onSetAsProvider) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Change")
                }
            }
        },
    )
}
