# Calculator â€” DatensouverÃ¤nitÃ¤t & 

> **Alles offline. Alles sicher. VollstÃ¤ndig verschlÃ¼sselt.**

**Calculator** ist ein steganografisches Kommunikationstool fÃ¼r Android. Es versteckt
verschlÃ¼sselte Nachrichten und Audiodaten in den Pixeln gewÃ¶hnlicher Bilder â€“ und
sieht auf dem Homescreen wie ein Taschenrechner aus. Es gibt keinen Server, kein
Konto, kein Tracking und keine Internet-Berechtigung.

**Stand:** `versionName 2.8.2` Â· `versionCode 6` Â· `minSdk 26` Â· `targetSdk 36` Â· `compileSdk 36`

---

## Was ist Calculator? (Kurzbeschreibung & Manifest)

Calculator in fÃ¼nf SÃ¤tzen:

- **Offline by design.** Die App besitzt keine `android.permission.INTERNET` â€“
  sie wird beim Manifest-Merge sogar aktiv wieder entfernt. SÃ¤mtliche
  AbhÃ¤ngigkeiten (Tailwind, CryptoJS-Fallback, Lucide-Icons, Schriftarten) liegen
  lokal in `app/src/main/assets/`. Ein Netzwerkzugriff ist technisch nicht mÃ¶glich.
- **Kein Backend.** Keine Cloud, kein Nutzerkonto, keine Telemetrie, keine
  Server-Infrastruktur. Monetarisierung lÃ¤uft ausschlieÃŸlich Ã¼ber die offizielle
  Google Play Billing Library.
- **Tarnung.** Der Startbildschirm ist ein voll funktionsfÃ¤higer Taschenrechner
  mit iOS-inspiriertem Launcher-Icon. Erst die geheime Eingabe (âˆš-Unlock **mit
  Dezimaltrennzeichen** â€“ Komma-Pflicht) Ã¶ffnet das versteckte Dashboard â€“ mit
  cineastischem Ãœbergang; der Panik-Code lÃ¶scht stattdessen still und Ã¶ffnet nie.
- **Kernfunktion.** Unsichtbare Einbettung von AES-256-GCM-verschlÃ¼sselten Texten
  und Audiodaten in die niederwertigsten Bits (LSB) der RGB-Pixel eines Bildes.
- **Panik-Funktion.** Ein selbst gewÃ¤hlter Duress-Code lÃ¶st einen forensisch
  gedachten Komplett-Wipe aus (Bilder, WebStorage, Preferences) â€“ der Ã¤uÃŸerlich
  wie eine falsche Passworteingabe aussieht.

### FÃ¼r wen ist Calculator gedacht?

FÃ¼r Menschen, die vertrauliche Informationen Ã¼ber KanÃ¤le transportieren mÃ¼ssen,
die sie nicht kontrollieren (Messenger, E-Mail, Cloud-Fotos) â€“ und dabei
**DatensouverÃ¤nitÃ¤t** ernst nehmen: Der Schutz liegt in der Nachricht selbst
(AES-256-GCM), nicht in der Gnade eines Anbieters. Die Tarnung schÃ¼tzt zusÃ¤tzlich
davor, dass Ã¼berhaupt jemand nach einer versteckten Nachricht sucht.

---

## Kernfunktionen & Tarnung

### 1. Die Fassade (Tarnmodus)

- **Launcher:** `res/drawable/ic_calculator_launcher_foreground.xml` â€“ das
  klassische iPhone-Taschenrechner-Icon als Vektor (adaptives Icon): schwarze,
  flÃ¤chendeckende Kachel, runde Tasten, hellgraue Funktionszeile, dunkelgraue
  Zifferntasten, leuchtend orange Operator-Spalte mit den mathematischen
  Symbolen (`Ã· Ã— âˆ’ + =`) und bewusst **ohne Ziffern** â€“ wie das Original.
  Als kantenfÃ¼llendes **Viereck** angelegt (das System rundet nur die Ecken
  leicht ab). Eingebunden Ã¼ber `mipmap-anydpi-v26/ic_launcher.xml`, inklusive
  `<monochrome>` fÃ¼r die Themed Icons (Android 13+,
  `drawable/ic_calculator_monochrome.xml`). Bewusst **kein** Schloss-,
  Schild- oder Sicherheitssymbol.
- **OberflÃ¤che:** `assets/index.html` rendert zuerst eine bedienbare
  Taschenrechner-Fassade (inkl. Prozent-/Vorzeichen-/Rechenlogik).
- **Entsperren:** Die Wurzel-Taste `âˆš` wertet die aktuelle Taschenrechner-Eingabe
  als Code â€“ aber **nur mit Komma-Pflicht**: Die Eingabe muss ein
  Dezimaltrennzeichen (`,` oder `.`) enthalten, sonst rechnet `âˆš` wie ein echter
  Rechner die Quadratwurzel. EnthÃ¤lt die Eingabe den **Panik-Code**, lÃ¶st `âˆš`
  unsichtbar den SecureWipe aus und Ã¶ffnet das Dashboard **niemals**: die Fassade
  bleibt stehen, rechnet glaubwÃ¼rdig weiter und blendet â€“ wie nach einer falschen
  Eingabe â€“ kurz eine unauffÃ¤llige Fehlermeldung ein (`showFailedAttempt`, in der
  jeweils gewÃ¤hlten Sprache). Der normale Entsperr-Code startet den Ãœbergang ins
  Dashboard; im Modul `:matrixui` steht dafÃ¼r zusÃ¤tzlich eine wiederverwendbare
  Compose-Variante (`MatrixUnlockTransition`) bereit.
