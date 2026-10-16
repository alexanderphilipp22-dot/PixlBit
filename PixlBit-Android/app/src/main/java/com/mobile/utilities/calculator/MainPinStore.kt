package com.mobile.utilities.calculator

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Haupt-PIN (Entsperr-PIN) des Rechners.
 *
 * Getrennt vom Duress-/Panik-Code. Die PIN wird ueber die Rechner-Tasten
 * eingegeben (nur Ziffern + Dezimaltrennzeichen). Gespeichert wird nur
 * Salt + Argon2id-Hash (EncryptedSharedPreferences, StrongBox/TEE).
 */
@Keep
object MainPinStore {

    private const val TAG = "CalculatorMainPin"

    private const val PREFS_NAME = "calc_mainpin_secure"
    private const val KEY_SALT = "main_pin_salt_b64"
    private const val KEY_HASH = "main_pin_hash_b64"

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

    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_CODE_LENGTH..MAX_CODE_LENGTH &&
            hasDecimalSeparator(pin) &&
            pin.all { it.isDigit() || it == ',' || it == '.' }

    fun hasDecimalSeparator(pin: String): Boolean = pin.any { it == ',' || it == '.' }

    /** Normalisiert das Dezimaltrennzeichen: Komma (,) wird intern zu Punkt (.). */
    fun normalizeSeparator(pin: String): String = pin.replace(',', '.')

    fun setPin(context: Context, pin: String): Boolean {
        val normalized = normalizeSeparator(pin)
        if (!isValidPin(normalized)) return false
        val prefs = securePrefs(context) ?: return false

        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val pinChars = normalized.toCharArray()
        val hash = try {
            hashOf(pinChars, salt)
        } finally {
            pinChars.fill('\u0000')
            System.gc()
        }
        return try {
            prefs.edit()
                .putString(KEY_SALT, encode(salt))
                .putString(KEY_HASH, encode(hash))
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Haupt-PIN konnte nicht gespeichert werden: ${e.javaClass.simpleName}")
            false
        } finally {
            salt.fill(0)
            hash.fill(0)
            System.gc()
        }
    }

    fun verify(context: Context, pin: String): Boolean {
        if (pin.isEmpty()) return false
        val normalized = normalizeSeparator(pin)
        val prefs = securePrefs(context) ?: return false

        val salt = decode(prefs.getString(KEY_SALT, null)) ?: return false
        val expected = decode(prefs.getString(KEY_HASH, null)) ?: return false
        val pinChars = normalized.toCharArray()
        val candidate = try {
            hashOf(pinChars, salt)
        } finally {
            pinChars.fill('\u0000')
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

    private fun hashOf(pin: CharArray, salt: ByteArray): ByteArray =
        Argon2Kdf.derive(pin, salt)

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
                SecurePrefsFactory.create(context, PREFS_NAME, SecurePrefsFactory.ALIAS_MAIN_PIN).also { cachedPrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Verschluesselte Ablage nicht verfuegbar: ${e.javaClass.simpleName}")
                null
            }
        }
    }
}
