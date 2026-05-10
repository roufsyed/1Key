---
title: 1Key Privacy Policy
description: How 1Key handles your data — collected, transmitted, stored, and shared.
---

# 1Key Privacy Policy

_Last updated: May 2026_

## What we don't collect

- No accounts, no email, no signup of any kind.
- No analytics, telemetry, or crash reporting — ever.
- No advertising IDs and no tracking.

## How your data is protected

- **Field-level encryption.** Every credential field — title, username, password, URL, notes, custom fields, TOTP secret — is encrypted separately with AES-256-GCM. Each field's ciphertext is bound to its row and column, so an attacker can't swap encrypted blobs between accounts.
- **Memory-hard password verification.** Your master password is checked using Argon2id (64 MiB memory, 3 passes) — the OWASP-recommended algorithm. Even with the encrypted blobs in hand, brute-forcing is unrealistic on consumer hardware.
- **Verifier kept off-disk-readable.** The password verifier and PIN hash live in EncryptedSharedPreferences, encrypted at rest by an Android Keystore-bound key. Without live access to your phone's Keystore, an attacker can't read the verifier — meaning offline brute-forcing is not possible.
- **Hardware-backed wrapping.** The vault key is wrapped by a key inside the Android Keystore (TEE / StrongBox on supported devices). On Android 9+ unwrapping additionally requires the device to be unlocked.
- **Subkey separation.** Field encryption uses HKDF-derived subkeys, not the raw vault key — so different parts of the data are encrypted under different keys derived for that purpose only.
- **Memory hygiene.** The unwrapped vault key only exists in memory while the vault is unlocked, and is dropped the moment auto-lock fires or you lock manually.
- **Existing installs migrate automatically.** Older vaults using the previous PBKDF2 verifier silently upgrade to Argon2id on the first unlock after the app update.

## Permissions we use

- **Biometric** — to verify your fingerprint or face for app unlock. The biometric data itself never reaches the app; we only receive a yes/no result from Android.
- **Camera** — only for QR-code scanning when you add a new 2FA secret, and OCR credential capture. No photos are taken or stored.
- **Storage** — used only on Android 12 and below for reading and writing backup files. Modern Android uses the system file picker, which doesn't require this permission.

## Permissions we don't request

- No Internet permission — the app cannot make any network request, period. You can verify this in Android Settings → Apps → 1Key → Mobile data & Wi-Fi: data usage is exactly zero, ever.
- No location, contacts, microphone, SMS, or any other personal-data permission.

## Brute-force protection

Wrong master-password and wrong-PIN attempts are tracked in persistent storage (so killing the app cannot reset the counter) and trigger escalating cooldowns: 3 failures → 30 seconds, 5 → 5 minutes, 10 → 1 hour. After 3 wrong PINs, the easier PIN unlock is disabled until you successfully enter the master password.

## Clipboard & screen capture

- Sensitive copies (passwords, 2FA codes) are automatically cleared from the clipboard after 30 seconds.
- On Android 13 and above, those copies are marked sensitive so the system's paste-preview toast doesn't reveal the value.
- Screenshots and screen recordings of the app are blocked by default — including the Recent Apps preview. You can change this in Security settings.
- Revealed passwords cannot be selected from the on-screen text via the long-press menu, so they can only leave the app via the in-app copy button (which routes through the auto-clear path).

## Backups & recycle bin

- Encrypted `.1key` backups are protected by your master password using Argon2id. The export timestamp and vault-version counter are also authenticated, so a backup file can't be tampered with or replayed across vaults without detection.
- Plain JSON or CSV exports are unencrypted text. Treat those files as sensitive: anyone who finds one can read every password inside. 1Key warns clearly and requires master-password confirmation before producing one.
- Items in the recycle bin remain encrypted with the same protections as active items. They're auto-purged after 30 days by default; you can change the retention (1 week, 30 days, 6 months, 1 year, or never) or disable auto-purge entirely in Settings.

## Data deletion

- Uninstalling the app removes all of your data. Android wipes the app's private storage, including your encrypted vault. There is no remote copy of anything.
- Use "Delete Vault" in Settings to wipe all credentials while keeping the app installed. This requires your master password to confirm.

## Background activity

- The app does not run when you are not using it. There are no background services, scheduled jobs, or wake-ups. When you close or background the app, the auto-lock timer fires (immediately by default) and the encryption key is dropped from memory.

## Regulatory

- GDPR, CCPA, and COPPA do not apply to this app because no personal data is collected, processed, or transmitted at any point.
