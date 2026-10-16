# PIXLBIT — Feature & Security Factsheet

> **Für Endnutzer und Security-Tester.** Dieses Dokument erklärt verständlich,
> was die App kann, wie sie sich tarnt und welche Sicherheitsmechanismen im
> Hintergrund arbeiten.

---

## 1. Kernfunktion & Tarnung

- **Taschenrechner-Fassade:** Die App präsentiert sich als voll funktionsfähiger
  Taschenrechner im klassischen iOS-Stil — mit Ziffern, Grundrechenarten
  (`+ − × ÷`), Komma, `=` und `AC`. Sie rechnet echt und verhält sich wie ein
  normaler Rechner.
- **Unauffälliger Name & Icon:** Der Launcher-Name ist neutral lokalisiert
  („Taschenrechner", „Calculator", „Calculadora", „Калькулятор", …). Kein
  Schloss- oder Schild-Symbol — die App ist auf dem Homescreen **nicht** als
  Sicherheits-App erkennbar.
- **Versteckter Zugang:** Ein **3-Sekunden-Long-Press auf die Wurzel-Taste (√)**
  öffnet den verborgenen Bereich. Ein kurzer Tipp auf `√` rechnet nur die
  normale Quadratwurzel — kein Hinweis auf den versteckten Inhalt.
- **Unsichtbare Daten (Steganografie):** Nachrichten, Texte und Audiodaten werden
  **in den niederwertigsten Bits (LSB) harmloser Bilder versteckt**. Für
  Außenstehende ist das Trägerbild ein normales Foto — die geheimen Daten sind
  darin nicht sichtbar.

---

## 2. Zero-Trust & Offline-Garantie

- **Komplett offline:** Die App besitzt **keine `INTERNET`-Berechtigung** (hart
  per `tools:node="remove"` aus dem Manifest entfernt).
- **Kein Nach-Hause-Telefonieren:** Die App kann technisch keine Daten an einen
  Server senden.
- **Kein Cloud-Backup:** `allowBackup=false` + Daten-Extraktionsregeln schließen
  Cloud-, D2D- und ADB-Backup aus.
- **Kein Tracking:** Es gibt kein Konto, keine Telemetrie und keine Analyse-Tools.
- **Einzige Ausnahme (offline-neutral):** Der Einmalkauf läuft über Google Play
  Billing — der Netzwerkkontakt liegt dabei bei Google, nicht bei der App.

---

## 3. Verschlüsselung & PIN-Sicherheit

- **AES-256-GCM:** Alle versteckten Inhalte werden mit AES-256 im GCM-Modus
  (128-bit Integritäts-Tag) verschlüsselt.
- **Schlüsselableitung (Argon2id):** Die PIN wird mit **Argon2id** (speicherhart:
  64 MiB RAM, 3 Iterationen, 4 Lanes) gehasht. Das macht GPU-/ASIC-Brute-Force
  praktisch unmöglich. *(Hinweis: ersetzt das frühere PBKDF2.)*
- **PIN-Format:** Mindestens **12 Zeichen**, ausschließlich **Ziffern (0-9)** und
  **Dezimaltrennzeichen (`,` oder `.`)**. Das Dezimaltrennzeichen ist Pflicht
  (zusätzliche Tarnung: die PIN sieht aus wie eine Rechnung).
- **Sichere Ablage:** Die PIN wird nie im Klartext gespeichert — nur als
  Salt + Hash in **EncryptedSharedPreferences** (Schlüssel im Android-Keystore,
  bevorzugt **StrongBox**-Hardware-Chip, sonst TEE).
- **Konstantzeitiger Vergleich:** Die Prüfung nutzt `MessageDigest.isEqual`,
  damit keine Zeitmessung (Timing-Attacke) die PIN verrät.

---

## 4. Anti-Brute-Force & Schutz vor Angriffen

- **Stiller Lockout:** Nach **5 Fehlversuchen** wird die Code-Prüfung für
  **5 Minuten** still gesperrt (rein timestamp-basiert — kein Warten, keine
  Blockade der Oberfläche).
- **Verschlüsselter Zähler:** Der Lockout-Zustand liegt in verschlüsselten
  `EncryptedSharedPreferences` (StrongBox/TEE) — ein Angreifer kann ihn nicht
  einfach von außen zurücksetzen.
- **„Silent Fail":** Bei falscher PIN passiert **nichts Sichtbares** — keine
  Fehlermeldung, keine Reaktion. Die App bleibt ein glaubwürdiger Rechner und
  erregt keinen Verdacht.

---

## 5. Notfall-Protokoll (Zwangslöschung / Duress)

- **Panik-Code:** Ein separat hinterlegter Notfall-Code löst beim Eingeben
  (3-s-Long-Press auf `√`) die **sofortige, physische Datenvernichtung** aus
  (**3-Pass-Shreddern** über `SecureWipeWork`).
- **Immer verfügbar (Priorisierung):** Der Notfall-Wipe funktioniert **auch bei
  aktivem Brute-Force-Lockout** — die Duress-Prüfung steht **vor** der Sperre.
  Ein Angreifer kann den Notfall-Wipe also nicht durch provozierte Fehlversuche
  blockieren.
- **Unauffälliger Reset:** Das Display wird unmittelbar auf **„0"** zurückgesetzt
  (wie die `AC`-Taste) — kein Hinweis, keine Meldung, kein Aufhänger.

---

## 6. Systemschutz

- **`FLAG_SECURE`:** Blockiert Screenshots, Bildschirmaufnahmen und
  Task-Manager-Vorschauen des App-Inhalts.
- **Zusätzlich aktiv (über die Vorgabe hinaus):**
  - **StrongBox/TEE-Master-Keys** mit eigener Schlüssel-Isolation pro
    Präferenzdatei.
  - **Anti-Debug / Anti-Frida:** Erkennung von Debugger, `TracerPid` und
    Frida-Port — bei Verdacht sofortiger stiller Abbruch.
  - **Anti-Repackaging:** Laufzeit-Signatur-Attestierung (SHA-256 des
    Zertifikats) gegen manipulierte APKs.
  - **Tapjacking-Schutz** (`filterTouchesWhenObscured`) gegen Overlay-Angriffe.
  - **Face-Down-Lock:** Legt man das Gerät mit dem Display nach unten, wird die
    Sitzung sofort beendet und zum Rechner zurückgekehrt.
  - **Zwischenablage-Isolation:** Kopierte Geheimnisse werden als „sensibel"
    markiert (Android 13+) und nach **20 Sekunden** automatisch geleert.
  - **R8-Obfuskation** und vollständige **Logcat-Eliminierung** im Release-Build.

---

## Hinweis zum aktuellen Stand (ehrlich)

Die ursprüngliche Spezifikation nannte **PBKDF2 (210.000 Iterationen)** und eine
**Mindestlänge von 8 Zeichen**. Der finale Code ist **weitergehärtet**:
**Argon2id** (speicherhart) und **12 Zeichen** Mindestlänge. Der App-Name wurde
im Rahmen der Tarnung auf neutrale Rechner-Bezeichnungen umgestellt
(„Calculator"/„Taschenrechner"); **PIXLBIT** ist der interne Projektname.