- **Panik-Code einrichten/Ã¤ndern:** beim ersten Start erzwungen (nativer, nicht
  abbrechbarer Dialog), spÃ¤ter jederzeit Ã¼ber den Button **Panik-Code Ã¤ndern** in
  der Karte â€ž2. Sicherheit & Modus" (`AndroidBridge.requestPanicCodeSetup`).
- **Screenshot-Schutz:** Sowohl `MainActivity` als auch `ShareTargetActivity`
  setzen `WindowManager.LayoutParams.FLAG_SECURE` â€“ entschlÃ¼sselte Inhalte
  erscheinen weder in Screenshots noch in der Task-Ãœbersicht.

### 2. Daten einbetten und auslesen

| Schritt | Was passiert |
|---|---|
| VerschlÃ¼sseln | Text/Audio â†’ `AndroidBridge.aesEncrypt()` â†’ AES-256-GCM-Payload (Salt \| Nonce \| Ciphertext+Tag) |
| Einbetten | `assets/index.html` (STEGO CORE v2) schreibt das Base64 des Payloads in die LSBs der RGB-KanÃ¤le (3 Bit/Pixel) bzw. in die Helligkeit (1 Bit/Pixel) |
| Sichern | Verlustfreies PNG, genau **eine** Datei, sichtbar im Album **`Pictures/Calculator`** |
| Auslesen | Datei in Calculator laden **oder** aus einer anderen App an Calculator teilen (`ShareTargetActivity` liest nativ aus) |

