package com.evansappstudio.doapp.billing

/**
 * What a Play subscription purchase query does to the local entitlement.
 *
 * Grant only for [PurchaseViewState.PURCHASED]. Acknowledge only those
 * purchases that are still unacknowledged. A failed query keeps the cache.
 * A successful query that no longer contains the product revokes it.
 * [EntitlementRecord.everSubscribed] is set by the first grant and never
 * cleared, so a revoke after a grant reads as [SubscriptionState.EXPIRED].
 * The purchase token is returned for acknowledgement and is not stored.
 */
object EntitlementPolicy {
    const val SOURCE_PLAY = "play"
    const val SOURCE_FAKE = "fake"
    const val SOURCE_NONE = "none"

    fun apply(
        cache: EntitlementRecord,
        querySucceeded: Boolean,
        purchases: List<PurchaseView>,
        now: Long,
        source: String,
        productId: String = FreeTier.PRODUCT_ID,
    ): PurchaseApply {
        if (!querySucceeded) {
            return PurchaseApply(
                record = cache,
                acknowledgeTokens = emptyList(),
                justGranted = false,
                reachedStore = false,
            )
        }
        val mine = purchases.filter { productId in it.productIds }
        val purchased = mine.filter { it.state == PurchaseViewState.PURCHASED }
        val pending = mine.any { it.state == PurchaseViewState.PENDING }
        val active = purchased.isNotEmpty()
        val tokens = purchased
            .filter { !it.acknowledged && it.token.isNotBlank() }
            .map { it.token }
            .distinct()
        val record = EntitlementRecord(
            active = active,
            pending = !active && pending,
            lastQueryAt = now,
            grantSource = source,
            lastQueryOk = true,
            everSubscribed = cache.everSubscribed || active,
        )
        return PurchaseApply(
            record = record,
            acknowledgeTokens = tokens,
            justGranted = active && !cache.active,
            reachedStore = true,
        )
    }
}

enum class PurchaseViewState {
    PURCHASED,
    PENDING,
    OTHER,
}

data class PurchaseView(
    val productIds: List<String>,
    val state: PurchaseViewState,
    val acknowledged: Boolean,
    val token: String,
)

/**
 * The cached entitlement. [active] means the last successful query returned a
 * PURCHASED Do Unlimited purchase (Play includes grace periods). [everSubscribed]
 * is set the first time a grant happens and is never cleared.
 */
data class EntitlementRecord(
    val active: Boolean,
    val pending: Boolean,
    val lastQueryAt: Long,
    val grantSource: String,
    val lastQueryOk: Boolean,
    val everSubscribed: Boolean = false,
) {
    companion object {
        val EMPTY = EntitlementRecord(
            active = false,
            pending = false,
            lastQueryAt = 0L,
            grantSource = EntitlementPolicy.SOURCE_NONE,
            lastQueryOk = false,
            everSubscribed = false,
        )
    }
}

data class PurchaseApply(
    val record: EntitlementRecord,
    val acknowledgeTokens: List<String>,
    val justGranted: Boolean,
    val reachedStore: Boolean,
)
