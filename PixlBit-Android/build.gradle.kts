// Deklariert nur die Plugin-Versionen fuer den Klassenpfad; angewendet wird
// jeweils im Modul (siehe app/build.gradle.kts und matrixui/build.gradle.kts).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    // Ab Kotlin 2.0 liefert dieses Plugin den Compose-Compiler mit.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