Der EmpfÃ¤nger braucht nur das Bild und das Passwort â€“ kein Konto, keine App-Cloud.
Erkennt Calculator keinen gÃ¼ltigen Pixel-Rahmen, sagt es das ausdrÃ¼cklich (z. B.
â€žCRC16 falsch" bei verlustbehaftet weitergeleiteten Bildern) und verwechselt es
**nicht** mit einem falschen Passwort.

### 3. Audio & Anhang

- Sprachaufnahme lÃ¤uft Ã¼ber die WebView (`getUserMedia`/`MediaRecorder`,
  `RECORD_AUDIO` als Laufzeitberechtigung).
- Audiodaten werden wie Texte behandelt: verschlÃ¼sselt und in die Pixel
  eingebettet. Audio-Nutzdaten sind Teil des Premium-Umfangs.

### 4. Panik-Wipe (Duress-Code)

- Der Nutzer legt beim **ersten App-Start** selbst einen Panik-Code fest; der
  Einrichtungsdialog ist nativ und nicht abbrechbar.
- Wird dieser Code spÃ¤ter als Passwort eingegeben, startet Calculator still den
  Komplett-Wipe (Details in der Sicherheitsarchitektur) und die OberflÃ¤che meldet
  wie gewohnt â€žPasswort falsch" â€“ fÃ¼r Beobachter ist kein Unterschied erkennbar.
- Der Code selbst wird **niemals im Klartext** gespeichert (siehe unten).

### 5. UI-Highlights

- **Matrix-Zeichenregen** beim Einbetten (in der WebView als CSS-Keyframe-Layer
  direkt auf dem Aktionsbutton â€“ lÃ¤uft auf dem Compositor-Thread weiter, wÃ¤hrend
  die native Krypto rechnet).
- **Kein doppelter Speichervorgang:** Das fertige Stego-PNG wird **exakt einmal**
  geschrieben. FrÃ¼here Versionen legten dasselbe Bild zusÃ¤tzlich in Galerie und
  Download-Ordner ab â€“ diese Wege sind entfernt.

### 3. Sprachen und Rechtliches (offline)

- **Sechs Sprachen, rein offline.** Die Sprachauswahl (Flagge + Sprachcode rechts
  oben) umfasst **Deutsch, Englisch, Russisch, FranzÃ¶sisch, Spanisch und
  Japanisch**. Die Tabellen liegen vollstÃ¤ndig in `assets/index.html`; es wird
  nichts nachgeladen. Die Auswahl steht in `localStorage` (`calc_language`),
  wirkt sofort (Markup, Platzhalter/Tooltips, Dialoge, Fehlertexte) und Ã¼berlebt
  Neustarts. Native Daten (Panik-Code, Premium-Status) bleiben davon unberÃ¼hrt.
- **SchlÃ¼ssel = deutscher Quelltext.** Ãœbersetzt wird Ã¼ber
  `I18N[<sprache>][<deutscher Text>]`. Fehlt ein Eintrag, bleibt Deutsch stehen â€“
  die OberflÃ¤che ist nie leer. Dieses Muster hÃ¤lt `translate(â€¦)`-Aufrufe und
  Markup deckungsgleich und macht den Selbsttest `selftest-i18n.js` mÃ¶glich
  (VollstÃ¤ndigkeit aller Tabellen, Platzhalter-Treue, Rechtstexte, Markup).
- **Der Panik-Dialog ist nativ und trotzdem mehrsprachig:** Die Texte stehen in
  `res/values/strings.xml` sowie `values-en`, `values-ru`, `values-fr`,
  `values-es` und `values-ja` und folgen der Systemsprache. Der Klartext des
  Codes lÃ¤uft weiterhin **nie** durch das WebView.
- **AGB + Datenschutz** sind Ã¼ber das Info-Symbol im Header erreichbar (Dialog mit
  den Reitern â€žAGB" und â€žDatenschutz", Daten in `I18N_LEGAL` â€“ ebenfalls in allen
  sechs Sprachen). Beide Texte beschreiben den realen Funktionsumfang: offline,
  kein Konto, kein Server, kein Tracking, lokale VerschlÃ¼sselung, Panik-Wipe und
  Abrechnung ausschlieÃŸlich Ã¼ber Google Play.
- **Keine Sackgassen-Dialoge:** Sprachauswahl, Rechtliches, Hinweis und Paywall
  sind scrollbar (Kopf und FuÃŸ bleiben stehen), mit grÃ¶ÃŸerer Schrift und
  groÃŸzÃ¼gigem Zeilenabstand (`#legal-body` 15 px / ZeilenhÃ¶he 1.7) sowie zwei
  gleichwertigen Auswegen: groÃŸes â€žX" oben rechts (48 Ã— 48 px) und breiter
  SchlieÃŸen-Knopf am unteren Rand. ZusÃ¤tzlich schlieÃŸt die **Android-ZurÃ¼ck-Taste
  zuerst den offenen Dialog** statt die App zu beenden (jeder Dialog legt dafÃ¼r
  einen eigenen Eintrag in der WebView-Historie an).

---

## Sicherheitsarchitektur & Kryptografie (Deep-Dive)

### Kein C++-Kern â€“ und warum das kein Nachteil ist

Es gibt in Calculator **keinen NDK-/JNI-Anteil**: kein `src/main/cpp`, kein
`CMakeLists.txt`, kein `System.loadLibrary()`, keine `Java_com_*`-Symbole, keine
nativen Bibliotheken im APK. Die komplette Sicherheitslogik ist Kotlin Ã¼ber die
Android-Plattform:

| Baustein | Aufgabe |
|---|---|
| `AesGcmCrypto` | AES-256-GCM + PBKDF2-HMAC-SHA256 (SchlÃ¼sselableitung) |
| `StegoExtractor` | nativer Port der LSB-Extraktion fÃ¼r das Share-Target |
| `SecureDelete` | 3-Pass-Shredding (Dateien, WebView-Daten, Preferences) |
| `SecureWipeWork` | WorkManager-Auftrag, der das Shredding Ã¼berlebt |
| `PanicCodeStore` | Duress-Code (Salt + SHA-256 in EncryptedSharedPreferences) |

Vorteile dieser Entscheidung: eine Sprache, ein Toolchain, **keine manuelle
Speicherverwaltung** (die klassischen C++-Fehlerklassen wie Use-after-free,
Double-free oder Leaks entfallen) und Kryptografie aus der gehÃ¤rteten
Plattform-Implementierung statt aus selbst geschriebenem Code.

Die einzige **reflektive Grenze** der App ist die WebView-BrÃ¼cke
(`@JavascriptInterface`). Genau sie wird wie eine JNI-Grenze behandelt: mit
R8-Keep-Regeln, `@Keep`-Annotationen und einem automatischen Build-Check.

### VerschlÃ¼sselung im Detail (`AesGcmCrypto.kt`)

| Parameter | Wert | BegrÃ¼ndung |
|---|---|---|
| Verfahren | AES-256-GCM (`AES/GCM/NoPadding`) | AEAD: Manipulation wird erkannt, kein Padding-Oracle |
| SchlÃ¼sselableitung | PBKDF2-HMAC-SHA256, **210.000** Iterationen, 256 Bit | OWASP-Empfehlung |
| Salt | 16 Byte, zufÃ¤llig **pro VerschlÃ¼sselung** | gleiche PasswÃ¶rter â‡’ unterschiedliche SchlÃ¼ssel |
| Nonce | 12 Byte (96 Bit), frisch via `SecureRandom` | NIST-Empfehlung fÃ¼r GCM, niemals wiederverwendet |
| Auth-Tag | 128 Bit | starke IntegritÃ¤tsprÃ¼fung |
| Payload-Layout | `[SALT 16] [NONCE 12] [CIPHERTEXT + TAG]` | ein Blob, der direkt in die Pixel passt |
| Passwort-Normalisierung | Unicode **NFC** | â€žÃ¤" als Einzelzeichen und `a`+Trema ergeben denselben SchlÃ¼ssel |
| RAM-Hygiene | Passwort-`CharArray` und abgeleitetes SchlÃ¼sselmaterial werden nach Nutzung **genullt** | SchlÃ¼sselmaterial Ã¼berlebt den Vorgang nicht im Heap |

### Steganografie: das â€žPixel-Bett v2"

```
[0..1]  Magic  'P','B'          -> erkennt Calculator-Bilder eindeutig
[2]     Version = 2
[3]     Modus   0 = RGB (3 Bit/Pixel), 1 = Graustufen (1 Bit/Pixel)
[4..7]  Nutzlast-LÃ¤nge in Bytes (Big-Endian)
[8..9]  CRC16-CCITT Ã¼ber Metadaten + Nutzlast
[10..]  Nutzlast (Base64 des AES-Payloads)
```
Steganografie
- **Verlustfreiheit ist zwingend.** Die Nutzlast steckt in den niederwertigsten
  Bits; verlustbehaftete Kompression wÃ¼rde sie zerstÃ¶ren. Deshalb prÃ¼ft die
  JS-Seite PNG-Signatur **und** IHDR-MaÃŸe gegen die Canvas-GrÃ¶ÃŸe, und die
  Kotlin-Seite validiert zusÃ¤tzlich die 8-Byte-PNG-Signatur, bevor geschrieben
  wird. Es findet **kein Re-Encoding** statt â€“ die Bytes werden 1:1 durchgeschrieben
  (kein `Bitmap.compress`, kein JPEG/WebP).
- **Kein Raten:** Der Modus wird aus der Magic gelesen statt aus dem Bildinhalt
  geschÃ¤tzt (frÃ¼here Ursache der Fehlmeldung â€žPasswort falsch"). Die CRC16 belegt
  zusÃ¤tzlich, dass das Pixel-Bett unverÃ¤ndert ist â€“ Altbestand (bis v2.8.1) wird
  weiterhin gelesen.
- **Warum es forensisch unauffÃ¤llig ist:** Direkt in den Bilddaten selbst
  eingebettete Krypto-Daten haben keine Struktur mehr, die man einem Container
  zuordnen kÃ¶nnte. Ohne Passwort bleibt nur statistisches Rauschen in den
  niederwertigsten Bits â€“ ein Bild unter Milliarden.

### Duress-Code: niemals im Klartext (`PanicCodeStore.kt`)

- Ablage in **`EncryptedSharedPreferences`** (AES-256-GCM; Keystore-SchlÃ¼ssel via
  `MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)`).
- Gespeichert werden ausschlieÃŸlich **32 Byte Zufalls-Salt** und
  **`SHA-256(Salt â€– Code)`** (Base64) â€“ der Klartext existiert nur wÃ¤hrend der
  Eingabe und wird danach auch aus den Eingabefeldern gelÃ¶scht.
- Vergleich **konstantzeitig** (`MessageDigest.isEqual`).
- Einrichtung erzwungen: Beim ersten Start erscheint ein **nativer, nicht
  abbrechbarer** Dialog; der Code lÃ¤uft nie durch das WebView und ist auch nicht
  im APK enthalten â€“ er ist pro Installation nutzerdefiniert.

### Panik-Wipe: unaufhaltsam, aber unsichtbar

Ablauf bei Eingabe des Panik-Codes (`AndroidBridge.triggerIfDuress`):

0. **Komma-Pflicht:** Nur eine Eingabe mit Dezimaltrennzeichen (`,`/`.`) kann ein
   Code sein; ohne Trenner wird gar nicht erst geprÃ¼ft. Bei Treffer bleibt das
   Dashboard verschlossen â€“ die Fassade rechnet unauffÃ¤llig weiter und die
   OberflÃ¤che Ã¶ffnet sich **niemals**.
1. `PanicCodeStore.verify(...)` â†’ bei Treffer
2. `PanicCodeStore.clear()` â€“ Salt/Hash entfernen
3. `SecureDelete.deleteWebStorageData()` â€“ **synchron im UI-Thread** (Vorgabe der WebStorage-API)
4. `SecureWipeWork.enqueueOrFallback(...)` â€“ Datei-Shredding als **Expedited Work**:
   - `CoroutineWorker` Ã¼ber **WorkManager**, `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`
   - Der UI-Thread bleibt frei (kein ANR), und ein **App-Kill bricht den Auftrag nicht ab**: WorkManager persistiert ihn und setzt ihn nach Kill bzw. GerÃ¤te-Neustart fort
   - bis zu 5 Versuche mit exponentiellem Backoff, jeder Lauf **idempotent**
   - unterhalb API 31 lÃ¤uft die Arbeit als stiller Vordergrunddienst (`dataSync`, `IMPORTANCE_LOW`, kein Ton/Badge)
5. GelÃ¶scht werden (3-Pass-ZufallsÃ¼berschreibung + Truncate + Delete pro Datei):
   Ã¶ffentliches Album `Pictures/Calculator`, interner Album-Ordner, Altbestand im
   app-externen Ordner, `shared_prefs`, `app_webview/`, `databases/`.
   **Einzige Ausnahme:** die WorkManager-Datenbank â€“ sie gehÃ¶rt dem laufenden
   Auftrag und enthÃ¤lt nur Auftrags-Metadaten.

**Ehrliche Grenze:** Bei Flash-Speicher (Wear-Leveling) ist ein physisches
Ãœberschreiben derselben Zellen nicht garantiert; Ã¼ber die MediaStore-API gar nicht
mÃ¶glich â€“ dort werden Eintrag und Datei entfernt. FÃ¼r eine App-interne
Panik-LÃ¶schung ist das mehrfache Ãœberschreiben dennoch die praktikable Best Practice.

### Wo Bilder liegen â€“ und warum

| Ort | Zweck |
|---|---|
| **`Pictures/Calculator`** (Ã¶ffentlich, MediaStore-Bucket) | Der eigentliche Zielort: dein Bild muss fÃ¼r Galerie und Dateimanager auffindbar sein. API 29+: MediaStore mit `RELATIVE_PATH` + `IS_PENDING` (Datei bleibt unsichtbar, bis sie vollstÃ¤ndig geschrieben ist), API â‰¤ 28: Ã¶ffentlicher Bilderordner + `MediaScannerConnection.scanFile` |
| `filesDir/Pictures/Calculator` (intern) | **Nur Notfallablage**, falls Ã¶ffentliches Schreiben fehlschlÃ¤gt â€“ damit die Arbeit nie verloren geht; auÃŸerdem Altbestand vor der Umstellung |
| `Downloads` (Ã¶ffentlich) | AusschlieÃŸlich fÃ¼r echte Datei-Downloads aus der WebView (`blob:`/`data:`) |

Weitere HÃ¤rtungen rund um Dateien:

- **Path-Traversal-Schutz:** Von JavaScript gelieferte Dateinamen werden auf einen
  Basisnamen reduziert (`sanitizeFileName`) und anschlieÃŸend **kanonisch** gegen
  den Zielordner geprÃ¼ft (`albumFileFor`) â€“ ein Name wie `../../shared_prefs/x.xml`
  kann nichts auÃŸerhalb des Albums adressieren.
- **FileProvider minimal:** `res/xml/file_paths.xml` gibt genau **einen** Ordner
  frei (`files-path Pictures/Calculator/`) statt eines generischen `Pictures/`.
- **Offline-Garantie im Download-Pfad:** `MainActivity.enqueueDownloadManager()`
  wirft fÃ¼r jedes Schema auÃŸer `content://`/`file://` eine `SecurityException`.
  Wichtig, weil der System-DownloadManager in einem eigenen Prozess **mit**
  Internet-Berechtigung lÃ¤uft â€“ das fehlende `INTERNET` der App allein wÃ¼rde einen
  Netzabruf also nicht verhindern. Der Aufruf kommt aus der WebView und wird dort
  gefangen (Hinweis per Toast, kein Absturz).
- **Teilen ohne Rekompression:** Der Share-Intent nutzt bewusst
  `application/octet-stream` statt `image/png`, damit WhatsApp & Co. die Datei als
  Dokument behandeln und die Pixel nicht neu komprimieren.

### WebView-HÃ¤rtung

- Assets werden Ã¼ber `WebViewAssetLoader` unter
  `https://appassets.androidplatform.net/assets/` ausgeliefert (sicherer Kontext fÃ¼r
  Clipboard- und Storage-APIs) statt Ã¼ber `file://`.
- `setAllowUniversalAccessFromFileURLs(false)`, `cacheMode = LOAD_NO_CACHE`,
  keine fremden JS-Interfaces â€“ nur die eigene Bridge mit dem Namen `AndroidBridge`.
- Beim Wechsel in den Hintergrund und beim Beenden werden WebStorage, Cache,
  Formulardaten und Historie geleert (`wipeSensitiveData()`, `onDestroy()`).
- Die Seite prÃ¼ft vor jedem Bridge-Aufruf `typeof window.AndroidBridge â€¦` und lÃ¤uft
  damit auch im normalen Browser (Blob-Download-Fallback).

### R8/Obfuskation als Ã¼berprÃ¼fbarer Vertrag

- **Release:** `isMinifyEnabled = true`, `isShrinkResources = true`,
  `isDebuggable = false`, `proguard-android-optimize.txt` + `proguard-rules.pro`.
  Das `debuggable`-Flag steht explizit im Skript (statt nur als AGP-Default), und
  geprÃ¼ft wird das **gemergte** Release-Manifest â€“ so fÃ¤llt auch ein von einer
  Bibliothek eingeschleustes `android:debuggable="true"` auf.
- **WebView-Debugging nur im Debug-Build:**
  `WebView.setWebContentsDebuggingEnabled(true)` steht in `MainActivity` hinter
  `if (BuildConfig.DEBUG)`. Im Release ist `BuildConfig.DEBUG` konstant `false`,
  R8 entfernt den Zweig vollstÃ¤ndig â€“ die Release-APK kann keinen Debug-Port
  Ã¶ffnen. Damit `BuildConfig` Ã¼berhaupt existiert, setzt `buildFeatures` in
  `app/build.gradle.kts` `buildConfig = true` (ab AGP 8 wird die Klasse sonst
  nicht mehr generiert).
- **Keep-Netze:** explizite `-keep`-Regeln fÃ¼r `@android.webkit.JavascriptInterface`
  (klassen- und generisch), `@Keep`-Annotationen an Klasse und jeder BrÃ¼ckenmethode,
  sowie Keep-Regeln fÃ¼r `SecureDelete`, `PremiumManager`, `PanicCodeStore` und
  `SecureWipeWorker`.
- **Automatischer Build-Check:** Der Gradle-Task **`verifyJsBridgeKept`** lÃ¤uft nach
  `minifyReleaseWithR8` und **bricht den Build ab**, wenn einer der geschÃ¼tzten Namen
  im Mapping fehlt oder umbenannt wurde (4 Klassen, 14 BrÃ¼ckenmethoden + Worker).
  Damit kann die WebView-BrÃ¼cke nicht mehr â€žlautlos" ausfallen, und ein
  WorkManager-Auftrag findet seinen Worker nach einem Update weiterhin.
- **Zweiter Build-Check `verifyReleaseHardening`:** prÃ¼ft nach dem Manifest-Merge
  das Release-Manifest auf `android:debuggable="true"` und das generierte
  `BuildConfig.java` auf `DEBUG = false`. Kippt jemand `isDebuggable` um oder
  schleust eine Bibliothek debuggable ein, schlÃ¤gt der Release-Build fehl, statt
  eine ungehÃ¤rtete APK zu erzeugen.

### Bedrohungsmodell: was Calculator schÃ¼tzt â€“ und was nicht

**SchÃ¼tzt:** Vertraulichkeit und IntegritÃ¤t des Inhalts (AES-256-GCM), Transport
Ã¼ber fremde KanÃ¤le, UnauffÃ¤lligkeit (kein Container-Header, kein Cloud-Upload),
Screenshots und Task-Vorschauen (`FLAG_SECURE`), gezielte Auswertung eines
beschlagnahmten GerÃ¤ts (Panik-Wipe), versehentlichen Netzwerkabfluss (keine
`INTERNET`-Berechtigung).

**SchÃ¼tzt nicht / Grenzen:** GerÃ¤te mit Root oder Malware kÃ¶nnen RAM/Keystore
angreifen; ein schwaches Passwort ist bei vorliegender Bilddatei offline
angreifbar; Messenger-/Social-Media-Kompression zerstÃ¶rt die LSBs (deshalb immer
â€žals Datei/Dokument" versenden); Kanal-Metadaten (wer sendet wann ein Bild an wen)
bleiben sichtbar; bei Flash-Speicher ist physisches Ãœberschreiben nicht garantiert.

---

## Code-Struktur & Build-Prozess (Hinweise fÃ¼r Entwickler)

### Module

| Modul | Inhalt |
|---|---|
| `:app` | Die Android-App: WebView-Fassade, nativer Sicherheitskern, Bridge, Billing. `applicationId = com.mobile.utilities.calculator` (Debug-Builds zusÃ¤tzlich `.debug`) |
| `:matrixui` | EigenstÃ¤ndiges Compose-Modul mit Matrix-Zeichenregen + cineastischem Unlock-Ãœbergang. **Bewusst ohne AbhÃ¤ngigkeit zu `:app`** â€“ dadurch nicht Teil des App-APKs, sondern in jedes Compose-Projekt Ã¼bernehmbar (`implementation(project(":matrixui"))`). In der App selbst lÃ¤uft der Regen als CSS-Keyframe-Layer in `index.html` |

### Der native Sicherheitskern (`app/src/main/java/com/mobileutilities/calc/`)

| Datei | Rolle |
|---|---|
| `MainActivity.kt` | WebView-Host, Dateiauswahl, Download-Pfade, Offline-Garantie, Panik-Code-Dialog (nativ, Texte aus `strings.xml`) |
| `AndroidBridge.kt` | Die **einzige** JS-BrÃ¼cke: Speichern, Teilen, AES, Premium, Mikrofon, Panik-Code (14 `@JavascriptInterface`-Methoden) |
| `AesGcmCrypto.kt` | AES-256-GCM + PBKDF2-HMAC-SHA256 (210.000 Iterationen), NFC-Normalisierung, RAM-Hygiene |
| `StegoExtractor.kt` | Nativer LSB-Extraktor (Magic/Version/Modus/CRC16) fÃ¼r geteilte Bilder |
| `SecureDelete.kt` | 3-Pass-Shredding, Verzeichnis-Rekursion, Bereichs-Wipes |
| `SecureWipeWork.kt` | `CoroutineWorker` + Enqueue als **Expedited Work** (WorkManager) |
| `PanicCodeStore.kt` | Duress-Code: Salt + SHA-256 in `EncryptedSharedPreferences` |
| `PremiumManager.kt` | Freemium-Zustand (50-WÃ¶rter-Limit, Preis-Label) + Premium-Kauf-Token in `EncryptedSharedPreferences` |
| `BillingManager.kt` | Google Play Billing (Einmalkauf, Wiederherstellung, Acknowledge) |
| `ShareTargetActivity.kt` | EmpfÃ¤ngt Bilder aus anderen Apps (`ACTION_SEND`), OOM-gehÃ¤rtet |

### Assets und Konfiguration

| Datei | Rolle |
|---|---|
| `assets/index.html` | Gesamte OberflÃ¤che **und** der STEGO CORE (Encoder/Decoder in JavaScript) |
| `assets/vendor/**` | Lokale AbhÃ¤ngigkeiten (Tailwind, CryptoJS-Fallback, Lucide-Icons, Webfonts) â€“ kein CDN |
| `res/xml/file_paths.xml` | FileProvider-Umfang (exakt `Pictures/Calculator/`) |
| `res/values*/strings.xml` | Alle nativen Texte (u. a. der Panik-Dialog und das Share-Target) â€“ **in 6 Sprachen** Ã¼ber `values-en`, `values-ru`, `values-fr`, `values-es`, `values-ja` |
| `proguard-rules.pro` | Keep-Regeln fÃ¼r die JS-BrÃ¼cke, Wipe- und Panik-Code-Logik |
| `app/build.gradle.kts` | R8-Konfiguration, Dependencies, Gradle-Task `verifyJsBridgeKept` |

### Voraussetzungen & Build

```bash
# JDK 17, Android SDK mit compileSdk 36 (AGP 8.13.2, Kotlin 2.4.0, Gradle 8.13)
cd Calculator-Android

./gradlew :app:assembleDebug            # Debug-APK (ohne R8, debuggable)
./gradlew :app:installDebug             # baut und installiert auf angeschlossenem GerÃ¤t
./gradlew :app:assembleRelease           # Release: R8 + shrinkResources + verifyJsBridgeKept + verifyReleaseHardening
./gradlew :app:verifyJsBridgeKept        # nur der BrÃ¼cken-/Mapping-Check
./gradlew :app:verifyReleaseHardening    # nur debuggable/BuildConfig.DEBUG-Check
./gradlew :matrixui:testDebugUnitTest    # Unit-Tests des Animationsmoduls
```

Die Release-APK entsteht als
`app/build/outputs/apk/release/app-release-unsigned.apk` â€“ sie ist **unsigniert**
und muss vor der Verteilung signiert werden (siehe To-Dos).

### Dependencies (Auszug)

`androidx.core:core-ktx` Â· `androidx.appcompat` Â· `androidx.webkit` (WebViewAssetLoader) Â·
`com.google.android.material` Â· `com.android.billingclient` (Play Billing) Â·
`androidx.security:security-crypto` (EncryptedSharedPreferences) Â·
`androidx.work:work-runtime` (Expedited Work). Kein Analytics-, Crash- oder
Netzwerk-SDK.

---

## Berechtigungen & Offline-Garantie

| Berechtigung | Zweck |
|---|---|
| *(`android.permission.INTERNET` â€“ **entfernt**)* | wird beim Manifest-Merge aktiv gelÃ¶scht (`tools:node="remove"`), damit NachprÃ¼fbarkeit gilt: ohne diese Berechtigung ist Netzverkehr technisch unmÃ¶glich |
| `com.android.vending.BILLING` | In-App-Kauf Ã¼ber den Play-Store-Dienst (IPC; die Zahlung wickelt die Play-Store-App ab) |
| `RECORD_AUDIO` | Sprachaufnahme Ã¼ber `getUserMedia` (Laufzeitdialog) |
| `MODIFY_AUDIO_SETTINGS` | sauberer Auf-/Abbau des Aufnahme-Kanals in der WebView |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`) | nur Android 9 und Ã¤lter (Ã¶ffentlicher Bilder-/Download-Ordner) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | stiller Vordergrunddienst, damit der Panik-Wipe auf Ã¤lteren Versionen zuverlÃ¤ssig durchlÃ¤uft (normal-Permissions, keine Dialoge) |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | kommen mit WorkManager: Das GerÃ¤t darf wÃ¤hrend des Shredder-Laufs nicht schlafen, und der Auftrag wird nach einem Neustart fortgesetzt |

ZusÃ¤tzlich: `android:allowBackup="false"` sowie `dataExtractionRules` /
`fullBackupContent` schlieÃŸen Cloud-Backup, â€žadb backup" und
GerÃ¤te-zu-GerÃ¤te-Transfer vollstÃ¤ndig aus.

---

## Monetarisierung & Promo-Codes

- **Kein Abo, kein eigener Server, kein Backend.** Der komplette Zahlungsvorgang
  lÃ¤uft im nativen Google-Play-Dialog: `BillingManager` mit dem nicht-verbrauchenden
  Einmalkauf `PRODUCT_ID_PREMIUM = "calc_premium_lifetime"`, inklusive
  Wiederherstellung nach Neuinstallation und `acknowledgePurchase()` (sonst
  erstattet Google nach drei Tagen automatisch). Nach BestÃ¤tigung durch Google
  Play wird ausschlieÃŸlich der **validierte Kauf-Token** gespeichert â€“ kein
  einfaches Boolean-Flag.
- **Freemium:** Bis zu **50 WÃ¶rter** pro Nachricht sind dauerhaft kostenlos
  (`PremiumManager.MAX_FREE_WORDS`). Audio-Nutzdaten sind Premium. Es gibt
  **keinen zeitbasierten Probezeitraum** und keine Timer-Sperre.
- **Premium (Lifetime):** Einmalkauf **12,99 â‚¬**. Der angezeigte Preis kommt
  lokalisiert von Google Play und wird nur als Anzeigewert zwischengespeichert
  (`PRICE_LABEL` ist der RÃ¼ckfallwert). Premium schaltet das WÃ¶rter-Limit und die
  AudioverschlÃ¼sselung frei.
- **Promo-Codes:** Werden **ausschlieÃŸlich im Google-Play-Kaufdialog** eingelÃ¶st.
  Es gibt bewusst kein eigenes Code-Feld in der App: Ein selbst gebauter
  Freischalt-Code mÃ¼sste das Geheimnis mitliefern und wÃ¤re damit ein
  Sicherheitsrisiko.
- **Zustandsspeicherung:** Der Premium-Status gilt **nur dann** als aktiv, wenn ein
  von Google Play validierter **Kauf-Token** fÃ¼r `calc_premium_lifetime`
  vorliegt. Der Token wird in **`EncryptedSharedPreferences`** abgelegt
  (AES-256-GCM, SchlÃ¼ssel im Android-Keystore) â€“ nicht als Boolean-Flag in einer
  normalen `SharedPreferences`-XML-Datei, die sich trivial auf `true` setzen
  lieÃŸe. Das WebView kann den Status nur **lesen** (`getPremiumState`), nie setzen;
  ein Manipulieren von `localStorage` Ã¤ndert daran nichts. Beim **Panik-Wipe**
  wird das gesamte `shared_prefs/`-Verzeichnis geschreddert â€“ die verschlÃ¼sselte
  Premium-Ablage wird dabei automatisch mitgelÃ¶scht.

---

## Selbsttests (ohne Android-Toolchain)

Alle PrÃ¼fungen extrahieren den **echten** Code aus `assets/index.html` und fÃ¼hren
ihn in einer Node-VM mit DOM-Stubs aus â€“ es wird keine Logik nachgebaut:

| Aufruf | PrÃ¼ft |
|---|---|
| `node selftest-camouflage.js` | Tarnfassade: Grundrechenarten, Komma-Pflicht, Panik-Pfad (App bleibt zu), 3-Sekunden-Ãœbergang, Zeichenregen |
| `node selftest-stego.js` | Pixel-Bett v2: Magic/Modus/CRC16, RundlÃ¤ufe beider Modi, Altformat |
| `node selftest-freemium.js` | 50-WÃ¶rter-Limit, Audio-Regel, Premium-Status, Mikrofon-Fehlertexte |
| `node selftest-i18n.js` | 6 Sprachen: VollstÃ¤ndigkeit der Tabellen, Platzhalter-Treue, AGB/Datenschutz, Markup der Sprachauswahl |

---

## ZukÃ¼nftige To-Dos

1. **Panik-Code-Hinweis:** dezenter Hinweis im Dashboard, solange noch kein
   Panik-Code gesetzt ist (der Dialog selbst ist bereits erzwungen und Ã¼ber den
   Button â€žPanik-Code Ã¤ndern" jederzeit erreichbar).
2. **Hash hÃ¤rten:** PBKDF2/Argon2 mit Arbeitsfaktor statt einfachem SHA-256 â€“
   Schutz gegen Offline-Brute-Force eines schwachen Panik-Codes.
3. **Release-Signierung:** `signingConfig` + Keystore etablieren (Upload-Key gehÃ¶rt
   nicht ins Repository), Versionierung und Artefakt-Benennung je Release.
4. **Automatisierte Tests:** JVM-Unit-Tests fÃ¼r AES-Roundtrip, Stego-Ein-/Auslesen
   (Magic, CRC, beide Modi) und `sanitizeFileName`; instrumentierte Tests fÃ¼r den
   MediaStore-Speicherpfad.
5. **Tarnung feinschleifen:** Launcher-Label/Icon je Build-Variante konfigurierbar
   machen und die Taschenrechner-Fassade weiter ausbauen (GlaubwÃ¼rdigkeit).
6. **Barrierefreiheit:** Kontrast-/TalkBack-PrÃ¼fung. Die Lokalisierung ist
   umgesetzt (WebView in 6 Sprachen, `strings.xml` inklusive
   `values-en/-ru/-fr/-es/-ja`, Selbsttest `selftest-i18n.js`).
7. **GroÃŸe TrÃ¤gerbilder:** klarere Warnung und Speicher-Grenzen im UI
   (OOM-Schutz existiert bereits im nativen Pfad).
8. **Optional:** verschlÃ¼sselte, versteckte Zweitablage parallel zum Ã¶ffentlichen
   Album â€“ fÃ¼r Nutzer, die Auffindbarkeit **und** Verborgenheit gleichzeitig wollen.

---

## Lizenz & Kontakt

Es liegt derzeit **keine `LICENSE`-Datei** im Repository â€“ vor einer
VerÃ¶ffentlichung sollte die Lizenzform festgelegt werden. Kontakt/Projektname:
**Calculator** (CalcGuard). Diese README beschreibt den Stand des Codes im
Repository; sicherheitsrelevante Aussagen sind im Quelltext mit BegrÃ¼ndung
kommentiert, damit sie Ã¼berprÃ¼fbar bleiben.

> **Alles offline. Alles sicher. VollstÃ¤ndig verschlÃ¼sselt.**




