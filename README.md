# 🧮 PIXLBIT — Secure Offline Steganographic Vault

> *High-security offline steganography app for Android with a functional calculator facade, AES-256-GCM encryption, Argon2id, and secure duress wipe. Zero internet permissions.*

[![GitHub Release](https://img.shields.io/badge/Release-v2.8.3-blue.svg)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)]()
[![Network: Zero-Trust](https://img.shields.io/badge/Network-Isolated-red.svg)]()

---

## 🎯 What is PIXLBIT?

**PIXLBIT** is a privacy-first, offline-oriented Android application designed for secure steganographic data hiding. On the surface, it functions as a fully operational calculator. Behind the scenes, it acts as an impenetrable local vault for sensitive text notes and audio data, hidden in plain sight.

---

## 🛡️ Core Features & Architecture

* **The Facade:** Operates seamlessly as a standard calculator for everyday use.
* **Hidden Access:** The vault unlocks only via a specific **3-second long-press on the square root key (`√`)** followed by your custom PIN.
* **Silent Fail:** Incorrect PIN entries trigger zero alerts or errors, preserving plausible deniability at all times.
* **Zero-Trust Network Isolation:** Hardcoded without the `INTERNET` permission. Data never leaves your device; cloud and local backups (`allowBackup="false"`) are strictly blocked by design.
* **Military-Grade Cryptography:** Powered by **AES-256-GCM** encryption coupled with **Argon2id** key derivation from your passphrase.
* **Anti-Brute-Force Guard:** Thread-safe, atomic failure counters stored securely in `EncryptedSharedPreferences` with permanent lockouts after repeated failures.
* **Duress/Panic Wipe:** Dedicated panic codes to instantly destroy container data during critical situations.

---

## ⚠️ Important Note on Sharing Images
When sharing exported PNG images containing hidden data via messengers like WhatsApp, you **must** send them as a **"Document / File"** (paperclip icon) rather than a normal photo. Standard messenger image compression destroys steganographic LSB pixel data.

---

## 🚀 Quick Start / Build

Clone the repository and build a clean release APK locally using Gradle:

```bash
git clone [https://github.com/alexanderphilipp22-dot/PixlBit.git](https://github.com/alexanderphilipp22-dot/PixlBit.git)
cd PixlBit
./gradlew clean assembleRelease
