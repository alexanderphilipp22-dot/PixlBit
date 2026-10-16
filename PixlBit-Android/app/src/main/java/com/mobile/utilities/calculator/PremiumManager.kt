package com.mobile.utilities.calculator

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import org.json.JSONObject

object PremiumManager {

    private const val TAG = "CalculatorPremium"

    private const val PREFS_NAME = "calc_premium"

    private const val SECURE_PREFS_NAME = "calc_premium_secure"
    private const val KEY_PREMIUM_TOKEN = "premium_purchase_token"
    private const val KEY_PREMIUM_PRODUCT = "premium_product_id"
    private const val KEY_PREMIUM_TIME = "premium_purchase_millis"

    private const val KEY_BILLING_PRICE = "billing_price_label"

    // Erststart-Zeitstempel fuer die 3-Tage-Testphase.
    private const val KEY_FIRST_LAUNCH = "first_launch_time"
    private const val TRIAL_DURATION_MILLIS = 3L * 24L * 60L * 60L * 1000L

    const val MAX_FREE_WORDS = 50

    const val PRICE_LABEL = "12,99 €"

    @Volatile
    private var cachedSecurePrefs: SharedPreferences? = null
    private val secureLock = Any()

    fun isPremium(context: Context): Boolean {
        val prefs = securePrefs(context) ?: return false
        val productId = prefs.getString(KEY_PREMIUM_PRODUCT, null) ?: return false
        val token = prefs.getString(KEY_PREMIUM_TOKEN, null) ?: return false
        return productId == BillingManager.PRODUCT_ID_PREMIUM && token.isNotBlank()
    }

    /** Speichert den Erststart-Zeitstempel genau einmal (idempotent). */
    fun ensureTrialStart(context: Context) {
        val prefs = prefs(context)
        if (prefs.getLong(KEY_FIRST_LAUNCH, 0L) == 0L) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
        }
    }

    /** true, solange die 3-Tage-Testphase ab Erststart laeuft. */
    fun isTrialActive(context: Context): Boolean {
        val prefs = prefs(context)
        var firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH, 0L)
        if (firstLaunch == 0L) {
            // Defensiv: noch nie gespeichert -> jetzt setzen (kein Absturz).
            firstLaunch = System.currentTimeMillis()
            prefs.edit().putLong(KEY_FIRST_LAUNCH, firstLaunch).apply()
        }
        return (System.currentTimeMillis() - firstLaunch) < TRIAL_DURATION_MILLIS
    }

    fun buildStateJson(context: Context): String {
        val prefs = prefs(context)
        val premium = BuildConfig.TEST_BUILD_UNLOCKED || isPremium(context) || isTrialActive(context)

        val json = JSONObject()
        json.put("premium", premium)
        json.put("maxFreeWords", MAX_FREE_WORDS)
        json.put("priceLabel", billingPriceLabel(prefs))
        return json.toString()
    }

    fun buildFailClosedStateJson(): String {
        return JSONObject()
            .put("premium", BuildConfig.TEST_BUILD_UNLOCKED)
            .put("maxFreeWords", MAX_FREE_WORDS)
            .put("priceLabel", PRICE_LABEL)
            .toString()
    }

    private fun billingPriceLabel(prefs: SharedPreferences): String =
        prefs.getString(KEY_BILLING_PRICE, null)?.takeIf { it.isNotBlank() } ?: PRICE_LABEL

    fun openStoreListing(context: Context): Boolean {
        val packageName = context.packageName
        if (startViewIntent(context, "market://details?id=$packageName")) return true
        if (startViewIntent(context, "https://play.google.com/store/apps/details?id=$packageName")) return true

        Log.w(TAG, "Keine Store-App gefunden")
        return false
    }

    fun activatePremium(
        context: Context,
        purchaseToken: String,
        productId: String,
        purchaseTimeMillis: Long
    ): Boolean {
        if (purchaseToken.isBlank() || productId != BillingManager.PRODUCT_ID_PREMIUM) return false
        val prefs = securePrefs(context) ?: return false
        val wasPremium = isPremium(context)
        prefs.edit()
            .putString(KEY_PREMIUM_TOKEN, purchaseToken)
            .putString(KEY_PREMIUM_PRODUCT, productId)
            .putLong(KEY_PREMIUM_TIME, purchaseTimeMillis)
            .apply()
        return !wasPremium
    }

    fun cacheBillingPriceLabel(context: Context, priceLabel: String) {
        if (priceLabel.isBlank()) return
        prefs(context).edit().putString(KEY_BILLING_PRICE, priceLabel).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun securePrefs(context: Context): SharedPreferences? {
        cachedSecurePrefs?.let { return it }
        return synchronized(secureLock) {
            cachedSecurePrefs ?: try {
                createSecurePrefs(context.applicationContext).also { cachedSecurePrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Verschluesselte Premium-Ablage nicht verfuegbar: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun createSecurePrefs(appContext: Context): SharedPreferences =
        SecurePrefsFactory.create(appContext, SECURE_PREFS_NAME, SecurePrefsFactory.ALIAS_PREMIUM)

    private fun startViewIntent(context: Context, uri: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
