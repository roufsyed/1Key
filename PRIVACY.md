# Privacy Policy - 1Key

**Last updated: May 2026**

1Key is a password manager built on a simple principle: your data belongs to you, not us. This document explains exactly what the app does and does not do with your information - in plain English, not legal boilerplate.

---

## The short version

1Key does not collect, transmit, store, or share any of your data. Ever. There is no server, no account, no analytics, and no network connection of any kind. Everything stays on your device.

---

## What data does 1Key store?

1Key stores the following data **locally on your device only**:

- Your saved credentials (titles, usernames, passwords, URLs, notes, custom fields)
- TOTP / 2FA secrets for two-factor authentication codes
- App settings and preferences (theme, auto-lock timeout, biometric preference)
- Tags and categories you create

Everything except a small amount of metadata (timestamps, sort order, tag membership) is encrypted on disk. The encryption key is wrapped by the Android Keystore - meaning it lives in your phone's secure hardware and can only be unwrapped from inside the app, with your master password.

---

## What data does 1Key NOT collect?

- No analytics or usage statistics
- No crash reports or error logs sent anywhere
- No device identifiers (IMEI, advertising ID, etc.)
- No IP addresses
- No contact lists, location data, or anything unrelated to a password manager
- No "anonymised" or "aggregated" data of any kind

There is nothing to opt out of, because nothing is collected in the first place.

---

## Does 1Key connect to the internet?

No. The app does not have the `INTERNET` permission in its manifest. This is not a setting that can be toggled - it is architecturally impossible for 1Key to make a network request. Your device's operating system enforces this.

---

## How your master password is protected

Your master password is **never stored on this device**, in any form. We don't keep a hash, a copy, or anything that could be used to recover it. If you forget it, your data is unrecoverable - that's the trade-off for not having a server-side reset path.

To check whether the password you typed is correct, 1Key uses a small **verifier** - a piece of ciphertext that only the right password can decrypt. The key for this check is derived from your password using **Argon2id**, a memory-hard algorithm that allocates 64 MiB of RAM per attempt. This makes brute-forcing your password expensive enough that even a desktop GPU farm can't realistically crack a decent password in a useful timeframe.

The verifier itself is stored in **EncryptedSharedPreferences**, which is itself encrypted at rest by an Android Keystore-bound key. So even if someone extracted your phone's storage, they couldn't read the verifier blob - meaning offline brute-forcing of your password is not possible. They would need a live, attacker-controlled phone with your Keystore intact, which already implies game-over.

---

## How your credentials are encrypted

Each field of each credential - title, username, password, URL, notes, custom fields, TOTP secret - is encrypted separately with **AES-256-GCM**, the same authenticated cipher used by Signal, WhatsApp, and modern TLS. Authenticated encryption means tampering with the encrypted data is detected on decryption rather than producing scrambled output.

We don't use the vault key directly to encrypt fields. Instead we derive purpose-specific subkeys via **HKDF-SHA256** - one for credential fields, another for titles. Each field's encryption is also bound to its row ID and column name (using a technique called *additional authenticated data*), so an attacker with raw database write access can't swap, say, an old password's ciphertext into a different account without the decryption failing.

The same protections apply to the credential history table that stores previous versions of credentials when you edit them.

---

## Permissions explained

1Key requests only the permissions it genuinely needs.

### `CAMERA`
**Why:** Scanning QR codes when setting up TOTP two-factor authentication, and the OCR credential capture feature that reads text from a physical card or screen.

**What we do NOT do:** No photo or video is saved, uploaded, or stored anywhere. The camera feed is processed entirely on-device in real time and discarded immediately after the QR code or text is recognised.

### `USE_BIOMETRIC`
**Why:** Allows the app to prompt for fingerprint or face unlock so you can open your vault without typing your master password every time.

**What we do NOT do:** 1Key never sees your fingerprint or face data. The Android operating system handles biometric matching entirely inside the device's secure hardware. The app only receives a yes or no - authentication succeeded or it did not. Your biometric data never enters 1Key's code or memory.

### `READ_EXTERNAL_STORAGE` (Android 12 and below) / `WRITE_EXTERNAL_STORAGE` (Android 9 and below)
**Why:** Reading and writing backup files on older Android versions that pre-date the system file picker.

**What we do NOT do:** On Android 13 and above, neither of these is requested. Modern Android uses the system file picker (Storage Access Framework), which doesn't require a broad-storage permission - you grant access to a single chosen file at a time.

---

## Biometric unlock - how it actually works

When you enable biometric unlock, 1Key generates a cryptographic key inside the Android Keystore - a hardware-backed secure enclave on your device (called a TEE, Trusted Execution Environment, or StrongBox on newer phones). This key is tied to your biometric credential at the hardware level, and on Android 9+ it additionally requires the device to be unlocked at the time of use.

When you unlock with biometrics:
1. Android's secure hardware verifies your fingerprint or face
2. If it matches, the hardware releases the key to the app
3. 1Key uses that key to unwrap the vault key

Your fingerprint or face is never extracted, copied, or seen by 1Key. The secure enclave handles everything. If you clear your biometrics from your phone's settings, the key is automatically destroyed and biometric unlock stops working - you fall back to your master password.

---

## Brute-force protection

If someone gets hold of your phone, the only way into the vault without your biometrics is to guess the master password or PIN. 1Key makes guessing as slow as possible:

- **Persistent counters.** Wrong-attempt counts live in DataStore, so killing and reopening the app cannot reset them.
- **Tiered cooldowns.** 3 wrong attempts trigger a 30-second wait. 5 trigger 5 minutes. 10 trigger 1 hour.
- **PIN escalation.** After 3 wrong PINs, the easier PIN unlock is disabled until you successfully enter the master password - proving real identity before the shortcut resumes.
- **Argon2id cost per attempt.** Each guess pays a 64 MiB memory cost regardless of cooldown.

