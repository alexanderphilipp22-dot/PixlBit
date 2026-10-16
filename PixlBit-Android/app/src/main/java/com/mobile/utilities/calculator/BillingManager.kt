package com.mobile.utilities.calculator

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

class BillingManager(
    private val activity: Activity,
    private val onPremiumStateChanged: () -> Unit = {},
    private val onPurchaseFailed: (reason: String) -> Unit = {}
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "CalculatorBilling"

        const val PRODUCT_ID_PREMIUM = "calc_premium_lifetime"
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var connected = false

    private var purchaseRequested = false

    fun connect() {
        val existing = billingClient
        if (existing != null) {
            if (!connected) startConnection(existing)
            return
        }

        val created = BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        billingClient = created
        startConnection(created)
    }

    private fun startConnection(billingClient: BillingClient) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                connected = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                if (!connected) {
                    Log.w(
                        TAG,
                        "Play-Billing nicht verfuegbar: Code ${billingResult.responseCode} " +
                            "(${billingResult.debugMessage})"
                    )
                    if (purchaseRequested) {
                        purchaseRequested = false
                        onPurchaseFailed("Play Store nicht erreichbar, Code ${billingResult.responseCode}")
                    }
                    return
                }

                queryProducts()
                queryPurchases()

                if (purchaseRequested) {
                    purchaseRequested = false
                    launchPurchaseFlow()
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
                Log.w(TAG, "Verbindung zum Play-Store-Dienst getrennt")
            }
        })
    }

    fun endConnection() {
        purchaseRequested = false
        connected = false
        productDetails = null
        billingClient?.endConnection()
        billingClient = null
    }

    fun refreshPurchases() {
        if (connected) queryPurchases()
    }

    fun launchPurchaseFlow(): Boolean {
        val billingClient = billingClient
        if (billingClient == null || !connected || !billingClient.isReady) {
            purchaseRequested = true
            connect()
            return true
        }

        val details = productDetails
        if (details == null) {
            purchaseRequested = true
            queryProducts()
            return true
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Kauf-Dialog nicht gestartet: Code ${result.responseCode} (${result.debugMessage})")
            onPurchaseFailed("Kauf-Dialog nicht gestartet, Code ${result.responseCode}")
            return false
        }
        return true
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !purchases.isNullOrEmpty()) {
            purchases.forEach { handlePurchase(it) }
            return
        }

        if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            return
        }

        Log.e(
            TAG,
            "Kauf fehlgeschlagen: Code ${billingResult.responseCode} (${billingResult.debugMessage})"
        )
        onPurchaseFailed("Google Play meldet Fehler Code ${billingResult.responseCode}")
    }

    private fun queryProducts() {
        val billingClient = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_PREMIUM)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(
                    TAG,
                    "Produktabfrage fehlgeschlagen: Code ${billingResult.responseCode} " +
                        "(${billingResult.debugMessage})"
                )
                if (purchaseRequested) {
                    purchaseRequested = false
                    onPurchaseFailed("Produktabfrage fehlgeschlagen, Code ${billingResult.responseCode}")
                }
                return@queryProductDetailsAsync
            }

            val details = queryResult.productDetailsList.firstOrNull { it.productId == PRODUCT_ID_PREMIUM }
            if (details == null) {
                Log.e(TAG, "Produkt $PRODUCT_ID_PREMIUM ist in der Play Console nicht verfuegbar")
                if (purchaseRequested) {
                    purchaseRequested = false
                    onPurchaseFailed("Produkt in Google Play nicht verfuegbar")
                }
                return@queryProductDetailsAsync
            }

            productDetails = details
            details.oneTimePurchaseOfferDetails
                ?.formattedPrice
                ?.takeIf { it.isNotBlank() }
                ?.let { price -> PremiumManager.cacheBillingPriceLabel(activity, price) }

            if (purchaseRequested) {
                purchaseRequested = false
                launchPurchaseFlow()
            }
        }
    }

    private fun queryPurchases() {
        val billingClient = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(
                    TAG,
                    "Kaufabfrage fehlgeschlagen: Code ${billingResult.responseCode} " +
                        "(${billingResult.debugMessage})"
                )
                return@queryPurchasesAsync
            }
            purchases.forEach { handlePurchase(it) }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return
        }
        if (!purchase.products.contains(PRODUCT_ID_PREMIUM)) {
            Log.w(TAG, "Kauf gehoert nicht zum Premium-Produkt - ignoriert")
            return
        }

        if (!purchase.isAcknowledged) {
            acknowledge(purchase)
        }

        PremiumManager.activatePremium(
            activity,
            purchaseToken = purchase.purchaseToken,
            productId = PRODUCT_ID_PREMIUM,
            purchaseTimeMillis = purchase.purchaseTime
        )
        onPremiumStateChanged()
    }

    private fun acknowledge(purchase: Purchase) {
        val billingClient = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(
                    TAG,
                    "Bestaetigung des Kaufs fehlgeschlagen: Code ${billingResult.responseCode} " +
                        "(${billingResult.debugMessage})"
                )
            }
        }
    }
}
