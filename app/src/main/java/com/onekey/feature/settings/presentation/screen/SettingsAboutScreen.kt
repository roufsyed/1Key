package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            ExpandableInfoCard(
                title = "Privacy Policy",
                icon = Icons.Default.PrivacyTip,
            ) {
                PrivacyLine("All credentials are stored locally on this device using AES-256-GCM encryption.")
                PrivacyLine("1Key does not require an account or internet connection.")
                PrivacyLine("No analytics, telemetry, or crash reporting of any kind.")
                PrivacyLine("Your master password never leaves your device — not even a hash.")
                PrivacyLine("Encrypted .1key backups are protected by your master password. Plain JSON or CSV exports are unencrypted — treat those files as sensitive.")
            }
        }
    }
}
