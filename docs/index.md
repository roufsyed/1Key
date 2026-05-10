---
title: 1Key — Local-First Password Manager for Android
description: A password manager that lives only on your phone. No cloud, no account, no telemetry, no INTERNET permission. Free, GPL-3.0.
---

**The password manager that doesn't have a server to breach.**

<div class="k-hero" markdown="0">
  <p>No cloud. No account. No telemetry. No <code>INTERNET</code> permission in the manifest. Free, GPL-3.0, single developer.</p>
  <div class="k-cta">
    <a class="k-btn k-btn--primary" href="https://github.com/roufsyed/1key/releases">Download APK</a>
    <a class="k-btn k-btn--secondary" href="whitepaper.html">Read white paper</a>
    <a class="k-btn k-btn--ghost" href="https://github.com/roufsyed/1key">View source &rarr;</a>
  </div>
</div>

<p align="center">
  <img src="screenshots/vault_home.jpeg" alt="1Key vault home screen" width="280">
</p>

---

## Why 1Key exists

Every mainstream password manager keeps your vault on its servers and charges you £25–£100 a year for autofill, TOTP, and export — features that should be commoditised by now. The pitch is convenience: sync, recovery, sharing. The bill is an encrypted blob sitting on someone else's machine, attached to your email, waiting for the next breach disclosure.

1Key takes the opposite trade. Your vault lives in one place — this phone — and never leaves unless you explicitly export it. You give up sync and recovery. In return, there is no vendor server in your threat model, no account to subpoena, no auth blob to brute-force offline, and no subscription.

---

## What's inside

<div class="k-grid" markdown="0">
  <div class="k-card">
    <h4>Argon2id KDF</h4>
    <p>m=64 MiB, t=3, p=1. Memory-hard derivation that flattens the GPU and ASIC speedup attackers expect from PBKDF2 or bcrypt.</p>
  </div>
  <div class="k-card">
    <h4>AES-256-GCM + HKDF</h4>
    <p>Authenticated encryption with HKDF-SHA256 subkey separation. Each field is encrypted independently and bound by AAD to its row and column.</p>
  </div>
  <div class="k-card">
    <h4>Keystore-bound verifier</h4>
    <p>The password check sits in <code>EncryptedSharedPreferences</code>, not next to the database. A leaked SQLite file alone has no oracle to brute-force on devices with a working hardware Keystore.</p>
  </div>
  <div class="k-card">
    <h4>TOTP in the same record</h4>
    <p>Your second factor lives next to the credential it protects. No app switching, no premium tier.</p>
  </div>
  <div class="k-card">
    <h4>OCR credential capture</h4>
    <p>Point the camera at a card, screen, or printed token. On-device ML Kit extracts the text. Nothing uploads.</p>
  </div>
  <div class="k-card">
    <h4>Encrypted backups (V4)</h4>
    <p>AES-256-GCM under Argon2id, with timestamp and vault-version counter in the auth tag. An old backup cannot be replayed against a current vault.</p>
  </div>
  <div class="k-card">
    <h4>Auto-detecting importer</h4>
    <p>Google Passwords, LastPass, KeePass, 1Password, Bitwarden, iCloud Keychain, Dashlane, NordPass. Drop the CSV, no manual mapping.</p>
  </div>
  <div class="k-card">
    <h4>Table-stakes UX</h4>
    <p>Categories, favourites, recycle bin, search, sort. Free tier in 1Key is the only tier.</p>
  </div>
</div>

Full cryptographic architecture and threat model: **[white paper](whitepaper.html)**.

---

## See it

<div class="k-shots" markdown="0">
  <img src="screenshots/2fa_totp_list.jpeg" alt="1Key 2FA / TOTP list">
  <img src="screenshots/Airbnb_credential_detail.jpeg" alt="Credential detail view">
  <img src="screenshots/settings.jpeg" alt="Settings screen">
</div>

---

## Honest limits

These aren't an MVP backlog. They're the architecture working as designed.

- **One device.** No sync. Lose the phone without a current backup, lose the vault.
- **One password.** No recovery, no escrow, no reset link. Forget it without a backup, lose the vault.
- **One developer.** No on-call, no SLA, no third-party security audit yet. The code is open — read it, build it, run whichever version works.

If you need cross-device sync, team sharing, or vendor recovery, choose a hosted manager. They exist for good reasons. 1Key is for users who deliberately want no vendor server in their threat model.

---

## Should you use 1Key?

<div class="k-matrix" markdown="1">

| Use 1Key if... | Don't use 1Key if... |
|---|---|
| You want a manager that never talks to a server | You need cross-device sync |
| You're done paying £25–£100/year for autofill and TOTP | You need to share credentials with a team |
| You can keep an encrypted backup somewhere safe | You can't reliably remember a strong master password |
| You're comfortable reading source or trusting community review | You need formal SOC 2 / ISO 27001 vendor compliance |

</div>

---

## Get it

- **Install** — APK from [GitHub Releases](https://github.com/roufsyed/1key/releases). F-Droid distribution is planned.
- **Build it yourself** — three commands, no API keys, no `.env`:

  ```bash
  git clone https://github.com/roufsyed/1key
  cd 1key
  ./gradlew assembleDebug
  ```

  Then install the resulting `app-debug.apk`.
- **Read the source** — [github.com/roufsyed/1key](https://github.com/roufsyed/1key)

Minimum Android 8.0 (API 26). Target Android 16 (API 36).

---

## Documents

- **[White paper](whitepaper.html)** — cryptographic architecture and threat model
- **[Privacy policy](PRIVACY_POLICY.html)** — what 1Key collects (nothing) and what it stores (locally)
- **[FAQ](FAQ.html)** — encryption, passwords, backups, sync

---

## Licence and trademarks

1Key is released under the [GNU General Public License v3.0](https://github.com/roufsyed/1key/blob/master/LICENSE). The name "1Key" and the application icon are trademarks of the author — see [TRADEMARKS.md](https://github.com/roufsyed/1key/blob/master/TRADEMARKS.md). Forks are legally permitted under the GPL but must rebrand before redistribution.
