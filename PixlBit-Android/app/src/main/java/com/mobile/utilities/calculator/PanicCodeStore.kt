package com.mobile.utilities.calculator

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Duress-/Panik-Code. Eingabe ueber die Rechner-Tasten (nur Ziffern +
 * Dezimaltrennzeichen). Gespeichert wird nur Salt + Argon2id-Hash.
 */
@Keep
object PanicCodeStore {

    private const val TAG = "CalculatorPanicCode"

    private const val PREFS_NAME = "calc_panic_secure"
    private const val KEY_SALT = "panic_code_salt_b64"
    private const val KEY_HASH = "panic_code_hash_b64"

    private const val SALT_LENGTH = 32

    const val MIN_CODE_LENGTH = 12

    private const val MAX_CODE_LENGTH = 128

    private val secureRandom = SecureRandom()
    private val lock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    fun isConfigured(context: Context): Boolean {
        val prefs = securePrefs(context) ?: return false
        return prefs.getString(KEY_SALT, null) != null && prefs.getString(KEY_HASH, null) != null
    }

    fun isValidCode(code: String): Boolean =
        code.length in MIN_CODE_LENGTH..MAX_CODE_LENGTH &&
            hasDecimalSeparator(code) &&
            code.all { it.isDigit() || it == ',' || it == '.' }

    fun hasDecimalSeparator(code: String): Boolean = code.any { it == ',' || it == '.' }

    /** Normalisiert das Dezimaltrennzeichen: Komma (,) wird intern zu Punkt (.). */
    fun normalizeSeparator(code: String): String = code.replace(',', '.')

    fun setPanicCode(context: Context, code: String): Boolean {
        val normalized = normalizeSeparator(code)
        if (!isValidCode(normalized)) return false
        val prefs = securePrefs(context) ?: return false

        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val codeChars = normalized.toCharArray()
        val hash = try {
            hashOf(codeChars, salt)
        } finally {
            codeChars.fill('\u0000')
            System.gc()
        }
        return try {
            prefs.edit()
                .putString(KEY_SALT, encode(salt))
                .putString(KEY_HASH, encode(hash))
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Panik-Code konnte nicht gespeichert werden: ${e.javaClass.simpleName}")
            false
        } finally {
            salt.fill(0)
            hash.fill(0)
            System.gc()
        }
    }

    fun verify(context: Context, code: String): Boolean {
        if (code.isEmpty()) return false
        val normalized = normalizeSeparator(code)
        val prefs = securePrefs(context) ?: return false

        val salt = decode(prefs.getString(KEY_SALT, null)) ?: return false
        val expected = decode(prefs.getString(KEY_HASH, null)) ?: return false
        val codeChars = normalized.toCharArray()
        val candidate = try {
            hashOf(codeChars, salt)
        } finally {
            codeChars.fill('\u0000')
            System.gc()
        }
        return try {
            MessageDigest.isEqual(expected, candidate)
        } finally {
            candidate.fill(0)
            salt.fill(0)
            expected.fill(0)
            System.gc()
        }
    }

    fun clear() {
        val prefs = cachedPrefs ?: return
        try {
            prefs.edit().clear().commit()
        } catch (e: Exception) {
            Log.e(TAG, "Panik-Code konnte nicht entfernt werden: ${e.javaClass.simpleName}")
        }
        cachedPrefs = null
    }

    private fun hashOf(code: CharArray, salt: ByteArray): ByteArray =
        Argon2Kdf.derive(code, salt)

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String?): ByteArray? {
        if (value.isNullOrEmpty()) return null
        return try {
            Base64.decode(value, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Gespeicherter Wert ist beschaedigt")
            null
        }
    }

    private fun securePrefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        return synchronized(lock) {
            cachedPrefs ?: try {
                SecurePrefsFactory.create(context, PREFS_NAME, SecurePrefsFactory.ALIAS_PANIC).also { cachedPrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Verschluesselte Ablage nicht verfuegbar: ${e.javaClass.simpleName}")
                null
            }
        }
    }
}
