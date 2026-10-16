package com.mobile.utilities.calculator

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep

/**
 * Unsichtbarer Brute-Force-Schutz fuer den 3000-ms-Long-Press-Trigger.
 *
 * Kein Warten, kein Blockieren, KEINE Schleifen und KEIN Thread.sleep: Die
 * Sperre wird ausschliesslich ueber Zeitstempel (System.currentTimeMillis)
 * abgebildet. Der Lockout-Zustand liegt verschluesselt
 * (EncryptedSharedPreferences), damit ein physischer Angreifer den Zaehler
 * nicht von aussen manipulieren oder zuruecksetzen kann.
 */
@Keep
object BruteForceGuard {

    private const val TAG = "CalculatorBruteForce"

    private const val PREFS_NAME = "calc_bruteforce_secure"
    private const val KEY_FAIL_COUNT = "fail_count"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"

    const val MAX_FAILURES = 5

    private const val LOCKOUT_DURATION_MS = 5L * 60L * 1000L

    private val lock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    fun isLockedOut(context: Context): Boolean {
        val store = securePrefs(context) ?: return false
        val lockoutUntil = store.getLong(KEY_LOCKOUT_UNTIL, 0L)
        return System.currentTimeMillis() < lockoutUntil
    }

    fun recordFailure(context: Context) {
        val store = securePrefs(context) ?: return
        // Read-Modify-Write atomar halten: Zwei gleichzeitige Fehlversuche (JS-
        // Bridge-Thread + nativer verifyMainPin-Pfad) duerfen sich nicht
        // gegenseitig ueberschreiben und einen Zaehlerstand verlieren.
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val lockoutUntil = store.getLong(KEY_LOCKOUT_UNTIL, 0L)
            val count = if (now >= lockoutUntil) 1 else store.getInt(KEY_FAIL_COUNT, 0) + 1
            val newLockout = if (count >= MAX_FAILURES) now + LOCKOUT_DURATION_MS else lockoutUntil
            // commit() statt apply(): Der Zaehler muss auch einen sofortigen
            // Prozess-Kill (Force-Stop) ueberleben, sonst hebelt ein Angreifer
            // die Sperre durch einen Neustart nach jedem Fehlversuch aus.
            store.edit()
                .putInt(KEY_FAIL_COUNT, count)
                .putLong(KEY_LOCKOUT_UNTIL, newLockout)
                .commit()
        }
    }

    fun reset(context: Context) {
        val store = securePrefs(context) ?: return
        synchronized(lock) {
            store.edit()
                .putInt(KEY_FAIL_COUNT, 0)
                .putLong(KEY_LOCKOUT_UNTIL, 0L)
                .commit()
        }
    }

    private fun securePrefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        return synchronized(lock) {
            cachedPrefs ?: try {
                SecurePrefsFactory.create(context, PREFS_NAME, SecurePrefsFactory.ALIAS_BRUTE_FORCE).also { cachedPrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Verschluesselte Ablage nicht verfuegbar: ${e.javaClass.simpleName}")
                null
            }
        }
    }
}
