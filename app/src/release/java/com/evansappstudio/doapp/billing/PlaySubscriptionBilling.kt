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
 * Release billing for the Do Unlimited subscription ([FreeTier.PRODUCT_ID], [BillingClient.ProductType.SUBS]).
 * The offer and its price text come from Play ProductDetails via [SubscriptionOffers.choose];
 * no price is in the code. Grant, tell the user, then acknowledge. Pending does not grant.
 * "Active" means Play's purchase query currently returns the subscription: Play
 * includes grace periods and leaves out account hold, paused and expired ones.
 * There is no other way to pay. Spec 13: each purchased token also goes to the
 * [PurchaseObserver] (Do Cloud's verifyPurchase), whose verdict wins when present.
 *
 * Every Play callback body runs on the main thread ([onMain]), so the fields
 * below have one owner and [publish] never races with another callback.
 * A failed connection or details query sets [storeUnavailable] and keeps the
 * last good offer and price.
 */
class PlaySubscriptionBilling(
    context: Context,
    private val store: EntitlementStore,
) : SubscriptionBilling {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var offerToken: String? = null
    private var price: PriceInfo? = null
    private var failed = false
    private var celebrate = false
    private var storeUnavailable = false
    @Volatile private var purchaseObserver: PurchaseObserver? = null
    private val uiState = MutableStateFlow(current())
    override val ui: StateFlow<SubscriptionUiState> = uiState.asStateFlow()

    private val purchasesUpdated = com.android.billingclient.api.PurchasesUpdatedListener { result, _ ->
        onMain {
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
        if (client == null || !client.isReady || product == null || token.isNullOrBlank() || price == null) {
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
        when (client.launchBillingFlow(activity, params).responseCode) {
            BillingClient.BillingResponseCode.OK,
            BillingClient.BillingResponseCode.USER_CANCELED,
            -> Unit
            // Play already holds it (renewed elsewhere while the cache said otherwise): re-query.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            else -> {
                failed = true
                publish()
            }
        }
    }

    override fun restore() {
        onMain { queryPurchases() }
    }

    override fun price(): PriceInfo? = price

    override fun dumpSource(): String = if (store.read().lastQueryOk) "play" else "cache"

    override fun setPurchaseObserver(observer: PurchaseObserver?) {
        purchaseObserver = observer
    }

    override fun reload() {
        onMain { publish() }
    }

    override fun clearCelebrate() {
        onMain {
            celebrate = false
            publish()
        }
    }

    /** Runs [block] on the main thread: now if already there, else posted. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun connect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { connect() }
            return
        }
        val existing = billingClient
        if (existing != null && existing.isReady) {
            refresh()
            return
        }
        val client = existing ?: BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdated)
            // The builder requires pending purchases enabled for one-time products. Prepaid
            // plans are not enabled: Do Unlimited is an auto-renewing plan.
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
                onMain {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        refresh()
                    } else {
                        storeUnavailable = true
                        publish()
                    }
                }
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
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases -> onMain { onPurchases(client, result, purchases) } }
    }

    private fun onPurchases(client: BillingClient, result: BillingResult, purchases: List<Purchase>?) {
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
        if (ok) notifyPurchases(purchases.orEmpty(), applied.justGranted)
    }

    /** Spec 13: hands each purchased token to Do Cloud. Its failures never reach billing. */
    private fun notifyPurchases(purchases: List<Purchase>, justGranted: Boolean) {
        val observer = purchaseObserver ?: return
        val tokens = purchases
            .filter { FreeTier.PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .map { it.purchaseToken }
            .filter { it.isNotBlank() }
            .distinct()
        for (token in tokens) {
            try {
                observer.onPurchase(FreeTier.PRODUCT_ID, token, justGranted)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            }
        }
    }

    private fun queryDetails() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(FreeTier.PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            val ok = result.responseCode == BillingClient.BillingResponseCode.OK
            val product = if (ok) details.productDetailsList.firstOrNull { it.productId == FreeTier.PRODUCT_ID } else null
            onMain { onDetails(ok, product) }
        }
    }

    /** A failed query keeps the last good offer and price and flags Play as unreachable. */
    private fun onDetails(ok: Boolean, product: ProductDetails?) {
        if (!ok) {
            storeUnavailable = true
            publish()
            return
        }
        storeUnavailable = false
        val choice = SubscriptionOffers.choose(product?.subscriptionOfferDetails.orEmpty().map { it.toView() })
        productDetails = product.takeIf { choice != null }
        price = choice?.price
        offerToken = choice?.offerToken
        publish()
    }

    private fun acknowledge(client: BillingClient, token: String) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                DoAlarmLog.unlockAckFailed(result.responseCode)
            }
        }
    }

    private fun ProductDetails.SubscriptionOfferDetails.toView(): OfferView = OfferView(
        basePlanId = basePlanId,
        offerId = offerId,
        offerToken = offerToken,
        phasePrices = pricingPhases.pricingPhaseList.map { it.formattedPrice },
    )

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

    private fun current(): SubscriptionUiState = SubscriptionUiState.from(
        record = store.read(),
        price = price,
        failed = failed,
        celebrate = celebrate,
        storeUnavailable = storeUnavailable,
    )
}

fun createSubscriptionBilling(context: Context, store: EntitlementStore): SubscriptionBilling {
    return PlaySubscriptionBilling(context, store)
}
