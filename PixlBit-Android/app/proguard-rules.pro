# =============================================================================
# Calculator - R8-Regeln fuer den Release-Build
# =============================================================================
#
# Diese Datei ist das Sicherheitsnetz zwischen R8 (Shrinking/Obfuskation) und den
# Stellen, die der Compiler NICHT sieht:
#
#  1. Die WebView-Bruecke (AndroidBridge) wird aus JavaScript heraus REFLEKTIV
#     ueber Methodennamen aufgerufen (window.AndroidBridge.saveImagePrivate(...)).
#     R8 sieht diesen Aufruf nicht und wuerde die Methoden umbenennen - die
#     Bruecke faellt dann lautlos aus (kein Absturz, Speichern/Teilen/
#     Entschluesseln tut einfach nichts).
#  2. PremiumManager wird ueber die Bruecke gelesen, SecureDelete haengt am
#     Duress-/Panik-Pfad.
#
# Hinweis zur Architektur: Calculator hat KEINEN C++/NDK-Anteil und damit auch keine
# JNI-Funktionen (es gibt keine Java_...-Symbole und kein System.loadLibrary()).
# Der komplette Kern (AES-256-GCM, PBKDF2, LSB-Extraktion, Secure-Wipe) ist
# Kotlin ueber die Android-Plattform. Die einzige reflektive Grenze der App ist
# deshalb die @JavascriptInterface-Bruecke - genau sie wird hier geschuetzt.
#
# Geprueft wird der Vertrag zusaetzlich automatisch: Der Gradle-Task
# "verifyJsBridgeKept" laeuft nach jedem :app:assembleRelease direkt hinter dem
# R8-Schritt und bricht den Build ab, wenn einer der geschuetzten Namen im
# Mapping fehlt oder umbenannt wurde.

# -----------------------------------------------------------------------------
# 1) WebView <-> Java-Bruecke (AndroidBridge)
# -----------------------------------------------------------------------------
# -keep            : Klassenname bleibt erhalten (lesbare Stacktraces).
# -keepclassmembers: die von JavaScript gerufenen Methoden behalten ihre Namen.
-keep class com.mobile.utilities.calculator.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.mobile.utilities.calculator.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 1b) Generisches Netz: gilt automatisch fuer JEDE kuenftige Brueckenklasse,
# damit eine spaeter ergaenzte addJavascriptInterface()-Instanz nicht still
# bricht, nur weil hier ein Klassenname fehlt.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 1c) @Keep-Annotation als zweites, quellcode-nahes Netz: In AndroidBridge.kt
# sind die Klasse und jede Brueckenmethode mit @androidx.annotation.Keep
# markiert. Neue Brueckenmethoden sind damit schon durch die Annotation
# geschuetzt - unabhaengig davon, ob jemand diese Datei pflegt.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# -----------------------------------------------------------------------------
# 2) Secure-Wipe (Duress-/Panik-Loeschpfad)
# -----------------------------------------------------------------------------
# Wird von AndroidBridge.triggerIfDuress() aufgerufen. Ein wegoptimierter Wipe
# waere ein stiller Sicherheitsverlust (die App liefe weiter und raeumt nicht
# auf), daher Keep-Regel UND @Keep in der Klasse.
-keep class com.mobile.utilities.calculator.SecureDelete { *; }

# -----------------------------------------------------------------------------
# 3) Freemium-/Premium-Status
# -----------------------------------------------------------------------------
# PremiumManager haelt die Testphase (SharedPreferences) und den Google-Play-
# validierten Premium-Kauf-Token (EncryptedSharedPreferences) und wird ueber
# AndroidBridge.getPremiumState() aus JavaScript gelesen. Keep-Regel, damit der
# Status im Release-Build nicht durch R8 verloren geht. Freigeschaltet wird
# ausschliesslich ueber BillingManager (Google Play Billing) - einen eigenen
# Freischalt-Code gibt es bewusst nicht mehr.
-keep class com.mobile.utilities.calculator.PremiumManager { *; }

# Panik-Code: PanicCodeStore haelt Salt und SHA-256-Hash des nutzerdefinierten
# Codes. Wird direkt (nicht reflektiv) ueber AndroidBridge/MainActivity benutzt;
# die Keep-Regel ist wie bei SecureDelete/PremiumManager ein zusaetzliches Netz,
# damit die Duress-Erkennung im Release-Build nicht still verschwindet.
-keep class com.mobile.utilities.calculator.PanicCodeStore { *; }

# WorkManager instanziiert Worker ueber ihren KLASSENNAMEN: der Name wird beim
# Einplanen in der Auftrags-Datenbank abgelegt und spaeter per Class.forName
# wieder geladen. Der Name (und der oeffentliche Konstruktor) muss deshalb stabil
# bleiben, damit ein eingeplanter Wipe-Auftrag auch nach einem App-Update oder
# nach einem Prozess-Kill weiterlaeuft.
-keep class com.mobile.utilities.calculator.SecureWipeWorker { *; }

# -----------------------------------------------------------------------------
# 5) AndroidX Security-Crypto (EncryptedSharedPreferences) / Tink
# -----------------------------------------------------------------------------
# Bewusst OHNE zusaetzliche Keep-Regeln - mit Begruendung, damit hier niemand
# "auf Verdacht" Regeln ergaenzt:
#  - Die von uns genutzten Klassen (EncryptedSharedPreferences, MasterKey) werden
#    direkt aufgerufen, nicht per Reflexion - R8 behandelt sie also korrekt.
#  - Tink 1.5.0 (transitiv) enthaelt kein einziges Class.forName() (geprueft:
#    0 von 871 Klassen); Reflexion gibt es nur im mitgelieferten, geshadeten
#    Protobuf, und dessen Regeln bringt das JAR selbst mit
#    (META-INF/proguard/protobuf.pro, wird von R8 automatisch gelesen).
#  - Waeren hier pauschale Keeps fuer com.google.crypto.tink.** noetig, wuerde
#    das die APK um mehrere hundert KB vergroessern - ohne Sicherheitsgewinn.

# -----------------------------------------------------------------------------
# 4) Verwertbare Absturzberichte
# -----------------------------------------------------------------------------
# Zeilennummern bleiben erhalten, der Original-Dateiname wird ersetzt: ein
# Stacktrace zeigt damit die Zeile (Diagnose moeglich), ohne interne Pfade oder
# Modulnamen preiszugeben.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# Logcat-Eliminierung: entfernt im Release-Build ALLE android.util.Log-Aufrufe
# rueckstandslos (d/v/i/w/e) aus dem kompilierten Bytecode.
# -----------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
