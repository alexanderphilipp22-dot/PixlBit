plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signatur-Fingerprint (SHA-256, ohne Doppelpunkte) fuer die Laufzeit-Attestierung.
// Quelle: gradle.properties (RELEASE_SIGNATURE_SHA256=...) oder
// -PRELEASE_SIGNATURE_SHA256=... . Leer => Signaturpruefung uebersprungen.
val signatureHash: String = (project.findProperty("RELEASE_SIGNATURE_SHA256") as String?) ?: ""

android {
    namespace = "com.mobile.utilities.calculator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mobile.utilities.calculator"
        minSdk = 26
        targetSdk = 36
        // versionCode 7 / 2.8.3: Update-Build (Clean + Versions-Bump fÃ¼r Deployment).
        versionCode = 7
        versionName = "2.8.3"

    }

    buildTypes {
        release {
            // isDebuggable = false ist der AGP-Default, steht hier aber BEWUSST
            // explizit: nur so ist im Build-Skript nachpruefbar, dass die
            // Release-APK kein android:debuggable="true" traegt. Ein debuggable
            // Release waere ein direkter Datenschutz-Bruch - jeder koennte per
            // "adb shell run-as com.mobile.utilities.calculator" in die
            // App-Daten sehen (SharedPreferences mit Premium-/Panik-Code-Status,
            // private Stego-Bilder), ohne entschluesseln zu muessen. Zusaetzlich
            // prueft der Task verifyReleaseHardening (siehe unten) das GEMERgte
            // Release-Manifest - damit faellt auch ein von einer Bibliothek
            // eingeschleustes debuggable auf.
            isDebuggable = false
            // TEST-BUILD-Freischaltung: Im Release bleibt Premium an den Google-Play-
            // Kauf-Token gebunden (TEST_BUILD_UNLOCKED = false). Nur der Debug-Build
            // (Sideload-Test-APK) setzt den Wert auf true (siehe unten).
            buildConfigField("boolean", "TEST_BUILD_UNLOCKED", "false")
            buildConfigField("String", "EXPECTED_SIGNATURE_SHA256", "\"$signatureHash\"")
            // R8 ist bewusst AKTIV: Code-Shrinking + Obfuskation
            // (isMinifyEnabled) und Ressourcen-Shrinking (isShrinkResources).
            // Der Vertrag mit dem einzigen reflektiven Teil der App (der
            // WebView-Bruecke AndroidBridge) steht in proguard-rules.pro.
            // Zusaetzlich prueft der Task verifyJsBridgeKept (siehe unten) nach
            // jedem Release-Build das R8-Mapping und bricht ab, wenn ein
            // Brueckenname fehlt oder umbenannt wurde.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Kein signingConfig: der Release-Build erzeugt bewusst eine
            // UNSIGNIERTE APK (app-release-unsigned.apk). Signiert wird mit dem
            // eigenen Keystore, damit kein Upload-Key im Projekt landet.
        }
        debug {
            // Debug bewusst OHNE R8: ungekuerzt, debuggable und schnell baubar.
            // Die Obfuskation wird deshalb ueber den Release-Build geprueft
            // (verifyJsBridgeKept).
            isMinifyEnabled = false
            // Explizit debuggable: nur damit sind adb-Debugging, aussagekraeftige
            // Logcat-Details und der Chrome-Web-Inspector nutzbar. Das Gate fuer
            // WebView.setWebContentsDebuggingEnabled (siehe MainActivity.onCreate)
            // haengt genau an diesem Flag - nicht an einer eigenen Konstante.
            isDebuggable = true
            applicationIdSuffix = ".debug"
            // TEST-BUILD (Sideload, nicht ueber Google Play): schaltet Premium und
            // damit das 50-Woerter-Limit sowie alle Audio-Sperren dauerhaft frei,
            // ohne dass ein Google-Play-Kauf noetig ist. Gelesen von
            // PremiumManager.buildStateJson()/buildFailClosedStateJson().
            buildConfigField("boolean", "TEST_BUILD_UNLOCKED", "true")
            buildConfigField("String", "EXPECTED_SIGNATURE_SHA256", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = false
        // BuildConfig MUSS erzeugt werden: das Debug-Gate in MainActivity
        // (if (BuildConfig.DEBUG) { WebView.setWebContentsDebuggingEnabled(true) })
        // und die Pruefung in verifyReleaseHardening lesen die generierte Klasse.
        // Ab AGP 8 ist die Generierung standardmaessig DEAKTIVIERT - ohne dieses
        // Flag scheitert der Build mit "Unresolved reference: BuildConfig".
        buildConfig = true
    }

    // Assets (index.html, vendor/, Icons) sind bereits vorkomprimiert/klein;
    // nichts hier zusaetzlich komprimieren lassen, das die WebView-Ladezeit erhoehen wuerde.
    androidResources {
        noCompress += listOf("png")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WebViewAssetLoader: bedient app/src/main/assets/ ueber eine virtuelle
    // https-Domain statt file://, u.a. damit Fetch/Storage-APIs im WebView normal funktionieren.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    // Offizielle Google Play Billing Library: wickelt den Einmalkauf (12,99 â‚¬) im
    // nativen Google-Play-Kaufdialog ab (inkl. Einloesen von Promo-/Gutschein-Codes).
    //
    // Die Bibliothek kommuniziert per IPC mit der Play-Store-App, die den eigentlichen
    // Zahlungsvorgang und den Netzwerkkontakt uebernimmt. Calculator selbst bleibt daher
    // weiterhin OHNE android.permission.INTERNET (siehe AndroidManifest.xml).
    implementation("com.android.billingclient:billing:8.3.0")
    // AndroidX Security-Crypto: verschluesselte Ablage (EncryptedSharedPreferences)
    // fuer den nutzerdefinierten Panik-Code. Die Werte liegen AES-256-GCM-
    // verschluesselt vor, der Schluessel im Android-Keystore (siehe PanicCodeStore.kt).
    // Kein Netzwerkbezug; die Bibliothek bleibt vollstaendig offline nutzbar.
    implementation("androidx.security:security-crypto:1.0.0")
    // Argon2id (speicherharte KDF) via Bouncy Castle - reine Java-Implementierung,
    // kein NDK/JNI, kompatibel mit der netzwerk-isolierten Kotlin-Architektur.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // WorkManager: fuehrt das 3-Pass-Shreddern des Duress-Wipes als EXPEDITED
    // WORK (Out-of-Quota) aus. Der Auftrag wird persistiert und laeuft deshalb
    // auch dann weiter, wenn der Prozess direkt nach dem Panik-Code beendet wird
    // (siehe SecureWipeWork.kt). Rein lokal: kein Netzwerkzugriff.
    implementation("androidx.work:work-runtime:2.10.5")
}

/**
 * Schutznetz fuer die WebView-Bruecke.
 *
 * Dieser Task laeuft direkt hinter dem R8-Schritt des Release-Builds und prueft
 * im R8-Mapping, ob die Klassen und Methoden, die JavaScript reflektiv aufruft
 * (window.AndroidBridge.*), unveraendert erhalten geblieben sind. Ein
 * umbenannter Brueckenname wuerde die App sonst LAUTLOS (ohne Absturz)
 * funktionsunfaehig machen: Speichern, Teilen, AES und Premium-Status wuerden
 * einfach nichts mehr tun.
 *
 * Faellt der Build hier aus, ist die Ursache immer dieselbe: eine fehlende oder
 * zu enge -keep-Regel in app/proguard-rules.pro bzw. eine fehlende
 * @Keep-Annotation in AndroidBridge.kt.
 */
val verifyJsBridgeKept by tasks.registering {
    group = "verification"
    description = "Prueft nach R8, dass die von JavaScript gerufenen Brueckennamen erhalten sind."
    dependsOn("minifyReleaseWithR8")

    // Provider wird bereits zur Konfigurationszeit aufgeloest - so bleibt der
    // Task mit dem Gradle-Konfigurationscache (gradle.properties) vertraeglich.
    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")

    val requiredClassNames = listOf(
        "com.mobile.utilities.calculator.AndroidBridge",
        "com.mobile.utilities.calculator.PremiumManager",
        "com.mobile.utilities.calculator.SecureDelete",
        // WorkManager speichert den Worker-Klassennamen als String in seiner
        // Auftrags-Datenbank und laedt ihn darueber wieder (Class.forName).
        // Bleibt der Name stabil, laeuft ein bereits eingeplanter Wipe-Auftrag
        // auch nach einem App-Update weiter.
        "com.mobile.utilities.calculator.SecureWipeWorker"
    )
    val requiredBridgeMethods = listOf(
        "saveImagePrivate",
        "onDownloadBlob",
        "shareImage",
        "triggerIfDuress",
        "aesEncrypt",
        "aesDecrypt",
        "hasMicrophonePermission",
        "requestMicrophonePermission",
        "microphoneDiagnostics",
        "getPremiumState",
        "requestPremiumPurchase",
        // Panik-/Duress-Code: Einrichtung/Einstellungsdialog (PanicCodeStore).
        "isPanicCodeConfigured",
        "requestPanicCodeSetup",
        // Build-Info fuer das zentrale JS-Logging (CalcLog): schaltet Logging im
        // Release-Build stumm, Quelle der Wahrheit ist BuildConfig.DEBUG.
        "isDebugBuild",
        "setAppLanguage",
        "verifyMainPin",
        "requestMainPinChange",
        "hasMainPin",
        "setMainPin",
        "isBruteForceLockedOut",
        "recordFailedPinAttempt",
        "resetFailedPinAttempts",
        "copySensitiveText"
    )

    doLast {
        val mapping = mappingFile.get().asFile
        if (!mapping.exists()) {
            logger.warn("verifyJsBridgeKept: ${mapping.path} fehlt - Pruefung uebersprungen.")
            return@doLast
        }

        val lines = mapping.readLines()
        val problems = mutableListOf<String>()

        requiredClassNames.forEach { name ->
            if (lines.none { it == "$name -> $name:" }) {
                problems += "Klasse $name wurde von R8 umbenannt oder entfernt."
            }
        }
        requiredBridgeMethods.forEach { method ->
            if (lines.none { it.endsWith("-> $method") }) {
                problems += "Brueckenmethode $method fehlt oder wurde umbenannt."
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "R8-Haertung unvollstaendig - die WebView-Bruecke wuerde im Release-Build brechen:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nBitte die -keep-Regeln in app/proguard-rules.pro pruefen und @Keep in " +
                    "AndroidBridge.kt setzen."
            )
        }
        logger.lifecycle(
            "verifyJsBridgeKept: WebView-Bruecke, Premium-Status und Wipe-Logik sind unverschleiert erhalten."
        )
    }
}

// ---------------------------------------------------------------------------
// verifyReleaseHardening - zweiter, unabhaengiger Build-Vertrag
// ---------------------------------------------------------------------------
/**
 * Prueft die zwei Eigenschaften, die eine Release-APK auszeichnen und die
 * versehentlich verloren gehen koennen:
 *
 *  1. Kein android:debuggable="true" im GEMERGTEN Release-Manifest. Geprueft
 *     wird bewusst der Merge-Ausgang und nicht die eigene Konfiguration: nur so
 *     faellt auch ein debuggable auf, das eine Bibliothek per Manifest mitbringt.
 *     Ein debuggables Release erlaubt "adb shell run-as" auf die App-Daten
 *     (SharedPreferences inkl. Panik-Code-Status, private Stego-Bilder).
 *  2. BuildConfig.DEBUG = false im generierten Release-BuildConfig. Genau daran
 *     haengt das Gate in MainActivity: waere der Wert true, liefe
 *     WebView.setWebContentsDebuggingEnabled(true) auch in der Release-APK und
 *     wuerde DOM/Inhalte ueber einen Debug-Port nach aussen lesbar machen.
 *
 * Fehlt eine der Dateien (z.B. Einzelaufruf des Tasks ohne Release-Build), wird
 * nur gewarnt - gleiches Verhalten wie bei verifyJsBridgeKept.
 */
val verifyReleaseHardening by tasks.registering {
    group = "verification"
    description = "Prueft, dass die Release-APK nicht debuggable ist und BuildConfig.DEBUG=false bleibt."
    dependsOn("processReleaseMainManifest", "generateReleaseBuildConfig")

    val mergedManifest = layout.buildDirectory.file(
        "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
    )
    val releaseBuildConfig = layout.buildDirectory.file(
        "generated/source/buildConfig/release/com/mobile/utilities/calculator/BuildConfig.java"
    )

    doLast {
        val problems = mutableListOf<String>()

        val manifest = mergedManifest.get().asFile
        if (manifest.exists()) {
            if (Regex("android:debuggable\\s*=\\s*\"true\"").containsMatchIn(manifest.readText())) {
                problems +=
                    "Das gemergte Release-Manifest (${manifest.path}) setzt android:debuggable=\"true\"."
            }
        } else {
            logger.warn("verifyReleaseHardening: ${manifest.path} fehlt - Manifest-Pruefung uebersprungen.")
        }

        val buildConfig = releaseBuildConfig.get().asFile
        if (buildConfig.exists()) {
            // Der Java-Generator schreibt "public static final boolean DEBUG = false;"
            // zur Build-Zeit - bewusst ohne Ruecksicht auf Gross-/Kleinschreibung,
            // damit die Pruefung nicht an der Schreibweise haengt.
            val debugFalse = Regex("\\bboolean\\s+DEBUG\\s*=\\s*false", RegexOption.IGNORE_CASE)
            if (!debugFalse.containsMatchIn(buildConfig.readText())) {
                problems +=
                    "${buildConfig.path} meldet nicht DEBUG = false - das WebView-Debug-Gate waere offen."
            }
        } else {
            logger.warn("verifyReleaseHardening: ${buildConfig.path} fehlt - BuildConfig-Pruefung uebersprungen.")
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Release-Haertung unvollstaendig - die APK waere debuggable oder das WebView-Debug-Gate offen:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nBitte buildTypes.release in app/build.gradle.kts pruefen " +
                    "(isDebuggable = false, isMinifyEnabled = true, isShrinkResources = true) " +
                    "und buildFeatures.buildConfig = true beibehalten."
            )
        }
        logger.lifecycle(
            "verifyReleaseHardening: Release-Build ist nicht debuggable und BuildConfig.DEBUG ist false."
        )
    }
}

// Nach jedem Release-Assemble laufen beide Pruefungen automatisch mit.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyJsBridgeKept, verifyReleaseHardening)
}
