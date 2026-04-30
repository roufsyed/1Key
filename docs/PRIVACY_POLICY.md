# 1Key Privacy Policy

_Last updated: April 2026_

## What we don't collect

- No accounts, no email, no signup of any kind.
- No analytics, telemetry, or crash reporting — ever.
- No advertising IDs and no tracking.

## How your data is protected

- All credentials are encrypted with AES-256-GCM, the same algorithm protecting banking apps and government systems.
- Your master password is run through PBKDF2 with 310,000 iterations to derive cryptographic material — deliberately slow, so brute-forcing is impractical.
- Your master password is never written to disk in cleartext. We do store a small password verifier (an encrypted token only the correct password can decrypt) so we can confirm a password attempt without persisting the password itself.
- The vault key is encrypted by the Android Keystore (hardware-backed on supported devices) and stored in that wrapped form on disk. The unwrapped, usable form lives in memory only while the vault is unlocked, and is dropped from memory the moment the vault locks.
- 2FA codes (TOTP secrets) are encrypted with the same vault key as your passwords.

## Permissions we use

- **Biometric** — to verify your fingerprint or face for app unlock. The biometric data itself never reaches the app; we only receive a yes/no result from Android.
- **Camera** — only for QR-code scanning when you add a new 2FA secret. No photos are taken or stored.
- **Storage** — used only on older Android versions for reading and writing backup files. Modern Android uses the system file picker, which doesn't require this permission.

## Permissions we don't request

- No Internet permission — the app cannot make any network request, period. You can verify this in Android Settings → Apps → 1Key → Mobile data & Wi-Fi: data usage is exactly zero, ever.
- No location, contacts, microphone, SMS, or any other personal-data permission.

## Clipboard & screen capture

- Sensitive copies (passwords, 2FA codes) are automatically cleared from the clipboard after 30 seconds.
- On Android 13 and above, those copies are marked sensitive so the system's paste-preview toast doesn't reveal the value.
- Screenshots and screen recordings of the app are blocked by default — including the Recent Apps preview. You can change this in Security settings.
- Revealed passwords cannot be selected from the on-screen text via the long-press menu, so they can only leave the app via the in-app copy button (which routes through the auto-clear path).

## Backups & recycle bin

- Encrypted `.1key` backups are protected by your master password. Without that password the backup file is mathematically useless.
- Plain JSON or CSV exports are unencrypted text. Treat those files as sensitive: anyone who finds one can read every password inside.
- Items in the recycle bin remain encrypted with the same algorithm as active items. They're auto-purged after 30 days by default; you can change the retention or disable the bin entirely in Settings.

## Data deletion

- Uninstalling the app removes all of your data. Android wipes the app's private storage, including your encrypted vault. There is no remote copy of anything.
- Use "Delete Vault" in Settings to wipe all credentials while keeping the app installed. This requires your master password to confirm.

## Background activity

- The app does not run when you are not using it. There are no background services, scheduled jobs, or wake-ups. When you close or background the app, the auto-lock timer fires and the encryption key is dropped from memory.

## Regulatory

- GDPR, CCPA, and COPPA do not apply to this app because no personal data is collected, processed, or transmitted at any point.