Combined, these mean even with the device in hand, a full 6-digit PIN search would take far longer than the device's useful lifetime, and a strong master password is unrealistic to brute-force.

---

## Threat model - what 1Key defends against, and what it doesn't

A password manager is only as useful as the threats it is honest about. The sections above describe the defences 1Key actually has. This section is the other side: things 1Key cannot stop, that you should know before trusting the app with sensitive credentials.

### What 1Key defends against

- **Offline attacks on a stolen or imaged device.** The vault key never lives on disk in unwrapped form. The master-password verifier sits in EncryptedSharedPreferences and is unreadable without live Keystore access. Even a complete disk image, without the live Keystore, cannot be brute-forced offline.
- **Brute-force password guessing.** Argon2id (m=64 MiB, t=3, p=1) on every password attempt, tiered cooldowns (30 s / 5 min / 1 h) that persist across process kills, and PIN escalation to master password after three wrong PINs.
- **Database tampering.** Per-field AES-256-GCM with row + column AAD detects any attempt to swap encrypted blobs between accounts.
- **Backup-file tampering.** The `.1key` envelope binds the export timestamp and vault-version counter into the GCM authentication tag - swapping bodies, replaying older backups, or modifying headers all fail authentication.
- **Network exfiltration.** No INTERNET permission in the manifest. The OS enforces this; the app literally cannot make a network request.
- **Casual shoulder-surfing of usage patterns.** Screenshots, screen recordings, and the Recent Apps preview are blocked by default via FLAG_SECURE. Clipboard copies of secrets auto-clear after 30 seconds.

### What 1Key does NOT defend against

These are not bugs - they are limits we want you to know about up front.

- **A fully compromised device.** If the OS itself is owned (root malware, a privilege-escalation exploit running with the same uid as 1Key, a debugger attached at the right moment), nothing in user-space can protect the in-memory vault key while the vault is unlocked. The app's sandbox is a guarantee from Android, not from us.
- **Loss of your master password.** There is no reset, no recovery code, no support email. If you forget it, the vault is gone - and so are your encrypted backups, because they are encrypted with the same password. This is intentional: the only path to recovery would be us holding a copy of your password (which we refuse to do), or a multi-secret escrow system (which would need a server we have nowhere to run). Pick a password you can remember and store it somewhere physical if you need to.
- **Theft of an already-unlocked device.** If someone grabs your phone while the vault is unlocked, before auto-lock fires, the unlocked vault is readable. There is no remote wipe, no kill switch we can fire from elsewhere - we have nowhere to fire it from. Lower the inactivity-lock timeout if this matters to you.
- **Biometric spoofing on an unlocked device.** Android's class-2 biometric sensors (older fingerprint readers, weaker face unlock) can be fooled by good-quality fakes. If your device's biometric is class-2 and an attacker has high-resolution prints or a 3D model, they may bypass biometric unlock on an unlocked device. The mitigation is to use a strong master password and treat biometric as convenience, not as a second factor.
- **Keystore compromise on pre-Android 9 devices.** On API < 28, the legacy Keystore key wrapping the vault key does not require the device to be unlocked at use time. If someone images the device storage and can later interact with the Keystore (rooted device, certain TEE exploits), the vault key can be unwrapped. On API >= 28 this is mitigated by `setUnlockedDeviceRequired(true)`.
- **A malicious user who knows your master password.** Once the password is in the wrong hands, the vault is open. We cannot tell who is typing.

If any of these matter for your specific threat model, a local-only password manager is not your best fit. Use a hardened OS build (GrapheneOS), pair it with a hardware token (YubiKey for the things that support it), and accept that some convenience trade-offs are required.

---

## Exports and backups

When you export your vault, you are moving your data from the app to a file. That file goes wherever you choose to put it - your Downloads folder, a USB drive, cloud storage you control, etc.

- **Encrypted exports** (`.1key` format) - the file is protected with AES-256-GCM using a key derived from your master password via Argon2id. The export timestamp and vault-version counter are bound into the authentication tag, so tampering with any header field, swapping the encrypted body across files, or replaying an older backup against a newer vault all fail authentication. Without the password, the file is indistinguishable from random bytes.
- **Plain exports** (CSV or JSON) - the file contains your credentials in readable text. 1Key warns you clearly before creating one and requires you to confirm your master password first.

1Key has no knowledge of where your export files end up. Once the file leaves the app, it is entirely your responsibility.

---

## Clipboard and screen capture

- Sensitive copies (passwords, 2FA codes) are automatically cleared from the clipboard 30 seconds after the copy.
- On Android 13 and above, those copies are marked sensitive so the system's paste-preview toast doesn't reveal the value.
- Screenshots, screen recordings, and the Recent Apps preview of the app are blocked by default. You can change this in Security settings if you really need it.

---

## Recycle bin

Deleted credentials go to a recycle bin for 30 days by default (configurable: 1 week, 30 days, 6 months, 1 year, or never). Bin items are encrypted with the exact same protections as active items - the only difference is they're filtered out of normal views. After the retention window, they're auto-purged on the next vault unlock.

---

## OCR - on-device text recognition

The OCR feature uses Google's ML Kit Text Recognition library. This library runs entirely on your device - no image or text is uploaded to Google or any other server. The camera frame is processed locally and the result is discarded once the text is extracted.

---

## Children's privacy

1Key does not target or knowingly collect any information from anyone, including children. Since no data is collected at all, there is nothing to address here.

---

## Changes to this policy

If this policy ever changes, the updated version will be in this file in the repository. Since 1Key has no account system, there is no way to notify you directly - check this file if you are curious.

---

## Contact

If you have questions or concerns, open an issue on GitHub.
