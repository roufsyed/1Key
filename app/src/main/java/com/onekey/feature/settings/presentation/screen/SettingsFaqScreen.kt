package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFaqScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FAQ") },
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
            FaqGroup("Encryption & passwords") {
                FaqItem(
                    question = "How are my passwords encrypted?",
                    answer = "With AES-256-GCM — the same algorithm used by banking apps and " +
                        "government systems. The encryption key is derived from your master " +
                        "password using PBKDF2 with 310,000 iterations, deliberately slow so " +
                        "brute-forcing is impractical.",
                )
                FaqItem(
                    question = "Where is my master password stored?",
                    answer = "Nowhere. It's never written to disk — not as plaintext, not as a " +
                        "hash. While the vault is unlocked we hold the derived encryption key " +
                        "in memory; the moment you lock the vault, that key is dropped from memory.",
                )
                FaqItem(
                    question = "What happens if I forget my master password?",
                    answer = "Your data is unrecoverable. There's no \"forgot password\" link " +
                        "because there's no server and no recovery copy of your key anywhere. " +
                        "Only you can decrypt your vault — that's what makes it truly private.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Memory & runtime") {
                FaqItem(
                    question = "What does the app keep in memory while running?",
                    answer = "Only what's on screen and the encryption key for the unlocked " +
                        "session. Decrypted passwords are not cached. When the auto-lock fires, " +
                        "the encryption key is dropped and the vault returns to its " +
                        "encrypted-only state.",
                )
                FaqItem(
                    question = "Why does unlocking or creating a vault take a few seconds?",
                    answer = "That delay is PBKDF2 running 310,000 rounds of cryptographic work " +
                        "to turn your master password into the encryption key. Slowness here is " +
                        "the feature — it's what makes guessing your password too expensive to " +
                        "be practical.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Storage & device") {
                FaqItem(
                    question = "Where is my data stored?",
                    answer = "Locally on this device, in an encrypted database inside the app's " +
                        "private storage. Other apps on your phone can't read it. Nothing leaves " +
                        "the device unless you explicitly export a backup.",
                )
                FaqItem(
                    question = "What happens to my data if I uninstall the app?",
                    answer = "It goes with the app. Android removes the app's private storage on " +
                        "uninstall — your vault, your settings, everything. Export an encrypted " +
                        "backup first if you want to keep it.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Privacy") {
                FaqItem(
                    question = "Does the app talk to any servers?",
                    answer = "No. The app has no internet permission, no analytics, no crash " +
                        "reporting, no telemetry. You can verify in Android's app permissions " +
                        "screen — there's no \"Internet\" entry to grant or revoke.",
                )
                FaqItem(
                    question = "Can the developer (or anyone else) see my passwords?",
                    answer = "No. Your data never leaves your device, so there's literally no " +
                        "path for anyone — developer, Google, your network — to access it. " +
                        "Decryption requires your master password, which only you have.",
                )
                FaqItem(
                    question = "Why does the app block screenshots by default?",
                    answer = "Screenshots, screen recordings, and the Recent Apps preview can " +
                        "all capture passwords on screen. Blocking them prevents accidental " +
                        "leaks (or someone glancing at your phone). You can turn the block off " +
                        "in Security settings if you really need screenshots.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Biometric & re-authentication") {
                FaqItem(
                    question = "Why does enabling biometric require my master password?",
                    answer = "Biometric unlock provides the same full access as your master " +
                        "password. We ask for the master password once when you enable " +
                        "biometric, to make sure it's really you turning that shortcut on.",
                )
                FaqItem(
                    question = "Why does biometric get paused after several wrong master-password attempts?",
                    answer = "Three wrong attempts in a row could mean someone other than you is " +
                        "trying to get in. Pausing biometric forces the next unlock to use the " +
                        "master password — that proves it's really you before the easier " +
                        "biometric path resumes. After one successful master-password unlock, " +
                        "biometric is back to normal.",
                )
                FaqItem(
                    question = "Why does the app sometimes ask for my master password even though biometric works?",
                    answer = "By default, every 48 hours we re-prompt for the master password " +
                        "regardless of biometric or PIN. It's a safety check so you don't " +
                        "gradually forget the password the rest of your security depends on. " +
                        "The interval is configurable in Security settings.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Recycle bin & deletion") {
                FaqItem(
                    question = "Why is there a recycle bin? Are bin items still encrypted?",
                    answer = "Accidentally deleting a credential you need is worse than briefly " +
                        "storing one you've deleted. The bin holds deleted items for 30 days " +
                        "(configurable) so you can restore them. They're encrypted with the same " +
                        "algorithm as active items — the only difference is they're filtered out " +
                        "of normal views.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Backup & migration") {
                FaqItem(
                    question = "What's the difference between an encrypted backup and a plain export?",
                    answer = "An encrypted .1key backup is locked with your master password — " +
                        "useless to anyone without it. JSON or CSV exports are plain text — " +
                        "anyone who finds the file can read your passwords. Use encrypted " +
                        "backups unless you're migrating to another app that can't read the " +
                        "encrypted format.",
                )
                FaqItem(
                    question = "Can I move my vault to another device?",
                    answer = "Yes. Export an encrypted backup from this device, install 1Key on " +
                        "the new one, and choose \"Restore from backup\" during setup. The " +
                        "backup password becomes your new master password.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Clipboard") {
                FaqItem(
                    question = "What happens when I copy a password to my clipboard?",
                    answer = "The clipboard is automatically cleared after 30 seconds so a " +
                        "copied password doesn't sit there waiting to be picked up by another app.",
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FaqGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionHeader(title)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun FaqItem(question: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(question) },
                trailingContent = {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                    )
                },
                modifier = Modifier.clickable { expanded = !expanded },
            )
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Text(
                    answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}
