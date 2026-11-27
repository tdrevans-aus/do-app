package com.evansappstudio.doapp.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Do Unlimited subscription billing. Release talks to Play. Debug uses a fake that never connects.
 * Do not name an implementation UserUnlock: that name is the device-unlock check.
 */
interface SubscriptionBilling {
    val ui: StateFlow<SubscriptionUiState>

    fun start()

    fun onResume()

    fun buy(activity: Activity)

    fun restore()

    /** The offer price text from Play (or the debug fake), null until it has loaded. */
    fun price(): PriceInfo?

    /** Dump source: fake, play, or cache. */
    fun dumpSource(): String

    fun clearCelebrate()
}

/**
 * The state the user sees, derived from the cached [EntitlementRecord].
 * Only [ACTIVE] lifts the free cap. [EXPIRED] is free limits with different copy.
 */
enum class SubscriptionState {
    FREE,
    ACTIVE,
    PENDING,
    EXPIRED,
    ;

    val liftsCap: Boolean get() = this == ACTIVE

    companion object {
        fun of(record: EntitlementRecord): SubscriptionState = when {
            record.active -> ACTIVE
            record.pending -> PENDING
            record.everSubscribed -> EXPIRED
            else -> FREE
        }
    }
}

/**
 * Price text exactly as Play formats it. [intro] is the first-year offer price,
 * null when Play did not return the offer for this user. [renewal] is the
 * yearly price after any intro period.
 */
data class PriceInfo(
    val intro: String?,
    val renewal: String,
)

data class SubscriptionUiState(
    val state: SubscriptionState,
    val price: PriceInfo?,
    val failed: Boolean,
    val celebrate: Boolean,
    /** The last connection or details query to Play failed. A price already loaded is kept. */
    val storeUnavailable: Boolean = false,
) {
    val active: Boolean get() = state == SubscriptionState.ACTIVE

    val buyEnabled: Boolean
        get() = price != null && state != SubscriptionState.ACTIVE && state != SubscriptionState.PENDING

    companion object {
        fun from(
            record: EntitlementRecord,
            price: PriceInfo?,
            failed: Boolean,
            celebrate: Boolean,
            storeUnavailable: Boolean = false,
        ) = SubscriptionUiState(
            state = SubscriptionState.of(record),
            price = price,
            failed = failed,
            celebrate = celebrate,
            storeUnavailable = storeUnavailable,
        )
    }
}
