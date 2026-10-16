# PIXLBIT / Calculator — Red-Team-Bericht

**Rolle:** Lead Red Team Operator & Offensive Mobile Security Expert
**Modus:** Read-Only / Adversarial Attack Simulation
**Target:** `com.mobile.utilities.calculator` („Calculator“, Steganografie-App mit Taschenrechner-Fassade), Version 2.8.3 (versionCode 7)
**Analysebasis:** vollständiger Quelltext (Kotlin, WebView-Frontend `index.html`, Manifest, Gradle-Konfiguration, Build-Artefakte)

---

## 0. Executive Summary (ungeschönt)

Die App ist **kryptografisch überdurchschnittlich sauber gebaut** (Argon2id statt PBKDF2, AES-256-GCM mit frischem Salt+Nonce, EncryptedSharedPreferences mit Hardware-Keystore, konstantezeitige Vergleiche, aggressive RAM-Nullung). Das ist der Teil, der einen Angreifer *wirklich* aufhält.

Die **App-Architektur** ist dagegen an mehreren Stellen ein Pappkarton: Der „Tresor“ ist **kein kryptografischer Tresor, sondern eine CSS-Fassade** — die PIN schützt nur die Sichtbarkeit der Oberfläche, nicht die Daten. Die **Anti-Tampering-Prüfung ist im gebauten Release faktisch deaktiviert** (Signatur-Hash leer). Und die **Steganografie trägt einen Klartext-Magic-String („PB“) in den LSBs**, der forensisch ein Volltreffer ist.

**Bottom line:** Gegen einen Gelegenheits-Schnüffler (ohne Root, ohne Gerätezugriff) ist die App gut. Gegen einen **professionellen Angreifer mit Geräte-/APK-Zugriff** fällt die Fassade sofort, und die einzige verbliebene Verteidigungslinie ist die **Stärke der Benutzer-Passphrase** — die wiederum nirgends erzwungen wird.

---

## 1. Target-Status

### TARGET 1 — PIN/Trigger-Bypass der Rechner-Fassade
**Status: GEKNACKT (Low Effort)**

Die Fassade ist keine Sicherheitsgrenze. Konkret:

