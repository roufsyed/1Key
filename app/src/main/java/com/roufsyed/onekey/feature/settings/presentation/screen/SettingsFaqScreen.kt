package com.roufsyed.onekey.feature.settings.presentation.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import com.roufsyed.onekey.core.presentation.util.oneKeyTopBarColors
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
                expandedHeight = 56.dp,
                colors = oneKeyTopBarColors(),
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
                    answer = "Every credential field is encrypted on disk with AES-256-GCM - the " +
                        "same authenticated cipher used by Signal, WhatsApp, and modern TLS. " +
                        "Authenticated means tampering with the encrypted bytes is detected on " +
                        "decryption rather than producing scrambled output.\n\n" +
                        "The encryption key is derived from your master password using Argon2id " +
                        "- a memory-hard algorithm that allocates 64 MiB of RAM per attempt. " +
                        "Memory-hard means it can't be cheaply parallelised on GPUs or rented " +
                        "cloud farms the way older algorithms (like PBKDF2) can. Even with your " +
                        "encrypted blobs in hand, brute-forcing a decent password is unrealistic " +
                        "on consumer hardware.\n\n" +
                        "Each individual field - title, username, password, URL, notes, custom " +
                        "fields, TOTP secret - is also bound to its row and column when " +
                        "encrypted, so an attacker who somehow tampered with the database file " +
                        "couldn't swap one field's encrypted blob into another column or " +
                        "another account.",
                )
                FaqItem(
                    question = "Where is my master password stored?",
                    answer = "Nowhere. We don't store it as plaintext, as a hash, or in any " +
                        "other form.\n\n" +
                        "To check whether the password you typed is correct, 1Key keeps a small " +
                        "verifier - a piece of ciphertext that only the right password can " +
                        "decrypt. The verifier itself is stored in EncryptedSharedPreferences, " +
                        "which is encrypted at rest by a key bound to your phone's Android " +
                        "Keystore (the secure hardware enclave, TEE / StrongBox). So even if " +
                        "someone extracted your phone's storage, they couldn't read the verifier " +
                        "blob without live access to the Keystore - meaning offline " +
                        "brute-forcing of your password is not possible.",
                )
                FaqItem(
                    question = "How is my master password protected on this device?",
                    answer = "1Key never stores your master password. To unlock the vault, we wrap " +
                        "the encryption key with another key that lives inside the Android " +
                        "Keystore - a system service whose private keys regular apps, " +
                        "including 1Key, cannot read directly.\n\n" +
                        "Android offers two tiers of hardware isolation for that wrapping key, " +
                        "and both are full-strength protection:\n\n" +
                        "TEE (Trusted Execution Environment). A separate, secure-only mode of " +
                        "the main CPU. The key lives there; the rest of Android, our app " +
                        "included, cannot reach into it. Every modern Android phone has a TEE.\n\n" +
                        "StrongBox. A dedicated tamper-resistant chip, physically separate from " +
                        "the main CPU. Recent Pixel and many flagship phones have StrongBox. " +
                        "When it is available, 1Key places the wrapping key in StrongBox " +
                        "automatically. StrongBox is a stronger qualifier than TEE because " +
                        "even a compromise of the main CPU does not give an attacker the key.\n\n" +
                        "You can see which tier this device uses in Security settings, under " +
                        "Hardware key isolation. Either way, your master password itself is " +
                        "never stored - only a small verifier blob that the Keystore wraps, " +
                        "and even that lives inside EncryptedSharedPreferences. Offline " +
                        "brute-forcing of your password is not possible without live access " +
                        "to this phone's Keystore.\n\n" +
                        "On Android 9 and above, the wrapping key additionally requires the " +
                        "device screen to be unlocked before it can be used. So even with " +
                        "the phone in hand, an attacker cannot unwrap the vault key on a " +
                        "locked device.",
                )
                FaqItem(
                    question = "What happens if I forget my master password?",
                    answer = "Your data is unrecoverable. There's no \"forgot password\" link " +
                        "because there's no server and no recovery copy of your key anywhere. " +
                        "Only you can decrypt your vault - that's what makes it truly private.\n\n" +
                        "If you had Secret Key enabled when you took an encrypted backup, you " +
                        "also need that backup's Emergency Kit (the printed PDF or scanned QR) " +
                        "to restore the file - the master password alone is not enough on a " +
                        "Secret-Key-protected backup. The kit lives outside the device, so " +
                        "make sure you have a copy stored somewhere safe before relying on it.",
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
                        "You don't need to do anything - none of this changes your master " +
                        "password, your vault, or your saved credentials.",
                )
                FaqItem(
                    question = "Encryption strength - what do these settings mean?",
                    answer = "Argon2id is the memory-hard function that turns your master " +
                        "password into the AES-256 key that unlocks your vault. \"Memory-hard\" " +
                        "means it needs a large block of RAM to run, not just CPU - that makes " +
                        "it many times more expensive for an attacker to brute-force on GPU " +
                        "farms or rented cloud compute than older algorithms like PBKDF2.\n\n" +
                        "1Key ships four presets you can choose in Settings > Security > " +
                        "Encryption strength. The default (\"Standard\") is OWASP's 2023 " +
                        "interactive-auth recommendation and is what every fresh install starts " +
                        "with. Stronger presets cost more time and RAM on every unlock, so 1Key " +
                        "only offers presets your device can run smoothly.\n\n" +
                        "Preset         Params (mem/iter)   Min RAM   Unlock on rec. device   Attacker cost*\n" +
                        "Standard       64 MiB / 3 passes    any       ~0.3-0.6s              ~$200,000 / year\n" +
                        "Standard-plus  64 MiB / 8 passes    any       ~0.8-1.5s              ~$530,000 / year\n" +
                        "Hardened       128 MiB / 4 passes   4 GB+     ~1.0-2.0s              ~$1.0M / year\n" +
                        "Maximum        128 MiB / 8 passes   6 GB+     ~2.0-4.0s              ~$2.1M / year\n\n" +
                        "*Cost to crack an 8-character mixed-case + digit password on a 10-GPU " +
                        "rig at typical 2026 cloud prices ($10/hr). Estimates assume full " +
                        "keyspace brute-force; targeted attacks against weak passwords cost " +
                        "less.\n\n" +
                        "All four presets are infeasible to crack against a strong master " +
                        "password (12+ characters with mixed cases, digits, and a symbol or " +
                        "two) - the table above shows the gradient against a weaker password. " +
                        "The cost differences matter most if you suspect your password is on " +
                        "the weak side, or if your threat model includes a well-funded " +
                        "adversary with physical access to your device. Hardened roughly " +
                        "quintuples the attacker cost vs. Standard at the price of about 2x " +
                        "longer unlocks.\n\n" +
                        "Advanced users can also pick custom Argon2id parameters via \"Custom " +
                        "(advanced)\" in the picker. Parallelism is locked to 1: higher " +
                        "parallelism reduces attacker cost more than defender cost on " +
                        "commodity hardware and is not recommended.\n\n" +
                        "Changing the preset re-derives the verifier under the new Argon2id " +
                        "parameters. Your vault encryption key is wrapped by the Android " +
                        "Keystore (not by your password), so the migration does NOT re-encrypt " +
                        "your credentials - their AES-256-GCM ciphertext is unchanged. " +
                        "Migration is transactional: if anything fails part-way, the previous " +
                        "preset stays active and your vault remains unlocked.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Memory & runtime") {
                FaqItem(
                    question = "What does the app keep in memory while running?",
                    answer = "Only the encryption key for the unlocked session and whatever " +
                        "you're actively viewing - the visible list of credentials, the field " +
                        "you have open. Decrypted passwords are never written to disk and " +
                        "they're released from memory when you navigate away or the vault " +
                        "locks. The encryption key itself is dropped from memory the moment " +
                        "the vault locks.",
                )
                FaqItem(
                    question = "Why does unlocking or creating a vault take a few seconds?",
                    answer = "That delay is Argon2id allocating 64 MiB of RAM and running " +
                        "three passes over it to turn your master password into the encryption " +
                        "key. The slowness is the feature - it makes guessing your password too " +
                        "expensive to be practical, even for an attacker with serious hardware.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Secret Key") {
                FaqItem(
                    question = "What is Secret Key, and why does 1Key recommend it?",
                    answer = "Secret Key is a 128-bit random value that 1Key mixes into your " +
                        "master password before deriving the vault encryption key. It is " +
                        "generated on this device, stored only on this device (wrapped inside " +
                        "the Android Keystore), and printed onto your Emergency Kit so you can " +
                        "keep an offline copy.\n\n" +
                        "What it buys you: even if an encrypted backup of your vault is " +
                        "leaked, an attacker needs BOTH your master password AND your Secret " +
                        "Key to decrypt it. A guess at your master password alone - no matter " +
                        "how much GPU time the attacker has - cannot brute-force a " +
                        "Secret-Key-protected backup, because the 128 bits of Secret Key " +
                        "entropy are not in the file.\n\n" +
                        "This is the same model 1Password has used for years. New 1Key " +
                        "installs default it on; existing installs can turn it on in " +
                        "Settings > Security > Secret Key.",
                )
                FaqItem(
                    question = "What is the Emergency Kit, and where should I keep it?",
                    answer = "The Emergency Kit is a two-page A4 PDF that prints your Secret " +
                        "Key in large monospace, a QR code, and a labelled line where you can " +
                        "write your master password. It is generated entirely on-device and " +
                        "saved to a folder you pick - 1Key does not see the file again after " +
                        "it is written.\n\n" +
                        "Keep it offline. A printed copy in a safe, a locked drawer, or a " +
                        "fireproof file folder is the intended use. A digital copy on a USB " +
                        "stick that you keep separately from your phone also works. What you " +
                        "want to avoid is the same place as the encrypted backup file - the " +
                        "whole point of Secret Key is that a leak of the backup AND a leak of " +
                        "the kit have to happen together to expose the vault.",
                )
                FaqItem(
                    question = "Is Secret Key the same as two-factor authentication (2FA)?",
                    answer = "No. 2FA usually means a second login factor (a code from an " +
                        "authenticator app, a hardware key) that you present to a server when " +
                        "you sign in. 1Key has no server and no sign-in - there is nothing to " +
                        "present a 2FA token to.\n\n" +
                        "Secret Key is a second secret you must possess to derive the vault " +
                        "encryption key, on top of the master password you must know. In " +
                        "everyday terms it plays a similar role to 2FA (something you have, " +
                        "in addition to something you know), but the mechanism is " +
                        "cryptographic, not protocol-based, and it protects offline files - " +
                        "not server logins.",
                )
                FaqItem(
                    question = "What happens to my existing backups when I enable or disable Secret Key?",
                    answer = "Existing backup files are never touched. Backups are immutable " +
                        "snapshots: a backup written before you turned Secret Key on remains " +
                        "decryptable with just your master password forever, and a backup " +
                        "written after you turned it on always needs both master password and " +
                        "Secret Key to decrypt.\n\n" +
                        "If you disable Secret Key later, future backups go back to " +
                        "master-password-only, but any older Secret-Key-protected backups you " +
                        "still keep continue to require the kit. Rotating the Secret Key has " +
                        "the same property: old backups stay decryptable with the old kit, new " +
                        "backups need the new kit. That is why 1Key prompts you to save a " +
                        "fresh kit on every rotate.",
                )
                FaqItem(
                    question = "Why do I need the Emergency Kit even though I am not changing phones?",
                    answer = "Two reasons. First, the Secret Key lives inside this phone's " +
                        "Keystore - if the phone is lost, broken, factory-reset, or the app is " +
                        "uninstalled, the Keystore-wrapped copy goes with it. The Emergency " +
                        "Kit is your only path back into a Secret-Key-protected backup in " +
                        "that case.\n\n" +
                        "Second, even on this phone, the Secret Key is bound to a Keystore " +
                        "alias that can be invalidated by certain device events (factory " +
                        "reset, secure-hardware key wipe, some OS upgrades on a small number " +
                        "of devices). The kit is the offline copy that survives any " +
                        "on-device event. Treat it like a wallet recovery phrase: you may " +
                        "never need it, but you should never not have it.",
                )
                FaqItem(
                    question = "What happens if I lose my Emergency Kit but still have my master password?",
                    answer = "You can still unlock and use 1Key on this phone normally. The " +
                        "Secret Key is on this device's Keystore; the kit is the offline " +
                        "backup of the same value. As long as the phone is intact, the " +
                        "in-phone copy is what 1Key reads on every unlock.\n\n" +
                        "What you lose without the kit is the ability to restore a " +
                        "Secret-Key-protected backup on any other device. Generate a fresh " +
                        "kit as soon as possible: open Settings > Security > Secret Key and " +
                        "tap Save Emergency Kit. That writes a new PDF with the same Secret " +
                        "Key on it, so the lost copy is no longer the only one.\n\n" +
                        "If you want to invalidate the lost kit entirely (e.g. you suspect " +
                        "the document was found), rotate the Secret Key from the same " +
                        "screen. Rotating mints a fresh Secret Key, makes the lost kit " +
                        "useless on any future backup, and prompts you to save the new kit " +
                        "in its place. Existing backups stay readable with the old kit only.",
                )
                FaqItem(
                    question = "What happens if I lose my master password but still have my Emergency Kit?",
                    answer = "Your data is still unrecoverable. The Secret Key only " +
                        "supplements the master password; it never replaces it. 1Key derives " +
                        "the vault encryption key from both inputs combined, and the master " +
                        "password is the one that can be guessed by a human - the kit alone " +
                        "carries no information about it.\n\n" +
                        "There is no \"forgot password\" path. The kit is for the OTHER half " +
                        "of the equation: a leaked backup file alone (without the kit) " +
                        "cannot be brute-forced. The reverse - kit alone, without the " +
                        "password - cannot decrypt anything either.",
                )
                FaqItem(
                    question = "What does rotating my Secret Key do, and when should I rotate?",
                    answer = "Rotating mints a fresh 128-bit Secret Key, drops the old one " +
                        "from this phone, re-derives the verifier under the new key, and " +
                        "asks you to save a new Emergency Kit. No grace period and no second " +
                        "slot - the old key is gone the moment the rotate completes.\n\n" +
                        "Rotate when you suspect the old Emergency Kit has leaked or might " +
                        "leak (left in an old shared workspace, exposed in a cloud-drive " +
                        "screenshot, etc.), or as a routine refresh on a schedule that fits " +
                        "your threat model. Future backups will need the new kit; existing " +
                        "backups continue to require the old kit, so do not throw the old " +
                        "kit away as long as you might want to restore from a pre-rotate " +
                        "backup. 1Key shows a persistent banner on Settings until you save " +
                        "the new kit after rotating, so you cannot forget the step.",
                )
                FaqItem(
                    question = "Where on my phone is the Secret Key stored?",
                    answer = "The Secret Key is wrapped (encrypted) by a key inside the " +
                        "Android Keystore and the wrapped blob is stored in 1Key's " +
                        "EncryptedSharedPreferences. The wrapping key never leaves the " +
                        "Keystore - regular apps, 1Key included, cannot read it.\n\n" +
                        "1Key requests StrongBox isolation for the wrapping key on devices " +
                        "that support it (a dedicated secure chip, separate from the main " +
                        "CPU) and falls back to TEE (Trusted Execution Environment, a " +
                        "secure-only mode of the main CPU) on devices that do not. Both are " +
                        "full-strength protection. You can see which tier this device uses " +
                        "in Settings > Security > Hardware key isolation.",
                )
                FaqItem(
                    question = "Will Secret Key still work if I set up 1Key on a phone without StrongBox?",
                    answer = "Yes. Every modern Android device has a TEE (Trusted Execution " +
                        "Environment), which is the standard hardware-backed key isolation " +
                        "tier on Android. 1Key automatically falls back to TEE when " +
                        "StrongBox is not available on the device, and the Secret Key feature " +
                        "is fully supported in both cases.\n\n" +
                        "StrongBox is an additional qualifier on devices that have a " +
                        "dedicated secure chip - it does not change WHAT the Secret Key " +
                        "protects, only HOW it is protected at rest on the device. The kit " +
                        "you print is the same format on every device, and a kit saved on a " +
                        "TEE phone can be used to restore a backup onto a StrongBox phone " +
                        "(and vice versa).",
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
                        "uninstall - your vault, your settings, everything. Export an encrypted " +
                        "backup first if you want to keep it.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Privacy") {
                FaqItem(
                    question = "Does the app talk to any servers?",
                    answer = "No. The app has no internet permission, no analytics, no crash " +
                        "reporting, no telemetry. You can verify in Android Settings → Apps → " +
                        "1Key > Mobile data & Wi-Fi: data usage is exactly zero, ever.",
                )
                FaqItem(
                    question = "Can the developer (or anyone else) see my passwords?",
                    answer = "No. Your data never leaves your device, so there's literally no " +
                        "path for anyone - developer, Google, your network - to access it. " +
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
                    answer = "Wrong attempts are counted persistently - killing and reopening " +
                        "the app cannot reset them - and trigger escalating cooldowns:\n\n" +
                        "• 3 wrong attempts: 30-second wait\n" +
                        "• 5 wrong attempts: 5-minute wait\n" +
                        "• 10 wrong attempts: 1-hour wait\n\n" +
                        "For PIN specifically: after 3 wrong PINs, the PIN unlock is disabled " +
                        "until you successfully enter your master password. This forces a real " +
                        "identity check before the easier shortcut resumes. Successfully " +
                        "entering the master password resets both counters.\n\n" +
                        "Biometric attempts also have a 3-strike counter that triggers the same " +
                        "fallback - if biometric fails three times in a row, the next unlock " +
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
                        "as active items - the only difference is they're filtered out of " +
                        "normal views.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Backup & migration") {
                FaqItem(
                    question = "Does 1Key support automatic backups?",
                    answer = "Yes, with a clear constraint. Turn on Sync in Settings - it writes " +
                        "an encrypted backup of your vault every time you unlock by typing your " +
                        "master password. Biometric and PIN unlocks do NOT trigger a backup, by " +
                        "design: those unlock methods do not hand the app your password, and we " +
                        "will not invent a workaround that stores a copy. See the Sync section " +
                        "below for the full picture.\n\n" +
                        "If you'd rather back up explicitly, the manual Export now button in " +
                        "Settings does the same thing on demand. Either path requires your " +
                        "master password.",
                )
                FaqItem(
                    question = "What's the difference between an encrypted backup and a plain export?",
                    answer = "An encrypted .1key backup is locked with your master password - " +
                        "useless to anyone without it. JSON or CSV exports are plain text - " +
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
            FaqGroup("Sync") {
                FaqItem(
                    question = "What does Sync actually do?",
                    answer = "It writes an encrypted backup of your vault to a folder you pick, " +
                        "each time you unlock the app by typing your master password. You see a " +
                        "small \"Syncing...\" bar at the top, then \"Synced\" with a tick, then " +
                        "it disappears. That is the whole feature.",
                )
                FaqItem(
                    question = "Why does Sync only run on master-password unlock?",
                    answer = "Backups have to be encrypted with your master password. When you " +
                        "unlock with biometric or PIN, the app does not have your master " +
                        "password - by design, we never store it. So there is nothing to " +
                        "encrypt a backup with on those unlocks, and we will not invent a " +
                        "workaround that stores a copy. Type your master password to trigger " +
                        "a sync, or use Export now from Settings if you need an on-demand " +
                        "backup.",
                )
                FaqItem(
                    question = "Does enabling Sync change anything about how my master password is stored?",
                    answer = "No. The app uses your master password only in the brief moment " +
                        "between you typing it to unlock the vault and the backup file being " +
                        "written. As soon as the encryption finishes, the memory holding the " +
                        "password is zeroed - the same way the existing manual export works " +
                        "today. Nothing about Sync requires us to keep your password anywhere " +
                        "on the device.",
                )
                FaqItem(
                    question = "Where does the backup go?",
                    answer = "A folder you pick when you turn the feature on. It can be local " +
                        "storage, a USB drive, or a folder synced by another app (Google " +
                        "Drive, Dropbox, Nextcloud, OneDrive, etc.). The file is encrypted " +
                        "with your master password before it leaves the app, so the cloud " +
                        "provider sees only random bytes. The filename is fixed: " +
                        "vault-backup.1key.",
                )
                FaqItem(
                    question = "If I put backups in a cloud folder, can the cloud provider read my passwords?",
                    answer = "No. The file is AES-256-GCM ciphertext under an Argon2id-derived " +
                        "key from your master password. Without the password, the file is " +
                        "indistinguishable from random bytes. What the cloud provider can see " +
                        "is metadata: the file's name, its size, and the time you uploaded " +
                        "it. If those signals matter to you (for example, the upload cadence " +
                        "reveals when you typically use the app), keep backups on local " +
                        "storage or a USB drive instead.",
                )
                FaqItem(
                    question = "Why does each sync overwrite the previous file? Can I keep a history?",
                    answer = "Sync is meant as a continuously fresh copy, not a version log. " +
                        "Each sync replaces the previous vault-backup.1key safely - we write " +
                        "to a temp file first and only swap it in once the full file is on " +
                        "disk. If you need a history (e.g. before a big change or before " +
                        "changing your master password), do an Export now to a different " +
                        "filename. Most cloud providers (Google Drive, Dropbox) also keep " +
                        "file version history on their side for at least 30 days, which gives " +
                        "you a rollback if you need one.",
                )
                FaqItem(
                    question = "Does Sync slow down unlock?",
                    answer = "No. The vault unlocks and the UI is responsive immediately. " +
                        "Sync runs in the background; only the small bar at the top shows it " +
                        "is happening. A typical sync takes one to three seconds depending on " +
                        "your vault size and storage speed.",
                )
                FaqItem(
                    question = "What if a sync fails?",
                    answer = "You'll see an amber \"Backup didn't save\" bar that auto-dismisses " +
                        "after a few seconds. Tap the bar to see the exact reason in Settings " +
                        "- common causes are the chosen folder being unreachable (cloud app " +
                        "uninstalled, USB drive removed) or the device being out of free " +
                        "space. Your existing vault-backup.1key from the previous sync stays " +
                        "intact - a failed sync never corrupts the previous good one because " +
                        "we write to a temp file first.",
                )
                FaqItem(
                    question = "Does Sync use the internet from 1Key?",
                    answer = "No. 1Key still has no INTERNET permission - that has not " +
                        "changed. We write the encrypted file to a folder on your device. If " +
                        "that folder happens to be one another app synchronises to the cloud, " +
                        "the upload is done by that other app, not us. We never see the " +
                        "network.",
                )
                FaqItem(
                    question = "Can I restore from a Sync backup on a different device?",
                    answer = "Yes. The Sync file is the same format as the manual encrypted " +
                        "backup. Install 1Key on the new device, choose \"Restore from " +
                        "backup\" during setup, point at the file, and type your master " +
                        "password. Done.",
                )
                FaqItem(
                    question = "What happens to old sync backups if I change my master password?",
                    answer = "Each sync writes a fresh backup encrypted with whatever the " +
                        "master password is at that moment. After you change your password, " +
                        "the next time you unlock with the new one and trigger a sync, the " +
                        "file gets overwritten with one encrypted under the new password. " +
                        "Until that next sync runs, the existing vault-backup.1key is still " +
                        "locked with the old password - if you still remember it, you can " +
                        "restore from that file. We do not re-encrypt or migrate older " +
                        "backups in place; only the next sync rotates them.",
                )
                FaqItem(
                    question = "Are deleted items in the recycle bin included in the backup?",
                    answer = "Yes. Sync backs up both your active credentials and anything " +
                        "currently in the recycle bin. Restoring from a backup brings the bin " +
                        "back in the same state it was at sync time - so anything you deleted " +
                        "but had not yet purged will reappear in the bin, ready to be " +
                        "permanently deleted or restored from there. This matches how the " +
                        "manual Export now flow already works; Sync is the same file format " +
                        "and the same data scope.",
                )
                FaqItem(
                    question = "What happens to my backup file if I uninstall 1Key?",
                    answer = "It stays exactly where you put it. The file is yours - 1Key " +
                        "only writes to it, never reaches in to delete or move it. If you " +
                        "reinstall and pick the same SAF folder, the next sync overwrites the " +
                        "existing file. If you reinstall and want to restore your vault, " +
                        "choose Restore from backup during setup, point at the file, and " +
                        "type your master password.",
                )
                FaqItem(
                    question = "Can I open the sync backup with anything other than 1Key?",
                    answer = "No, by design. The file is AES-256-GCM ciphertext under an " +
                        "Argon2id-derived key from your master password. We don't ship a " +
                        "separate decryption tool. To read it, install 1Key on any device, " +
                        "use Restore from backup, and enter your master password. The file " +
                        "format is the same as a manual encrypted backup - both restore " +
                        "through the exact same flow.",
                )
                FaqItem(
                    question = "Can I turn Sync off later?",
                    answer = "Yes, any time. Turning it off stops new backups and releases " +
                        "the folder permission. The existing vault-backup.1key file is left " +
                        "where it is - we do not delete your file. You can keep it, move it, " +
                        "or delete it yourself.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Autofill") {
                FaqItem(
                    question = "How does 1Key decide which credentials to suggest while filling a form?",
                    answer = "When you tap into a username or password field, 1Key looks at the " +
                        "website's address and compares it to the URL field on each of your " +
                        "saved credentials. If the addresses match exactly, the credential " +
                        "shows up as a chip above your keyboard. Favourites appear first.\n\n" +
                        "We match on the exact host: accounts.google.com and google.com are " +
                        "treated as different sites. This is on purpose. A login form on " +
                        "g00gle.com (a typosquat) will never receive a chip for your real " +
                        "Google credentials, even if the page looks identical.",
                )
                FaqItem(
                    question = "Why isn't 1Key suggesting my credential on this site?",
                    answer = "Three common reasons:\n\n" +
                        "- The credential's URL field is empty. 1Key matches on URL only. Open " +
                        "the credential in 1Key and paste the site's address into the URL field.\n\n" +
                        "- The URL is a different host. mail.google.com and accounts.google.com " +
                        "are different addresses; a credential saved for one will not " +
                        "auto-suggest on the other.\n\n" +
                        "- The vault is locked. You'll see only a single \"Unlock 1Key to fill\" " +
                        "chip. Tap it, unlock with biometric, PIN, or password, and the " +
                        "matching credentials appear next.\n\n" +
                        "If you're sure you have the credential, tap the \"Search 1Key\" chip " +
                        "in the row and find it manually.",
                )
                FaqItem(
                    question = "What does the \"Search 1Key\" chip do?",
                    answer = "It's the catch-all option. Tapping it opens 1Key's search inside " +
                        "the autofill flow, where you can pick any credential from your vault - " +
                        "even ones whose stored URL doesn't match the page you're on.\n\n" +
                        "Before the credential fills, you'll see a \"Fill from a different " +
                        "site?\" confirmation pane. That extra step is deliberate: it prevents " +
                        "a phishing site from quietly receiving credentials that were saved for " +
                        "the real site.",
                )
                FaqItem(
                    question = "What is the \"Save URL on cross-host fills\" setting?",
                    answer = "An opt-in convenience.\n\n" +
                        "When off (the default), the \"Fill from a different site?\" pane just " +
                        "fills your form once. The credential's URL is unchanged, and you'll " +
                        "see the same prompt again on the next visit.\n\n" +
                        "When on, the pane adds a checkbox: \"Save [site] to this credential.\" " +
                        "Tick it before tapping Fill anyway, and 1Key writes the current site's " +
                        "URL to the credential. Future visits will then suggest the credential " +
                        "as a normal chip without the cross-host prompt.\n\n" +
                        "It's off by default because 1Key cannot verify whether a site is " +
                        "genuine. If you save the URL of a phishing site to a credential, " +
                        "future visits to that phishing site will auto-fill without warning, " +
                        "and the real site will no longer suggest the credential. The " +
                        "disclaimer dialog you see when enabling the setting walks through " +
                        "this before you turn it on.",
                )
                FaqItem(
                    question = "Does my password leave my phone when autofill runs?",
                    answer = "No. Autofill runs entirely on-device.\n\n" +
                        "The operating system gives 1Key the form's field layout when you tap a " +
                        "username or password box. 1Key reads its own encrypted vault, matches " +
                        "a credential, and returns the username and password to the form. The " +
                        "values go only to the foreground app you tapped - the browser or the " +
                        "app showing the form - never to a server.\n\n" +
                        "This is the same guarantee as the rest of 1Key: the app has no " +
                        "INTERNET permission in its Android manifest, so no data can be sent " +
                        "off the device.",
                )
            }

            Spacer(Modifier.height(8.dp))
            FaqGroup("Clipboard") {
                FaqItem(
                    question = "What happens when I copy a password to my clipboard?",
                    answer = "The clipboard is automatically cleared 30 seconds after you " +
                        "copy a password - provided the app is still running and you haven't " +
                        "copied something else in the meantime. On Android 13 and above the " +
                        "copy is also marked sensitive, so the system clipboard preview shows " +
                        "it as ••• instead of the actual value (this depends on your device's " +
                        "clipboard manager honouring the flag - stock Android does).",
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
