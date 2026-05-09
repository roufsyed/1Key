package com.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
            FaqGroup("Encryption & passwords") {
                FaqItem(
                    question = "How are my passwords encrypted?",
                    answer = "Every credential field is encrypted on disk with AES-256-GCM — the " +
                        "same authenticated cipher used by Signal, WhatsApp, and modern TLS. " +
                        "Authenticated means tampering with the encrypted bytes is detected on " +
                        "decryption rather than producing scrambled output.\n\n" +
                        "The encryption key is derived from your master password using Argon2id " +
                        "— a memory-hard algorithm that allocates 64 MiB of RAM per attempt. " +
                        "Memory-hard means it can't be cheaply parallelised on GPUs or rented " +
                        "cloud farms the way older algorithms (like PBKDF2) can. Even with your " +
                        "encrypted blobs in hand, brute-forcing a decent password is unrealistic " +
                        "on consumer hardware.\n\n" +
                        "Each individual field — title, username, password, URL, notes, custom " +
                        "fields, TOTP secret — is also bound to its row and column when " +
                        "encrypted, so an attacker who somehow tampered with the database file " +
                        "couldn't swap one field's encrypted blob into another column or " +
                        "another account.",
                )
                FaqItem(
                    question = "Where is my master password stored?",
                    answer = "Nowhere. We don't store it as plaintext, as a hash, or in any " +
                        "other form.\n\n" +
                        "To check whether the password you typed is correct, 1Key keeps a small " +
                        "verifier — a piece of ciphertext that only the right password can " +
                        "decrypt. The verifier itself is stored in EncryptedSharedPreferences, " +
                        "which is encrypted at rest by a key bound to your phone's Android " +
                        "Keystore (the secure hardware enclave, TEE / StrongBox). So even if " +
                        "someone extracted your phone's storage, they couldn't read the verifier " +
                        "blob without live access to the Keystore — meaning offline " +
                        "brute-forcing of your password is not possible.",
                )
                FaqItem(
                    question = "What happens if I forget my master password?",
                    answer = "Your data is unrecoverable. There's no \"forgot password\" link " +
                        "because there's no server and no recovery copy of your key anywhere. " +
                        "Only you can decrypt your vault — that's what makes it truly private.",
                )
                FaqItem(
                    question = "What changed if I had 1Key installed before the recent security update?",
                    answer = "Existing installs are migrated automatically on the first unlock " +
                        "after the app update:\n\n" +
                        "• If your password verifier was using the older PBKDF2 algorithm, it's " +
                        "silently re-derived under Argon2id.\n" +
                        "• Auth metadata (verifier, PIN hash, wrapped vault key) is moved from " +
                        "regular storage into EncryptedSharedPreferences.\n" +
                        "• Existing credentials are re-encrypted in the background under the " +
                        "new HKDF subkey scheme with per-field authentication. Your " +
                        "\"updated_at\" timestamps are preserved.\n\n" +
                        "You don't need to do anything — none of this changes your master " +
                        "password, your vault, or your saved credentials.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Memory & runtime") {
                FaqItem(
                    question = "What does the app keep in memory while running?",
                    answer = "Only the encryption key for the unlocked session and whatever " +
                        "you're actively viewing — the visible list of credentials, the field " +
                        "you have open. Decrypted passwords are never written to disk and " +
                        "they're released from memory when you navigate away or the vault " +
                        "locks. The encryption key itself is dropped from memory the moment " +
                        "the vault locks.",
                )
                FaqItem(
                    question = "Why does unlocking or creating a vault take a few seconds?",
                    answer = "That delay is Argon2id allocating 64 MiB of RAM and running " +
                        "three passes over it to turn your master password into the encryption " +
                        "key. The slowness is the feature — it makes guessing your password too " +
                        "expensive to be practical, even for an attacker with serious hardware.",
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
                        "reporting, no telemetry. You can verify in Android Settings → Apps → " +
                        "1Key → Mobile data & Wi-Fi: data usage is exactly zero, ever.",
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
                    question = "What happens after I enter a wrong PIN or master password?",
                    answer = "Wrong attempts are counted persistently — killing and reopening " +
                        "the app cannot reset them — and trigger escalating cooldowns:\n\n" +
                        "• 3 wrong attempts: 30-second wait\n" +
                        "• 5 wrong attempts: 5-minute wait\n" +
                        "• 10 wrong attempts: 1-hour wait\n\n" +
                        "For PIN specifically: after 3 wrong PINs, the PIN unlock is disabled " +
                        "until you successfully enter your master password. This forces a real " +
                        "identity check before the easier shortcut resumes. Successfully " +
                        "entering the master password resets both counters.\n\n" +
                        "Biometric attempts also have a 3-strike counter that triggers the same " +
                        "fallback — if biometric fails three times in a row, the next unlock " +
                        "has to be the master password.",
                )
                FaqItem(
                    question = "Why does the app sometimes ask for my master password even though biometric works?",
                    answer = "By default, every 48 hours we re-prompt for the master password " +
                        "regardless of biometric or PIN. It's a safety check so you don't " +
                        "gradually forget the password the rest of your security depends on. " +
                        "The interval is configurable in Security settings (48 hours, 3 days, " +
                        "1 week, or 3 weeks).",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Recycle bin & deletion") {
                FaqItem(
                    question = "Why is there a recycle bin? Are bin items still encrypted?",
                    answer = "Accidentally deleting a credential you need is worse than briefly " +
                        "storing one you've deleted. The bin holds deleted items for 30 days " +
                        "by default (configurable: 1 week, 30 days, 6 months, 1 year, or never) " +
                        "so you can restore them. They're encrypted with the same protections " +
                        "as active items — the only difference is they're filtered out of " +
                        "normal views.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Backup & migration") {
                FaqItem(
                    question = "Why doesn't 1Key support automatic backups, and will it ever?",
                    answer = "No, and we don't plan to. Here's why.\n\n" +
                        "Backup files are encrypted with your master password. For a backup to " +
                        "run automatically — especially on the unlock methods most people use " +
                        "day to day, biometric and PIN — 1Key would need to keep your master " +
                        "password on the device in some retrievable form, even if " +
                        "hardware-protected.\n\n" +
                        "That conflicts with our core promise: your master password lives only " +
                        "in your head, never on this device. It's the one secret we keep out of " +
                        "storage entirely, and we're not breaking that for any feature, no " +
                        "matter how convenient it would be.\n\n" +
                        "What we offer instead: one-tap manual backup from Settings. You save " +
                        "the encrypted file wherever suits you — a local folder, a USB drive, " +
                        "or a cloud-synced folder you control. We recommend backing up after " +
                        "big changes (new accounts, password updates, before app updates). The " +
                        "whole flow takes about ten seconds.",
                )
                FaqItem(
                    question = "What's the difference between an encrypted backup and a plain export?",
                    answer = "An encrypted .1key backup is locked with your master password — " +
                        "useless to anyone without it. JSON or CSV exports are plain text — " +
                        "anyone who finds the file can read your passwords. Use encrypted " +
                        "backups unless you're migrating to another app that can't read the " +
                        "encrypted format.\n\n" +
                        "The encrypted backup format also binds the export timestamp and your " +
                        "vault's version counter into the encryption authentication tag, so a " +
                        "backup file can't be silently tampered with, swapped for a different " +
                        "file, or replayed against a newer vault state without detection.",
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
                    answer = "The clipboard is automatically cleared 30 seconds after you " +
                        "copy a password — provided the app is still running and you haven't " +
                        "copied something else in the meantime. On Android 13 and above the " +
                        "copy is also marked sensitive, so the system clipboard preview shows " +
                        "it as ••• instead of the actual value (this depends on your device's " +
                        "clipboard manager honouring the flag — stock Android does).",
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
