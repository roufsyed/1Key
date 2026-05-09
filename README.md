# 1Key — Local-First Password Manager for Android

> Built because I got tired of paying £35/year just to autofill a password.

Most password managers lock their best features behind a subscription — cross-device sync, secure notes, TOTP codes, and even basic export often require a premium plan. 1Key exists to give you all of that, for free, forever, with no account required and no server ever involved.

---

## Screenshots

<table>
  <tr>
    <td align="center"><b>Vault Home</b></td>
    <td align="center"><b>All Items</b></td>
    <td align="center"><b>Credential Detail</b></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/vault_home.jpeg" width="200"/></td>
    <td><img src="docs/screenshots/all_items.jpeg" width="200"/></td>
    <td><img src="docs/screenshots/Airbnb_credential_detail.jpeg" width="200"/></td>
  </tr>
  <tr>
    <td align="center"><b>Edit Credential</b></td>
    <td align="center"><b>2FA / TOTP Codes</b></td>
    <td align="center"><b>Settings</b></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/airbnb_edit_credential.jpeg" width="200"/></td>
    <td><img src="docs/screenshots/2fa_totp_list.jpeg" width="200"/></td>
    <td><img src="docs/screenshots/settings.jpeg" width="200"/></td>
  </tr>
</table>

---

## Why 1Key?

Every major password manager today requires you to:

- **Create an account** — your vault identity lives on someone else's server
- **Trust their encryption** — you have no way to verify how they actually store your data
- **Pay for the features that matter** — TOTP codes, secure export, and device sync are almost always premium
- **Accept telemetry** — usage analytics, crash reporting, and "improvement data" collection are on by default

1Key takes a different position: your passwords are yours. They live on your device, encrypted by a key only you hold, and they never move unless you explicitly export them.

---

## Privacy

1Key collects **nothing**. No analytics, no crash reporting, no telemetry, no usage data — not even anonymised. There is no backend, no account system, and no `INTERNET` permission in the manifest.

- All data is stored locally in an encrypted Room database
- The master password never leaves the device — not even a hash is transmitted
- Screenshots and Recent Apps previews are blocked by default via `FLAG_SECURE`
- The app requests the minimum possible permissions

The only things that leave your device are files you explicitly choose to export.

Full details: **[Privacy Policy →](PRIVACY.md)**

---

## Encryption

1Key layers several independent protections so a failure in any one of them doesn't expose your data.

### Memory-hard password verification

When you set a master password or PIN, 1Key derives a key from it using **Argon2id** (64 MiB memory, 3 passes). Argon2id is the OWASP-recommended password hash, and it's deliberately *memory*-expensive — not just CPU-expensive — so it can't be cheaply parallelised on GPUs or cloud farms. Even if someone got hold of your encrypted blobs, brute-forcing your password would mean allocating 64 MiB of RAM per guess, which is intractable for any decent password.

### AES-256-GCM with per-field authentication

Every credential field — title, username, password, URL, notes, custom fields, TOTP secret — is encrypted separately with AES-256-GCM. Each field's encryption is bound to the specific row and column it belongs to via *additional authenticated data* (AAD), so an attacker with database write access can't swap one field's encrypted blob into a different column or a different row without the decrypt failing.

### HKDF subkey separation

The vault key isn't used directly to encrypt fields. 1Key derives purpose-specific subkeys via HKDF-SHA256 — one for credential fields, another for titles. This means each subkey only sees the data it's meant to, and a future cipher rotation can target one subkey without affecting the others.

### Hardware-backed key wrapping

The vault key itself is wrapped (encrypted) by a key inside the Android Keystore, which lives in your device's secure hardware (TEE or StrongBox). That wrapping key never leaves the secure enclave. On Android 9+ it additionally requires the device to be unlocked at the time of use, so the vault is unreadable on a powered-on but locked phone.

### Encrypted at rest, not just in the database

The password verifier, PIN hash, and wrapped vault key all live in **EncryptedSharedPreferences** — a Keystore-bound encrypted file. So even if someone managed to extract the file from your device's storage, they couldn't read the verifier without live Keystore access — meaning offline brute-forcing of your password isn't possible.

### Encrypted backup files

`.1key` backups are encrypted with AES-256-GCM under an Argon2id-derived key. The backup header binds the export timestamp and the vault's version counter into the GCM authentication tag, so swapping a header field, a ciphertext body, or replaying an older backup against a newer vault all fail authentication.

