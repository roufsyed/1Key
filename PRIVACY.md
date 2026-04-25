# Privacy Policy — 1Key

**Last updated: April 2026**

1Key is a password manager built on a simple principle: your data belongs to you, not us. This document explains exactly what the app does and does not do with your information — in plain English, not legal boilerplate.

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

All of this is encrypted on disk using AES-256-GCM. The encryption key is derived from your master password — a password that only you know. No copy of your master password, or any key derived from it, is ever stored or transmitted anywhere.

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

No. The app does not have the `INTERNET` permission in its manifest. This is not a setting that can be toggled — it is architecturally impossible for 1Key to make a network request. Your device's operating system enforces this.

---

## Permissions explained

1Key requests only the permissions it genuinely needs. Here is every permission the app uses and exactly why:

### `CAMERA`
**Why:** Used for two things — scanning QR codes when setting up TOTP two-factor authentication, and the OCR credential capture feature that reads text from a physical card or screen.

**What we do NOT do:** No photo or video is saved, uploaded, or stored anywhere. The camera feed is processed entirely on-device in real time and discarded immediately after the QR code or text is recognised.

### `USE_BIOMETRIC`
**Why:** Allows the app to prompt for fingerprint or face unlock so you can open your vault without typing your master password every time.

**What we do NOT do:** 1Key never sees your fingerprint or face data. The Android operating system handles biometric matching entirely inside the device's secure hardware. The app only receives a yes or no — authentication succeeded or it did not. Your biometric data never enters 1Key's code or memory.

### `USE_FINGERPRINT`
**Why:** This is the older version of the biometric permission, required to support Android devices running Android 9 and earlier. It does exactly the same thing as `USE_BIOMETRIC` above.

---

## Biometric unlock — how it actually works

When you enable biometric unlock, 1Key generates a cryptographic key inside the Android Keystore — a hardware-backed secure enclave on your device (called a TEE, Trusted Execution Environment, or StrongBox on newer phones). This key is tied to your biometric credential at the hardware level.

When you unlock with biometrics:
1. Android's secure hardware verifies your fingerprint or face
2. If it matches, the hardware releases the key to the app
3. 1Key uses that key to decrypt the vault

Your fingerprint or face is never extracted, copied, or seen by 1Key. The secure enclave handles everything. If you clear your biometrics from your phone's settings, the key is automatically destroyed and biometric unlock stops working — you fall back to your master password.

---

## Exports and backups

When you export your vault, you are moving your data from the app to a file. That file goes wherever you choose to put it — your Downloads folder, a USB drive, cloud storage you control, etc.

- **Encrypted exports** (`.1key` format) — the file is protected with AES-256-GCM using a key derived from your master password. Without the password, the file is unreadable.
- **Plain exports** (CSV or JSON) — the file contains your credentials in readable text. 1Key warns you clearly before creating one and requires you to confirm your master password first.

1Key has no knowledge of where your export files end up. Once the file leaves the app, it is entirely your responsibility.

---

## OCR — on-device text recognition

The OCR feature uses Google's ML Kit Text Recognition library. This library runs entirely on your device — no image or text is uploaded to Google or any other server. The camera frame is processed locally and the result is discarded once the text is extracted.

---

## Children's privacy

1Key does not target or knowingly collect any information from anyone, including children. Since no data is collected at all, there is nothing to address here.

---

## Changes to this policy

If this policy ever changes, the updated version will be in this file in the repository. Since 1Key has no account system, there is no way to notify you directly — check this file if you are curious.

---

## Contact

If you have questions or concerns, open an issue on GitHub.
