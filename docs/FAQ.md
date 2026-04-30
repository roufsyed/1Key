# 1Key — Frequently Asked Questions

## Encryption & passwords

### How are my passwords encrypted?

With AES-256-GCM — the same algorithm used by banking apps and government systems. The encryption key is derived from your master password using PBKDF2 with 310,000 iterations, deliberately slow so brute-forcing is impractical.

### Where is my master password stored?

Nowhere in cleartext. We do store a small password verifier — an encrypted token only the correct password can decrypt — so we can confirm a password attempt without persisting the password itself. The vault key is encrypted by the Android Keystore and stored in that wrapped form on disk; the unwrapped, usable form lives in memory only while the vault is unlocked, and is dropped the moment the vault locks.

### What happens if I forget my master password?

Your data is unrecoverable. There's no "forgot password" link because there's no server and no recovery copy of your key anywhere. Only you can decrypt your vault — that's what makes it truly private.

## Memory & runtime

### What does the app keep in memory while running?

Only the encryption key for the unlocked session and whatever you're actively viewing — the visible list of credentials, the field you have open. Decrypted passwords are never written to disk and they're released from memory when you navigate away or the vault locks; the encryption key itself is dropped from memory the moment the vault locks.

### Why does unlocking or creating a vault take a few seconds?

That delay is PBKDF2 running 310,000 rounds of cryptographic work to turn your master password into the encryption key. Slowness here is the feature — it's what makes guessing your password too expensive to be practical.

## Storage & device

### Where is my data stored?

Locally on this device, in an encrypted database inside the app's private storage. Other apps on your phone can't read it. Nothing leaves the device unless you explicitly export a backup.

### What happens to my data if I uninstall the app?

It goes with the app. Android removes the app's private storage on uninstall — your vault, your settings, everything. Export an encrypted backup first if you want to keep it.

## Privacy

### Does the app talk to any servers?

No. The app has no internet permission, no analytics, no crash reporting, no telemetry. You can verify in Android Settings → Apps → 1Key → Mobile data & Wi-Fi: data usage is exactly zero, ever.

### Can the developer (or anyone else) see my passwords?

No. Your data never leaves your device, so there's literally no path for anyone — developer, Google, your network — to access it. Decryption requires your master password, which only you have.

### Why does the app block screenshots by default?

Screenshots, screen recordings, and the Recent Apps preview can all capture passwords on screen. Blocking them prevents accidental leaks (or someone glancing at your phone). You can turn the block off in Security settings if you really need screenshots.

## Biometric & re-authentication

### Why does enabling biometric require my master password?

Biometric unlock provides the same full access as your master password. We ask for the master password once when you enable biometric, to make sure it's really you turning that shortcut on.

### Why does biometric get paused after several wrong master-password attempts?

Three wrong attempts in a row could mean someone other than you is trying to get in. Pausing biometric forces the next unlock to use the master password — that proves it's really you before the easier biometric path resumes. After one successful master-password unlock, biometric is back to normal.

### Why does the app sometimes ask for my master password even though biometric works?

By default, every 48 hours we re-prompt for the master password regardless of biometric or PIN. It's a safety check so you don't gradually forget the password the rest of your security depends on. The interval is configurable in Security settings.

## Recycle bin & deletion

### Why is there a recycle bin? Are bin items still encrypted?

Accidentally deleting a credential you need is worse than briefly storing one you've deleted. The bin holds deleted items for 30 days (configurable) so you can restore them. They're encrypted with the same algorithm as active items — the only difference is they're filtered out of normal views.

## Backup & migration

### Why doesn't 1Key support automatic backups, and will it ever?

No, and we don't plan to. Here's why.

Backup files are encrypted with your master password. For a backup to run automatically — especially on the unlock methods most people use day to day, biometric and PIN — 1Key would need to keep your master password on the device in some retrievable form, even if hardware-protected.

That conflicts with our core promise: your master password lives only in your head, never on this device. It's the one secret we keep out of storage entirely, and we're not breaking that for any feature, no matter how convenient it would be.

What we offer instead: one-tap manual backup from Settings → Backup & Recycle Bin. You save the encrypted file wherever suits you — a local folder, a USB drive, or a cloud-synced folder you control. We recommend backing up after big changes (new accounts, password updates, before app updates). The whole flow takes about ten seconds.

### What's the difference between an encrypted backup and a plain export?

An encrypted `.1key` backup is locked with your master password — useless to anyone without it. JSON or CSV exports are plain text — anyone who finds the file can read your passwords. Use encrypted backups unless you're migrating to another app that can't read the encrypted format.

### Can I move my vault to another device?

Yes. Export an encrypted backup from this device, install 1Key on the new one, and choose "Restore from backup" during setup. The backup password becomes your new master password.

## Clipboard

### What happens when I copy a password to my clipboard?

The clipboard is automatically cleared after 30 seconds so a copied password doesn't sit there waiting to be picked up by another app.
