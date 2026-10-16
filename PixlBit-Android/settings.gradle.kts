pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Calculator"
include(":app")
// Eigenstaendiges UI-Modul mit der Compose-Animation (Matrix-Zeichenregen).
// Bewusst getrennt, damit die WebView-App unveraendert schlank bleibt und die
// Animation in jedes beliebige Compose-Projekt uebernommen werden kann.
include(":matrixui")
