package com.mobile.utilities.calculator

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Koppelt die In-App-Sprachwahl (WebView/Header) an die Android-Locale, damit
 * native Dialoge (Panik-Code, ShareTarget) derselben Sprache folgen wie die
 * WebView-Oberflaeche.
 *
 * Idempotent: wird dieselbe Sprache erneut gemeldet, erfolgt KEIN erneutes
 * Anwenden/Recreate - das verhindert eine Endlos-Recreation beim WebView-Reload.
 */
object LocaleHelper {

    /** Wendet die Sprache an (persistiert automatisch via AppCompat). */
    fun apply(code: String?) {
        if (code.isNullOrBlank()) return
        val requested = LocaleListCompat.forLanguageTags(code)
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() == requested.toLanguageTags()) return
        AppCompatDelegate.setApplicationLocales(requested)
    }
}
