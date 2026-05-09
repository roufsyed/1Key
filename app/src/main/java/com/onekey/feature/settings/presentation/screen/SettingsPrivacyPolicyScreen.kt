package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPrivacyPolicyScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy") },
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
            Text(
                "Last updated: April 2026",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            PolicySection("What we don't collect") {
                PrivacyLine("No accounts, no email, no signup of any kind.")
                PrivacyLine("No analytics, telemetry, or crash reporting — ever.")
                PrivacyLine("No advertising IDs and no tracking.")
            }

            PolicySection("How your data is protected") {
                PrivacyLine("All credentials are encrypted with AES-256-GCM, the same algorithm protecting banking apps and government systems.")
                PrivacyLine("Your master password is run through PBKDF2 with 310,000 iterations to derive cryptographic material — deliberately slow, so brute-forcing is impractical.")
                PrivacyLine("Your master password is never written to disk in cleartext. We do store a small password verifier (an encrypted token only the correct password can decrypt) so we can confirm a password attempt without persisting the password itself.")
                PrivacyLine("The vault key is encrypted by the Android Keystore (hardware-backed on supported devices) and stored in that wrapped form on disk. The unwrapped, usable form lives in memory only while the vault is unlocked, and is dropped from memory the moment the vault locks.")
                PrivacyLine("2FA codes (TOTP secrets) are encrypted with the same vault key as your passwords.")
            }

            PolicySection("Permissions we use") {
                PrivacyLine("Biometric — to verify your fingerprint or face for app unlock. The biometric data itself never reaches the app; we only receive a yes/no result from Android.")
                PrivacyLine("Camera — only for QR-code scanning when you add a new 2FA secret. No photos are taken or stored.")
                PrivacyLine("Storage — used only on older Android versions for reading and writing backup files. Modern Android uses the system file picker, which doesn't require this permission.")
            }

            PolicySection("Permissions we don't request") {
                PrivacyLine("No Internet permission — the app cannot make any network request, period. You can verify this in Android Settings → Apps → 1Key → Mobile data & Wi-Fi: data usage is exactly zero, ever.")
                PrivacyLine("No location, contacts, microphone, SMS, or any other personal-data permission.")
            }

            PolicySection("Clipboard & screen capture") {
                PrivacyLine("Sensitive copies (passwords, 2FA codes) are automatically cleared from the clipboard after 30 seconds.")
                PrivacyLine("On Android 13 and above, those copies are marked sensitive so the system's paste-preview toast doesn't reveal the value.")
                PrivacyLine("Screenshots and screen recordings of the app are blocked by default — including the Recent Apps preview. You can change this in Security settings.")
                PrivacyLine("Revealed passwords cannot be selected from the on-screen text via the long-press menu, so they can only leave the app via the in-app copy button (which routes through the auto-clear path).")
            }

            PolicySection("Backups & recycle bin") {
                PrivacyLine("Encrypted .1key backups are protected by your master password. Without that password the backup file is mathematically useless.")
                PrivacyLine("Plain JSON or CSV exports are unencrypted text. Treat those files as sensitive: anyone who finds one can read every password inside.")
                PrivacyLine("Items in the recycle bin remain encrypted with the same algorithm as active items. They're auto-purged after 30 days by default; you can change the retention or disable the bin entirely in Settings.")
            }

            PolicySection("Data deletion") {
                PrivacyLine("Uninstalling the app removes all of your data. Android wipes the app's private storage, including your encrypted vault. There is no remote copy of anything.")
                PrivacyLine("Use \"Delete Vault\" in Settings to wipe all credentials while keeping the app installed. This requires your master password to confirm.")
            }

            PolicySection("Background activity") {
                PrivacyLine("The app does not run when you are not using it. There are no background services, scheduled jobs, or wake-ups. When you close or background the app, the auto-lock timer fires and the encryption key is dropped from memory.")
            }

            PolicySection("Regulatory") {
                PrivacyLine("GDPR, CCPA, and COPPA do not apply to this app because no personal data is collected, processed, or transmitted at any point.")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PolicySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}
