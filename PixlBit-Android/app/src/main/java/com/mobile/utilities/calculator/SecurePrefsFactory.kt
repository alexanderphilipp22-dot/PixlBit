package com.mobile.utilities.calculator

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.Keep
import androidx.security.crypto.EncryptedSharedPreferences
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Zentrale Master-Key-Erzeugung fuer alle EncryptedSharedPreferences.
 * Jede Praeferenzdatei erhaelt einen EIGENEN KeyStore-Alias (Schluessel-Isolation).
 * Erzwingt StrongBox (API 28+) und faellt pro Alias auf TEE zurueck, wenn der
 * Hardware-Chip fehlt oder StrongBoxUnavailableException geworfen wird.
 */
@Keep
object SecurePrefsFactory {

    private const val TAG = "CalculatorSecurePrefs"

    const val ALIAS_MAIN_PIN = "calc_master_key_mainpin"
    const val ALIAS_PANIC = "calc_master_key_panic"
    const val ALIAS_BRUTE_FORCE = "calc_master_key_bruteforce"
    const val ALIAS_PREMIUM = "calc_master_key_premium"

    fun create(context: Context, name: String, keyAlias: String): SharedPreferences {
        val appContext = context.applicationContext
        ensureMasterKey(appContext, keyAlias)
        return EncryptedSharedPreferences.create(
            name,
            keyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun ensureMasterKey(appContext: Context, keyAlias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(spec(keyAlias, strongBox = true))
                generator.generateKey()
                return
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox nicht verfuegbar (${e.javaClass.simpleName}), TEE-Fallback")
            }
        }
        generator.init(spec(keyAlias, strongBox = false))
        generator.generateKey()
    }

    private fun spec(keyAlias: String, strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        return builder.build()
    }
}
