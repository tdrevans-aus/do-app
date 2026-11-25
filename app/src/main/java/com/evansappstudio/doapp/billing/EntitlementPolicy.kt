package com.evansappstudio.doapp.billing

/**
 * What a Play purchase query does to the local entitlement.
 *
 * Grant only for [PurchaseViewState.PURCHASED]. Acknowledge only those
 * purchases that are still unacknowledged. A failed query keeps the cache.
 * A successful query that no longer contains the product revokes it.
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
        val unlocked = purchased.isNotEmpty()
        val tokens = purchased
            .filter { !it.acknowledged && it.token.isNotBlank() }
            .map { it.token }
            .distinct()
        val record = EntitlementRecord(
            unlocked = unlocked,
            pending = !unlocked && pending,
            lastQueryAt = now,
            grantSource = source,
            lastQueryOk = true,
        )
        return PurchaseApply(
            record = record,
            acknowledgeTokens = tokens,
            justGranted = unlocked && !cache.unlocked,
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

data class EntitlementRecord(
    val unlocked: Boolean,
    val pending: Boolean,
    val lastQueryAt: Long,
    val grantSource: String,
    val lastQueryOk: Boolean,
) {
    companion object {
        val EMPTY = EntitlementRecord(
            unlocked = false,
            pending = false,
            lastQueryAt = 0L,
            grantSource = EntitlementPolicy.SOURCE_NONE,
            lastQueryOk = false,
        )
    }
}

data class PurchaseApply(
    val record: EntitlementRecord,
    val acknowledgeTokens: List<String>,
    val justGranted: Boolean,
    val reachedStore: Boolean,
)
