# Calculator â€” Security Documentation

Version: 2.8.2 (audit reference)

## 1. Overview

Calculator is an offline-first steganography and security application. All data
processing (encryption, embedding, extraction) happens entirely on-device.
The app declares **no `android.permission.INTERNET`** and loads all dependencies
locally from its assets.

## 2. Cryptography

- **Algorithm:** AES-256 in GCM mode (`AES/GCM/NoPadding`) â€” authenticated
  encryption with a 128-bit tag.
- **Key derivation:** PBKDF2-HMAC-SHA256, 210,000 iterations, per-message
  16-byte random salt.
- **Nonce:** fresh 12-byte random nonce per encryption (never reused).
- **Payload layout:** `[SALT(16)][NONCE(12)][CIPHERTEXT+TAG]`.
- **Memory hygiene:** derived key bytes, plaintext and decrypted buffers are
  zeroed (`fill(0)`) immediately after use.
- **File:** `app/src/main/java/com/mobileutilities/calc/AesGcmCrypto.kt`

## 3. Audio compression

- **Primary:** Android `MediaRecorder`, AAC-LC, 24 kbit/s, mono, 24 kHz.
- **Fallback:** WebView `MediaRecorder`, Opus, `audioBitsPerSecond: 24000`.
- **Native blackbox:** `libcalc_native.so` exposes `encodePcmToOpus`.
  **Note:** the libopus sources are not yet vendored; the encoder returns `null`
  until they are added (see Â§9).

## 4. Native hardening (`libcalc_native.so`)

- Anti-debugging: `/proc/self/status` `TracerPid` check (read-only).
- Root detection: `su` binary + Magisk indicator checks (read-only).
- `secure_wipe()`: volatile-loop memory zeroing (not compiler-optimizable).
- `secure_mlock()`: `mlock` + `madvise(MADV_DONTDUMP)` for small buffers.
- Dynamic JNI registration (`RegisterNatives` + `JNI_OnLoad`) combined with
  `-fvisibility=hidden` â€” no exported `Java_*` symbols in the binary.
- Deterministic PRNG bit-permutation (`bitPermutation`, SplitMix64) as a
  forensic primitive for pseudorandom LSB distribution.

## 5. Steganography

- LSB embedding with a header `[P][B][version][mode][length][CRC16]`.
- **Capacity throttle:** payload is limited to 10 % of image capacity to stay
  below statistical-steganalysis thresholds (chi-square / Pairs-of-Values).
- Original image dimensions are preserved (no square-forcing / downscale).
- Decoy payload (plausible deniability): an alternative PIN renders harmless
  decoy text instead of decrypting (see Â§8 for storage caveat).

## 6. Duress / panic code

A user-defined duress code (salted SHA-256 hash) triggers a silent, complete
wipe of images, WebStorage and preferences when entered as the password.
See `PanicCodeStore.kt`.

## 7. Data-at-rest protection

- `FileProvider` exposes only two narrow paths
  (`files/Pictures/Calculator/` and `cache/share/`), with
  `android:exported="false"` and `android:grantUriPermissions="true"`.
- Cloud backup disabled (`android:allowBackup="false"`) plus
  `data_extraction_rules.xml` and `backup_rules.xml` exclude all domains
  (files, databases, sharedprefs, external, device-protected).
- `FLAG_SECURE` blocks screenshots and recents thumbnails.

## 8. Known limitations (honest notes for auditors)

- **libopus not vendored:** the native Opus encoder is a stub until the sources
  are provided (the build environment had no network access to fetch them).
- **Decoy PIN storage:** stored in plaintext in WebView `localStorage`
  (acceptable for a fake PIN; its compromise reveals only the decoy text).
- **`mlock` scope:** limited to ~64 KB (`RLIMIT_MEMLOCK`) and applies only to
  native buffers; Kotlin heap buffers (AES keys) are not mlocked.
- **LSB PRNG:** the native `bitPermutation` primitive is implemented but not
  yet wired into the JS LSB embedder (wiring it requires a format-version bump).
- **Trial mode:** a 3-day trial is implemented in `PremiumManager` via a local
  first-launch timestamp, independent of Google Play Billing.

## 9. Build & verification

- Debug: `./gradlew assembleDebug`
- Release (unsigned): `./gradlew assembleRelease`
- R8 (minification + shrinking) is enabled for release. The custom tasks
  `verifyJsBridgeKept` and `verifyReleaseHardening` run automatically and fail
  the build on missing bridge names or a debuggable release manifest.
