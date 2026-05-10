---
title: 1Key — Local-First Password Manager for Android
description: No cloud. No account. No telemetry.
---

# 1Key

**A local-first Android password manager.** No cloud, no account, no telemetry, no `INTERNET` permission. Free, GPL-3.0, single-developer.

## Documents

- [White paper](whitepaper.html) — technical and strategic overview, including cryptographic architecture and threat model
- [Privacy policy](PRIVACY_POLICY.html)
- [FAQ](FAQ.html)

## Source

- Repository: [github.com/roufsyed/1key](https://github.com/roufsyed/1key)
- Licence: [GNU General Public License v3.0](https://github.com/roufsyed/1key/blob/master/LICENSE)
- Trademarks: see [TRADEMARKS.md](https://github.com/roufsyed/1key/blob/master/TRADEMARKS.md)

## What 1Key is

The vault lives on a single device, encrypted with Argon2id (m=64 MiB, t=3, p=1) for password derivation and AES-256-GCM for authenticated encryption. The vault key is wrapped by an Android Keystore key bound to the device's TEE or StrongBox. The master-password verifier sits in `EncryptedSharedPreferences`, not next to the database, so a stolen database file alone cannot be brute-forced offline.

## What 1Key is not

It does not synchronise across devices. It does not offer team sharing. It does not support recovery if the master password is forgotten. These are direct consequences of the design decision that the vault should never leave the device. If you need any of those features, choose a hosted password manager — they exist for good reasons.

## Audience

This site is intended for two audiences: privacy-conscious individuals deciding whether 1Key fits their threat model, and security reviewers who want to read the architecture before installing it.
