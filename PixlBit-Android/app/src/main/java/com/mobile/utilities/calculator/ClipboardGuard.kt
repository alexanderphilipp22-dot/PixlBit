package com.mobile.utilities.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.Keep

/**
 * Verwaltet den Auto-Purge sensibler Zwischenablage-Inhalte. Ein ausstehender
 * Sensitive-Clip wird bei App-Stopp (onStop/onTrimMemory) sofort geleert.
 */
@Keep
object ClipboardGuard {

    @Volatile
    private var pendingSensitiveClip = false

    fun markPending() {
        pendingSensitiveClip = true
    }

    fun purge(context: Context) {
        if (!pendingSensitiveClip) return
        pendingSensitiveClip = false
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Exception) {
            // ignorieren
        }
    }
}