### Tiered persistent lockouts

Wrong-password and wrong-PIN attempts are tracked in DataStore (so killing and reopening the app can't reset the counter) and trigger escalating cooldowns:

| Failures | Cooldown |
|---|---|
| 3 | 30 seconds |
| 5 | 5 minutes |
| 10 | 1 hour |

After 3 wrong PINs, the user is forced to fall back to the master password — proving real identity before the easier path resumes. Biometric attempts have their own 3-strike counter that triggers the same fallback.

---

## Features

### Password Vault
- Store credentials with title, username, password, URL, notes, and custom fields
- Tag-based organisation with custom categories
- Favourites tab for frequently used accounts
- Full-text search across all fields

### TOTP / 2FA — all in one place
Most apps make you switch between a password manager and a separate authenticator. 1Key stores both in the same credential entry. Scan a QR code once, and your TOTP codes live alongside the password they protect — no app switching.

- Scan setup QR codes with the built-in camera scanner
- Live 30-second countdown with automatic code refresh
- One-tap copy to clipboard

### OCR Credential Capture
Point your camera at a printed password, a screen showing credentials, or a physical token card, and 1Key extracts the text using on-device ML Kit OCR — no image is uploaded. The recognised text is pre-filled into the credential form for you to review and save. Everything runs locally on the device.

### Backup & Import
- Export your vault as an encrypted `.1key` file (AES-256-GCM with Argon2id key derivation, timestamp and vault-version bound into the auth tag) or plain CSV/JSON
- Encrypted exports require your master password to restore — no password, no access
- Import from 1Key backups or directly from other password managers

**Supported import sources:**
| App | Format |
|-----|--------|
| Google Passwords | CSV |
| LastPass | CSV |
| KeePass | CSV |
| Safari / iCloud Keychain | CSV |
| 1Password | CSV |
| Dashlane | CSV |
| NordPass | CSV |

The importer auto-detects format and column headers — no manual mapping required. Duplicate credentials are detected and skipped.

### Unlock Options

- **Master password** — always available as the primary fallback
- **Biometric** — fingerprint or face unlock backed by hardware-secure key; requires master-password confirmation to enable
- **PIN** — 6-digit PIN for quick access; resets to master password if removed
- **Background auto-lock** — locks when the app moves to the background. Options: Immediate (default), 30 seconds, 1 minute, 5 minutes
- **Inactivity auto-lock** — locks when the app is in the foreground but idle. Options: Never, 30 seconds, 1 minute, 5 minutes (default), 15 minutes
- **Periodic master-password recheck** — optionally require master password every 48 hours / 3 days / 1 week / 3 weeks even when biometric or PIN is enabled
- **Tiered attempt limiting** — 3, 5, and 10 wrong attempts on master password or PIN trigger 30-second, 5-minute, and 1-hour cooldowns respectively. Counters survive process kills.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt / Dagger |
| Database | Room (SQLite) |
| Encryption | AES-256-GCM, Argon2id, HKDF-SHA256 |
| Auth metadata | EncryptedSharedPreferences (Keystore-bound) |
| Biometric | AndroidX Biometric + Android Keystore |
| OCR | ML Kit Text Recognition (on-device) |
| QR Scanning | ML Kit Barcode Scanning (on-device) |
| Async | Kotlin Coroutines + StateFlow |
| Navigation | Jetpack Navigation Compose |

---

## Building

```bash
git clone https://github.com/roufsyed/1key.git
cd 1key
./gradlew assembleDebug
```

Requires Android Studio Hedgehog or later. No API keys, no `.env` files, no setup — clone and build.

---

## Permissions

| Permission | Reason |
|-----------|--------|
| `CAMERA` | QR code scanning and OCR credential capture |
| `USE_BIOMETRIC` | Biometric unlock |
| `READ_EXTERNAL_STORAGE` (Android ≤ 12) | Reading backup files on older Android versions |
| `WRITE_EXTERNAL_STORAGE` (Android ≤ 9) | Writing backup files on older Android versions |

No `INTERNET`. No `READ_CONTACTS`. No `ACCESS_FINE_LOCATION`. Nothing else. On Android 13+ no storage permission is requested at all — backups go through the system file picker.

---

## License

Copyright © 2026 Rouf Syed. All rights reserved — see [LICENSE](LICENSE) for details.