- Der komplette „Tresor“ (Encode/Decode-Oberfläche) liegt **im selben DOM** wie die Fassade und wird nur per CSS versteckt:
  `body.decoy-active > header, body.decoy-active > main { display: none !important; }` — [index.html#L160-L162](../../app/src/main/assets/index.html#L160-L162), Body-Startklasse [index.html#L454](../../app/src/main/assets/index.html#L454).
- Der „geheime Trigger“ (3-Sekunden-Long-Press auf „√“) ist **reines JavaScript** (`setTimeout(..., 3000)` auf `pointerdown`), kein natives/OS-Gestik-Gate — [index.html#L5718-L5740](../../app/src/main/assets/index.html#L5718-L5740). Er ist trivial per `startUnlockTransition()` oder einfach per Entfernen der CSS-Klasse auslösbar.
- Die **Haupt-PIN kann ohne Kenntnis der alten PIN überschrieben** werden: `AndroidBridge.setMainPin()` ([AndroidBridge.kt#L616-L625](../../app/src/main/java/com/mobile/utilities/calculator/AndroidBridge.kt#L616-L625)) ruft `MainPinStore.setPin()` auf, das Salt+Hash **bedingungslos überschreibt** — der „nur beim Erststart“-Guard existiert nur im JS-Wrapper ([index.html#L5660-L5665](../../app/src/main/assets/index.html#L5660-L5665)), **nicht nativ** ([MainPinStore.kt#L54-L80](../../app/src/main/java/com/mobile/utilities/calculator/MainPinStore.kt#L54-L80)).
- `AndroidBridge.verifyMainPin()` hat **keinen Brute-Force-Guard** (nur `aesDecrypt` und der JS-Pfad haben einen) — [AndroidBridge.kt#L594-L601](../../app/src/main/java/com/mobile/utilities/calculator/AndroidBridge.kt#L594-L601) vs. [AndroidBridge.kt#L410-L422](../../app/src/main/java/com/mobile/utilities/calculator/AndroidBridge.kt#L410-L422). Direkte Bridge-Aufrufe können die PIN **ohne Lockout** raten (gebremst nur durch Argon2id).
- **Anti-Tampering ist im gebauten Release deaktiviert:** `EXPECTED_SIGNATURE_SHA256 = ""` ([BuildConfig.java#L13](../../app/build/generated/source/buildConfig/release/com/mobile/utilities/calculator/BuildConfig.java#L13), Quelle [gradle.properties#L18](../../gradle.properties#L18)), und `IntegrityGuard.verifySignature()` gibt bei leerem Hash **immer `true`** zurück ([IntegrityGuard.kt#L59-L61](../../app/src/main/java/com/mobile/utilities/calculator/IntegrityGuard.kt#L59-L61)). → **Repackaging wird nicht erkannt.**
- Die Frida-/Debugger-Erkennung ist naiv: nur `TracerPid` + Default-Port 27042 + `Debug.isDebuggerConnected()`, und läuft **einmalig** in `onCreate` — [IntegrityGuard.kt#L23-L38](../../app/src/main/java/com/mobile/utilities/calculator/IntegrityGuard.kt#L23-L38). `frida-gadget` (injiziert, setzt keinen TracerPid, nutzt anderen Port) oder `frida-server` auf Fremd-Port umgehen das vollständig.
- `WebView.setWebContentsDebuggingEnabled(true)` ist korrekt nur im Debug-Build aktiv ([MainActivity.kt#L145-L147](../../app/src/main/java/com/mobile/utilities/calculator/MainActivity.kt#L145-L147)), aber ein repackagtes APK kann es einfach einschalten.

**Verwaiste Debug-/Hilfs-Hooks:** `isDebugBuild()`, `microphoneDiagnostics()` (leakt SDK-Int, WebView-Version, RECORD_AUDIO-Status), `getPremiumState()`. Der angekündigte Browser-AES-Fallback (CryptoJS) ist **toter Code** (leerer Platzhalter) — [index.html#L4729-L4731](../../app/src/main/assets/index.html#L4729-L4731).

### TARGET 2 — Kryptografie & Schlüsselextraktion
**Status: STARK GEHÄRTET (Krypto selbst) / Passphrase = einzige Schwachstelle**

Was gut ist:
- **AES-256-GCM** korrekt: 12-Byte-Zufalls-Nonce, 128-Bit-Tag, 16-Byte-Zufalls-Salt, alles **pro Verschlüsselung frisch** — kein IV-/Key-Reuse ([AesGcmCrypto.kt#L22-L58](../../app/src/main/java/com/mobile/utilities/calculator/AesGcmCrypto.kt#L22-L58)).
- **KDF ist Argon2id** (BouncyCastle, 64 MiB / t=3 / p=4 / 256-bit Output) — **besser** als das in Task/Kommentaren genannte PBKDF2 ([Argon2Kdf.kt#L13-L30](../../app/src/main/java/com/mobile/utilities/calculator/Argon2Kdf.kt#L13-L30)). Offline-Brute-Force ist damit **speicherhart und teuer**.
- **Keine Key-/IV-Leakage:** keine Klartext-Keys in Logs, SharedPrefs oder Cache. PIN/Panik-Code liegen als Salt+Argon2id-Hash in `EncryptedSharedPreferences` (AES256-SIV-Keys / AES256-GCM-Werte, eigener Keystore-Alias pro Datei, StrongBox→TEE) — [SecurePrefsFactory.kt#L30-L68](../../app/src/main/java/com/mobile/utilities/calculator/SecurePrefsFactory.kt#L30-L68). Konstantezeit-Vergleich via `MessageDigest.isEqual`. RAM-Nullung und `System.gc()` konsequent. Logcat wird im Release via ProGuard vollständig eliminiert ([proguard-rules.pro#L115-L121](../../app/proguard-rules.pro#L115-L121)).

Was angreifbar bleibt:
- **Der Ciphertext ist öffentlich.** Salt + Nonce + Ciphertext werden (base64-kodiert) **in die PNG-LSBs geschrieben** und über Messenger geteilt. Jeder, der die Datei besitzt, kann den Ciphertext extrahieren — „Schlüsselextraktion“ ist nicht nötig. **Die gesamte Sicherheit hängt an der Passphrase.**
- **Die Passphrase wird nicht auf Stärke geprüft.** Im Encode-Pfad wird nur `if (!passphrase)` geprüft, keine Mindestlänge/Komplexität ([index.html#L4703-L4706](../../app/src/main/assets/index.html#L4703-L4706)). Ein 1-Zeichen-Passwort wird akzeptiert → Offline-Brute-Force trotz Argon2id trivial (Argon2id verlangsamt nur, kann schwache Passphrasen nicht retten).
- **Nebenbefund:** `calc_premium` (`PremiumManager.prefs`) ist eine **unverschlüsselte** SharedPreferences, enthält aber nur Erststart-Zeitstempel + Preisschild — nicht sensibel ([PremiumManager.kt#L115-L116](../../app/src/main/java/com/mobile/utilities/calculator/PremiumManager.kt#L115-L116)).
- **Doku-Inkonsistenz:** Kommentare/README behaupten „PBKDF2WithHmacSHA256“ bzw. „SHA-256-Hash“ ([index.html#L4723](../../app/src/main/assets/index.html#L4723), [proguard-rules.pro#L75-L79](../../app/proguard-rules.pro#L75-L79), [README.md#L468-L469](../../README.md#L468-L469)) — **tatsächlich läuft überall Argon2id**. Kein Bug, aber irreführend und ein Hinweis darauf, dass die Härtung inkrementell passiert ist.

### TARGET 3 — Duress-Wipe-Blockade
**Status: TEILWEISE GEHÄRTET / Shreddern auf Flash wirkungslos, Kill-Fenster vorhanden**

Gut:
- **Lockout kann den Panik-Wipe NICHT blockieren.** Der Panik-Code wird **bewusst vor** der Brute-Force-Sperre geprüft ([index.html#L5666-L5676](../../app/src/main/assets/index.html#L5666-L5676)). Die im Task beschriebene Blockade über „App sperren → Wipe läuft ins Leere“ ist damit **konstruktiv verhindert**.
- Der Wipe läuft **asynchron und persistiert** über WorkManager (Expedited, `RUN_AS_NON_EXPEDITED_WORK_REQUEST`, 5 Retries) und überlebt normale Prozess-Kills — [SecureWipeWork.kt#L89-L121](../../app/src/main/java/com/mobile/utilities/calculator/SecureWipeWork.kt#L89-L121).

Angriffsfläche:
- **3-Pass-Shreddern ist auf Flash (eMMC/UFS) faktisch wirkungslos.** Wear-Leveling/FTL schreibt die Overwrite-Pässe auf *neue* Blöcke; die alten Blöcke bleiben forensisch (Chip-off/JTAG) auslesbar ([SecureDelete.kt#L34-L61](../../app/src/main/java/com/mobile/utilities/calculator/SecureDelete.kt#L34-L61)). Das ist eine Plattform-Grenze, aber als „sicheres Löschen“ suggeriert es Schutz, den es nicht gibt.
- **Das öffentliche Album wird NICHT geschreddert**, sondern nur per `contentResolver.delete()` gelöscht (kein Overwrite) — [SecureDelete.kt#L102-L124](../../app/src/main/java/com/mobile/utilities/calculator/SecureDelete.kt#L102-L124). Rekonstruierbar über Filesystem-Forensik.
- **Der Fallback-Pfad (Daemon-Thread) wird mit dem Prozess gekillt und NICHT neu gestartet** — schlägt `WorkManager.enqueue` fehl, unterbricht ein Force-Stop den Wipe dauerhaft ([SecureWipeWork.kt#L107-L121](../../app/src/main/java/com/mobile/utilities/calculator/SecureWipeWork.kt#L107-L121)).
- **Force-Stop / Reboot / Uninstall** hebelt einen noch laufenden Wipe aus (WorkManager-Jobs werden bei Force-Stop gecancelt; Expedited-Jobs haben auf Android 12+ ein Zeitbudget).
- **`PanicCodeStore.clear()` läuft vor dem Wipe** — schlägt der Wipe fehl, ist der Panik-Code schon weg ([AndroidBridge.kt#L378-L379](../../app/src/main/java/com/mobile/utilities/calculator/AndroidBridge.kt#L378-L379)).
- Der Wipe ist **nicht transaktional**: ein Abbruch hinterlässt Teilreste.
- **Grundsatzproblem:** Der Wipe schützt nur gegen *nachträgliche* forensische Zugriffe. Ein Angreifer, der das Gerät bereits besitzt, hat die Daten meist schon extrahiert — der Duress-Wipe kommt zu spät.

### TARGET 4 — Steganografie & Forensik
**Status: FORENSISCH SOFORT NACHWEISBAR (Extrahieren ohne Passwort = ja; Entschlüsseln = nein)**

- Das Pixel-Bett beginnt mit einem **Klartext-Magic „PB“** (0x50, 0x42), gefolgt von Version, Modus und **Klartext-Längenfeld** (4 Byte) — [StegoExtractor.kt#L11-L13](../../app/src/main/java/com/mobile/utilities/calculator/StegoExtractor.kt#L11-L13), [index.html#L4003-L4015](../../app/src/main/assets/index.html#L4003-L4015), [index.html#L4062-L4077](../../app/src/main/assets/index.html#L4062-L4077).
- **Nachweis ohne Passwort:** trivial — LSBs der ersten Pixel auslesen, „PB“ an fester Position finden ⇒ versteckte Daten sind **bewiesen** und die **Ciphertext-Länge** liegt im Klartext vor.
- **Extraktion ohne Passwort:** trivial — das Format ist im Code dokumentiert; der Angreifer erhält Salt+Nonce+Ciphertext. **Entschlüsseln ohne Passwort: nein** (AES-256-GCM), sofern die Passphrase stark ist.
- **Statistische Auffälligkeit:**
  1. Die Einbettung ist **sequenziell** (Pixel 0…N, von oben links) — [index.html#L4114-L4138](../../app/src/main/assets/index.html#L4114-L4138). Die modifizierte Region ist ein **zusammenhängender führender Block**, kein diffuser Spread. Ein Chi-Quadrat-/PoV-Test über den führenden 10-%-Block gegen den Rest zeigt eine klare LSB-Verteilungsverschiebung.
  2. Die 10-%-Drossel (`STEGO_MAX_FILL_RATIO = 0.10`, [index.html#L4012](../../app/src/main/assets/index.html#L4012)) senkt die Gesamtauffälligkeit, verhindert aber die **lokale** Erkennung nicht.
  3. Der **Graustufen-Modus macht das ganze Bild R=G=B** — ein Farbfoto wird monochrom, das ist ohne jede Statistik sichtbar.
  4. Die Payload (Base64 von GCM-Ciphertext) ist pseudo-zufällig → LSBs ≈ uniform. Das *kann* mit einem unberührten Original unterschieden werden, wenn der Analyst das Cover-Original zum Vergleich hat (Vergleich der LSB-Histogramme).
- **Datenverlust:** Der v2-Header hat CRC16 → erkennt Beschädigung mit ~1/65536 False-Accept ([index.html#L4027-L4059](../../app/src/main/assets/index.html#L4027-L4059)). Das **Legacy-Format hat kein CRC** ([StegoExtractor.kt#L95-L129](../../app/src/main/java/com/mobile/utilities/calculator/StegoExtractor.kt#L95-L129)) — dort schlägt Korruption erst am AES-GCM-Tag fehl und wird fälschlich als „falsches Passwort“ gemeldet. GCM selbst liefert 128-Bit-Integrität der Payload.

---

## 2. Die 3 wahrscheinlichsten Einfallstore (kritische Angriffsvektoren)

### Vektor A — Repackaging + WebView-/JS-Instrumentierung (höchste Wirkung, niedrigster Aufwand)
Die **Signatur-Attestierung ist leer** und die Frida-/Debugger-Erkennung naiv. Ein Angreifer nimmt die APK, schaltet `WebView.setWebContentsDebuggingEnabled(true)` ein (oder injiziert `frida-gadget`), repackagiert und signiert neu — **unentdeckt**. Danach hat er per Chrome-DevTools/Frida **vollen Zugriff auf das JS-`AndroidBridge`-Interface**: `setMainPin()` (PIN-Reset ohne Alt-PIN), `verifyMainPin()` im Loop (kein Lockout), `aesDecrypt()`/`aesEncrypt()` (Passphrasen/Plaintext abfangen), `triggerIfDuress()` (Wipe provozieren), und die komplette DOM-/`localStorage`-Auslese (inkl. Decoy-Payload).

### Vektor B — Runtime-Hooking der Krypto (Frida/Xposed)
Auch ohne Repackaging: `frida-gadget` (nicht über TracerPid/Port 27042 erkennbar) hakt `AesGcmCrypto.deriveKey`/`decryptFromBase64` oder `AndroidBridge.aesDecrypt` und **fängt Passphrase und Klartext im Moment der Nutzung ab** — die RAM-Nullung greift erst *danach*. Alternativ zwingt der Angreifer den App-Prozess, die `EncryptedSharedPreferences` zu entschlüsseln (Hardware-Keystore hilft, solange der Prozess selbst zur Entschlüsselung gezwungen wird, nicht).

### Vektor C — Offline-Brute-Force nach Ciphertext-Extraktion
Die geteilten PNGs/`.bin`-Dateien sind **Transport-Medium des Ciphertexts**. Wer die Datei abfängt (Messenger, Backup, Galerie, `/data/data/`), extrahiert Salt+Nonce+Ciphertext per dokumentiertem Format und führt eine **Offline-Brute-Force** gegen Argon2id. Die Hürde ist ausschließlich die **Passphrasen-Entropie** — und die wird nirgends erzwungen. Schwache Passwörter fallen in Minuten/Stunden.

---

## 3. Exploit-Szenario (Schritt für Schritt)

**Szenario: Geräte-/APK-Zugriff, Ziel = versteckte Dateien lesen + Duress-Wipe verhindern**

1. **APK beschaffen & zerlegen** — `apktool d` / `jadx` auf die Release-APK. R8-Obfuskation ist aktiv, aber die Bridge-Klassen sind per `@Keep`/ProGuard **unverschleiert** erhalten ([app/build.gradle.kts#L160-L197](../../app/build.gradle.kts#L160-L197)) — der Angreifer liest sofort alle Bridge-Methoden samt Namen.

2. **Anti-Tampering aushebeln** — Signatur-Prüfung ist leer (`EXPECTED_SIGNATURE_SHA256=""`), also keine Repackaging-Erkennung. `frida-gadget` wird injiziert (kein TracerPid, kein Port 27042). `IntegrityGuard` ist damit vollständig blind.

3. **Fassade öffnen & PIN neutralisieren** — Per `AndroidBridge.setMainPin("1234567890.1")` wird die Haupt-PIN **ohne Kenntnis der alten** überschrieben ([AndroidBridge.kt#L616-L625](../../app/src/main/java/com/mobile/utilities/calculator/AndroidBridge.kt#L616-L625)), oder die CSS-Klasse `decoy-active` wird entfernt. Der „Tresor“ ist offen — **ohne dass eine echte PIN gebrochen wurde**, weil die PIN nur die Sichtbarkeit der UI regelt.

4. **Passphrase abgreifen (statt zu brechen)** — Hook auf `AndroidBridge.aesDecrypt` bzw. `AesGcmCrypto.deriveKey`: Beim nächsten legitimen Entschlüsselungsvorgang des Opfers werden Passphrase **und** Klartext im Klartext abgefangen — schneller und leiser als jede Brute-Force.

5. **(Alternativ) Offline-Brute-Force** — Die geteilte `.bin`/PNG wird eingelesen, das „PB“-Magic lokalisiert, Ciphertext extrahiert, dann Argon2id-Brute-Force. Erfolgswahrscheinlichkeit = Passphrasen-Stärke (die App erzwingt keine).

6. **Duress-Wipe blockieren** — Der Angreifer hält das Gerät im Vordergrund seiner Kontrolle (Force-Stop/Reconnect), oder injiziert einen Hook in `SecureWipeWork`/`SecureDelete.wipeFilesOnly`, der die Löschroutine sofort zurückkehren lässt. Da der Fallback-Pfad ein reiner Daemon-Thread ist und der MediaStore-Pfad ohnehin **nur löscht statt zu shreddern**, bleiben die privaten Bilder forensisch rekonstruierbar (Flash-Wear-Leveling hebelt die 3 Overwrite-Pässe aus).

7. **Forensik abschließen** — Öffentliches Album (`Pictures/Calculator`) ist nur gelöscht, nicht überschrieben; private Dateien sind auf Flash rekonstruierbar. Der „PB“-Magic belegt die Existenz versteckter Daten dokumentenfest.

---

## 4. Priorisierte Härtungs-Empfehlungen (Kurzform)

| Prio | Maßnahme | Behebt |
|---|---|---|
| P0 | `RELEASE_SIGNATURE_SHA256` setzen + Signaturprüfung aktiv lassen; Frida-/Hook-Erkennung ausbauen (mehrere Ports, `/proc/self/maps`, Emulator/Root-Checks) | Vektor A |
| P0 | `setMainPin` nativ absichern (Alt-PIN erforderlich, nur bei Erststart) und `verifyMainPin` in den `BruteForceGuard` einhängen | PIN-Reset, PIN-Brute-Force |
| P0 | Mindest-Entropie der AES-Passphrase erzwingen (z. B. ≥ 12 Zeichen / zxcvbn), nicht nur „nicht leer“ | Vektor C |
| P1 | Stego-Header **mitverschlüsseln** bzw. Einbett-Position per keyed PRNG streuen; Magic entfernen/randomisieren | Target 4 Forensik-Tell |
| P1 | Duress-Wipe robuster: MediaStore-Pfad kann nicht shreddern — Erwartung dokumentieren; Fallback persistieren statt Daemon-Thread; Wipe transaktional gestalten | Target 3 |
| P2 | `pixlbit-test-release.jks` (im Workspace-Root) aus dem Baum entfernen/auslagern; `.bak`-Dateien (`index.html.phase2.bak`, `.idea/deepseek-sessions.json.bak`) und veraltete `app/release/output-metadata.json` aufräumen | Secret-/Artefakt-Hygiene |
| P2 | Kommentare/README auf Argon2id korrigieren („PBKDF2/SHA-256“-Behauptungen sind falsch) | Verwirrung vermeiden |

---

## Anhang — Belegstellen (Datei:Zeile)

- Fassade/CSS-Tresor: `app/src/main/assets/index.html:160-162, 454`
- Trigger (JS-Long-Press): `app/src/main/assets/index.html:5718-5740`
- `setMainPin` ohne Alt-PIN-Guard: `AndroidBridge.kt:616-625`, `MainPinStore.kt:54-80`
- `verifyMainPin` ohne Lockout: `AndroidBridge.kt:594-601` vs. `aesDecrypt` `410-422`
- Signatur-Prüfung deaktiviert: `IntegrityGuard.kt:59-61`, `BuildConfig.java:13`, `gradle.properties:18`
- AES-GCM/Salt/Nonce: `AesGcmCrypto.kt:22-58` · Argon2id: `Argon2Kdf.kt:13-30` · EncryptedPrefs: `SecurePrefsFactory.kt:30-68`
- Duress (Panik zuerst): `index.html:5666-5676` · Wipe: `SecureWipeWork.kt:89-121` · Shredder: `SecureDelete.kt:34-61, 102-124`
- Stego-Magic/Kopf: `StegoExtractor.kt:11-13, 68-93` · Einbettung sequenziell: `index.html:4114-4138` · 10-%-Drossel: `index.html:4012`
- Keystore/Backups: `pixlbit-test-release.jks`, `index.html.phase2.bak`, `.idea/deepseek-sessions.json.bak`
