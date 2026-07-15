package com.roufsyed.onekey.feature.settings.presentation.screen

import com.roufsyed.onekey.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
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
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
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
                "Last updated: May 2026",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            PolicySection("What we don't collect") {
                PrivacyLine("No accounts, no email, no signup of any kind.")
                PrivacyLine("No analytics, telemetry, or crash reporting - ever.")
                PrivacyLine("No advertising IDs and no tracking.")
            }

            PolicySection("How your data is protected") {
                PrivacyLine("Field-level encryption. Every credential field - title, username, password, URL, notes, custom fields, TOTP secret - is encrypted separately with AES-256-GCM. Each field's ciphertext is bound to its row and column, so an attacker can't swap encrypted blobs between accounts.")
                PrivacyLine("Memory-hard password verification. Your master password is checked using Argon2id (64 MiB memory, 3 passes) - the OWASP-recommended algorithm. Even with the encrypted blobs in hand, brute-forcing is unrealistic on consumer hardware.")
                PrivacyLine("Verifier kept off-disk-readable. The password verifier and PIN hash live in EncryptedSharedPreferences, encrypted at rest by an Android Keystore-bound key. Without live access to your phone's Keystore, an attacker can't read the verifier - meaning offline brute-forcing is not possible.")
                PrivacyLine("Hardware-backed wrapping. The vault key is wrapped by a key inside the Android Keystore (TEE / StrongBox on supported devices). On Android 9+ unwrapping additionally requires the device to be unlocked.")
                PrivacyLine("Subkey separation. Field encryption uses HKDF-derived subkeys, not the raw vault key - so different parts of the data are encrypted under keys derived for that purpose only.")
                PrivacyLine("Memory hygiene. The unwrapped vault key only exists in memory while the vault is unlocked, and is dropped the moment auto-lock fires or you lock manually.")
                PrivacyLine("Existing installs migrate automatically. Older vaults using the previous PBKDF2 verifier silently upgrade to Argon2id on the first unlock after the app update.")
            }

            PolicySection("Permissions we use") {
                PrivacyLine("Biometric - to verify your fingerprint or face for app unlock. The biometric data itself never reaches the app; we only receive a yes/no result from Android.")
                PrivacyLine(
                    "Camera - for QR-code scanning when you add a new 2FA secret" +
                        (if (BuildConfig.HAS_OCR) ", and OCR credential capture." else ".") +
                        " No photos are taken or stored; frames are processed on-device and discarded.",
                )
                PrivacyLine("Storage - used only on Android 12 and below for reading and writing backup files. Modern Android uses the system file picker, which doesn't require this permission.")
            }

            PolicySection("Permissions we don't request") {
                PrivacyLine("No Internet permission - the app cannot make any network request, period. You can verify this in Android Settings → Apps → 1Key → Mobile data & Wi-Fi: data usage is exactly zero, ever.")
                PrivacyLine("No location, contacts, microphone, SMS, or any other personal-data permission.")
            }

            PolicySection("Brute-force protection") {
                PrivacyLine("Wrong master-password and wrong-PIN attempts are tracked in persistent storage, so killing the app cannot reset the counter.")
                PrivacyLine("Tiered cooldowns: 3 wrong attempts trigger a 30-second wait, 5 trigger 5 minutes, 10 trigger 1 hour.")
                PrivacyLine("After 3 wrong PINs, the easier PIN unlock is disabled until you successfully enter the master password - proving real identity before the shortcut resumes.")
                PrivacyLine("Each guess pays the full Argon2id memory cost regardless of cooldown, so brute-forcing remains intractable even between wait periods.")
            }

            PolicySection("Threat model - what 1Key defends against") {
                PrivacyLine("Offline attacks on a stolen or imaged device. The vault key never lives on disk in unwrapped form. The master-password verifier sits in EncryptedSharedPreferences and is unreadable without live Keystore access. A disk image alone cannot be brute-forced offline.")
                PrivacyLine("Brute-force password guessing. Argon2id (m=64 MiB, t=3, p=1) on every attempt, tiered cooldowns that persist across process kills, and PIN escalation to master password after three wrong PINs.")
                PrivacyLine("Database tampering. Per-field AES-256-GCM with row + column AAD detects any attempt to swap encrypted blobs between accounts.")
                PrivacyLine("Backup-file tampering. The .1key envelope binds the export timestamp and vault-version counter into the GCM authentication tag - swapping bodies, replaying older backups, or modifying headers all fail authentication.")
                PrivacyLine("Network exfiltration. No INTERNET permission in the manifest. The OS enforces this; the app literally cannot make a network request.")
                PrivacyLine("Casual shoulder-surfing of usage patterns. Screenshots, screen recordings, and the Recent Apps preview are blocked by default via FLAG_SECURE. Clipboard copies of secrets auto-clear after 30 seconds.")
            }

            PolicySection("Threat model - what 1Key does NOT defend against") {
                PrivacyLine("These are not bugs. They are limits we want you to know about up front.")
                PrivacyLine("A fully compromised device. If the OS itself is owned (root malware, a privilege-escalation exploit running as the same uid as 1Key, a debugger attached at the right moment), nothing in user-space can protect the in-memory vault key while the vault is unlocked. The app's sandbox is a guarantee from Android, not from us.")
                PrivacyLine("Loss of your master password. There is no reset, no recovery code, no support email. If you forget it, the vault is gone - and so are your encrypted backups, because they are encrypted with the same password. This is intentional: the only path to recovery would be us holding a copy of your password (which we refuse to do), or a multi-secret escrow system (which would need a server we have nowhere to run). Pick a password you can remember and store it somewhere physical if you need to.")
                PrivacyLine("Theft of an already-unlocked device. If someone grabs your phone while the vault is unlocked, before auto-lock fires, the unlocked vault is readable. There is no remote wipe, no kill switch we can fire from elsewhere - we have nowhere to fire it from. Lower the inactivity-lock timeout if this matters to you.")
                PrivacyLine("Biometric spoofing on an unlocked device. Android's class-2 biometric sensors (older fingerprint readers, weaker face unlock) can be fooled by good-quality fakes. The mitigation is to use a strong master password and treat biometric as convenience, not as a second factor.")
                PrivacyLine("Keystore compromise on pre-Android 9 devices. On API < 28, the legacy Keystore key wrapping the vault key does not require the device to be unlocked at use time. If someone images the device storage and can later interact with the Keystore (rooted device, certain TEE exploits), the vault key can be unwrapped. On API >= 28 this is mitigated by setUnlockedDeviceRequired(true).")
                PrivacyLine("A malicious user who knows your master password. Once the password is in the wrong hands, the vault is open. We cannot tell who is typing.")
                PrivacyLine("If any of these matter for your specific threat model, a local-only password manager is not your best fit. Use a hardened OS build (GrapheneOS), pair it with a hardware token, and accept that some convenience trade-offs are required.")
            }

            PolicySection("Clipboard & screen capture") {
                PrivacyLine("Sensitive copies (passwords, 2FA codes) are automatically cleared from the clipboard after 30 seconds.")
                PrivacyLine("On Android 13 and above, those copies are marked sensitive so the system's paste-preview toast doesn't reveal the value.")
                PrivacyLine("Screenshots and screen recordings of the app are blocked by default - including the Recent Apps preview. You can change this in Security settings.")
                PrivacyLine("Revealed passwords cannot be selected from the on-screen text via the long-press menu, so they can only leave the app via the in-app copy button (which routes through the auto-clear path).")
            }

            PolicySection("Backups & recycle bin") {
                PrivacyLine("Encrypted .1key backups are protected by your master password using Argon2id. The export timestamp and vault-version counter are also authenticated, so a backup file can't be tampered with or replayed across vaults without detection.")
                PrivacyLine("Plain JSON or CSV exports are unencrypted text. Treat those files as sensitive: anyone who finds one can read every password inside. 1Key warns clearly and requires master-password confirmation before producing one.")
                PrivacyLine("Items in the recycle bin remain encrypted with the same protections as active items. They're auto-purged after 30 days by default; you can change the retention (1 week, 30 days, 6 months, 1 year, or never) or disable auto-purge entirely in Settings.")
            }

            PolicySection("Data deletion") {
                PrivacyLine("Uninstalling the app removes all of your data. Android wipes the app's private storage, including your encrypted vault. There is no remote copy of anything.")
                PrivacyLine("Use \"Delete Vault\" in Settings to wipe all credentials while keeping the app installed. This requires your master password to confirm.")
            }

            PolicySection("Background activity") {
                PrivacyLine("The app does not run when you are not using it. There are no background services, scheduled jobs, or wake-ups. When you close or background the app, the auto-lock timer fires (immediately by default) and the encryption key is dropped from memory.")
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
