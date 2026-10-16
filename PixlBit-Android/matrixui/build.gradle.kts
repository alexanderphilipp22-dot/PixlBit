// Eigenstaendiges UI-Modul fuer den Matrix-Zeichenregen (Jetpack Compose).
//
// Es hat bewusst KEINE Abhaengigkeit zum :app-Modul: die Animation ist rein
// ueber Compose-APIs gekapselt und kann 1:1 in jedes andere Compose-Projekt
// uebernommen werden. Eingebunden wird sie dort mit:
//     implementation(project(":matrixui"))
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Ab Kotlin 2.0 liefert dieses Plugin den Compose-Compiler mit.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mobile.utilities.calculator.matrix"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose 1.11.4 ist die neueste mit AGP 8.13.2 / compileSdk 36 kompatible
    // Compose-Generation (ab 1.12.x verlangen die Artefakte AGP 9.1). Die
    // Versionen werden bewusst explizit gepinnt statt ueber ein BOM, damit der
    // Compose-Stand im Modul nachvollziehbar und reproduzierbar bleibt.
    val composeVersion = "1.11.4"

    // "api", weil die oeffentliche API des Moduls Compose-Typen (Color,
    // FontFamily, Modifier, @Composable) enthaelt und Konsumenten diese zum
    // Kompilieren brauchen.
    api("androidx.compose.runtime:runtime:$composeVersion")
    api("androidx.compose.ui:ui:$composeVersion")
    api("androidx.compose.ui:ui-graphics:$composeVersion")
    api("androidx.compose.ui:ui-text:$composeVersion")
    api("androidx.compose.foundation:foundation:$composeVersion")

    // Standard-Animations-APIs (updateTransition, AnimatedVisibility, Crossfade).
    // Sie liegen in einem eigenen Artefakt und werden fuer den Unlock-Uebergang
    // (UnlockTransition.kt) gebraucht; "implementation", weil die oeffentliche API
    // des Moduls KEINE Animationstypen nach aussen gibt (nur Floats, Booleans und
    // Composable-Slots) - Konsumenten brauchen das Artefakt daher nicht selbst.
    implementation("androidx.compose.animation:animation:$composeVersion")

    // Nur fuer @Preview in Android Studio (wandert nicht ins Release-APK).
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")

    testImplementation("junit:junit:4.13.2")
}
