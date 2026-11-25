package com.evansappstudio.doapp.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.evansappstudio.doapp.alarm.DoAlarmLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Release billing. The formatted price comes from Play ProductDetails.
 * Grant, tell the user, then acknowledge. Pending does not grant.
 * There is no other way to pay.
 */
class PlayUnlockBilling(
    context: Context,
    private val store: EntitlementStore,
) : UnlockBilling {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var offerToken: String? = null
    private var price: String? = null
    private var failed = false
    private var celebrate = false
    private val uiState = MutableStateFlow(current())
    override val ui: StateFlow<UnlockUiState> = uiState.asStateFlow()

    private val purchasesUpdated = com.android.billingclient.api.PurchasesUpdatedListener { result, _ ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
            -> queryPurchases()
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> {
                failed = true
                publish()
            }
        }
    }

    override fun start() {
        publish()
        connect()
    }

    override fun onResume() {
        connect()
    }

    override fun buy(activity: Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { buy(activity) }
            return
        }
        val client = billingClient
        val product = productDetails
        val token = offerToken
        if (client == null || !client.isReady || product == null || token.isNullOrBlank() || price.isNullOrBlank()) {
            failed = true
            publish()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(token)
                        .build(),
                ),
            )
            .build()
        val launched = client.launchBillingFlow(activity, params)
        if (launched.responseCode != BillingClient.BillingResponseCode.OK &&
            launched.responseCode != BillingClient.BillingResponseCode.USER_CANCELED
        ) {
            failed = true
            publish()
        }
    }

    override fun restore() {
        queryPurchases()
    }

    override fun formattedPrice(): String? = price

    override fun dumpSource(): String = if (store.read().lastQueryOk) "play" else "cache"

    override fun clearCelebrate() {
        celebrate = false
        publish()
    }

    private fun connect() {
        val existing = billingClient
        if (existing != null && existing.isReady) {
            refresh()
            return
        }
        val client = existing ?: BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdated)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .enableAutoServiceReconnection()
            .build()
        billingClient = client
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh()
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun refresh() {
        queryPurchases()
        queryDetails()
    }

    private fun queryPurchases() {
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            val ok = result.responseCode == BillingClient.BillingResponseCode.OK
            val applied = EntitlementPolicy.apply(
                cache = store.read(),
                querySucceeded = ok,
                purchases = if (ok) purchases.orEmpty().map { it.toView() } else emptyList(),
                now = System.currentTimeMillis(),
                source = EntitlementPolicy.SOURCE_PLAY,
            )
            store.write(applied.record)
            if (applied.justGranted) celebrate = true
            if (ok) failed = false
            publish()
            for (token in applied.acknowledgeTokens) acknowledge(client, token)
        }
    }

    private fun queryDetails() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(FreeTier.PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                price = null
                offerToken = null
                productDetails = null
                publish()
                return@queryProductDetailsAsync
            }
            val product = details.productDetailsList.firstOrNull { it.productId == FreeTier.PRODUCT_ID }
            val offer = product?.oneTimePurchaseOfferDetailsList?.firstOrNull()
            productDetails = product
            price = offer?.formattedPrice?.takeIf { it.isNotBlank() }
            offerToken = offer?.offerToken?.takeIf { it.isNotBlank() }
            publish()
        }
    }

    private fun acknowledge(client: BillingClient, token: String) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                DoAlarmLog.unlockAckFailed(result.responseCode)
            }
        }
    }

    private fun Purchase.toView(): PurchaseView {
        val mapped = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseViewState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseViewState.PENDING
            else -> PurchaseViewState.OTHER
        }
        return PurchaseView(
            productIds = products,
            state = mapped,
            acknowledged = isAcknowledged,
            token = purchaseToken,
        )
    }

    private fun publish() {
        uiState.value = current()
    }

    private fun current(): UnlockUiState = UnlockUiState.from(
        record = store.read(),
        price = price,
        failed = failed,
        celebrate = celebrate,
    )
}

fun createUnlockBilling(context: Context, store: EntitlementStore): UnlockBilling {
    return PlayUnlockBilling(context, store)
}
