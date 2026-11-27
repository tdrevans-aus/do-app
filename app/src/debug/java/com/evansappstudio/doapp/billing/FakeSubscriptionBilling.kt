package com.evansappstudio.doapp.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug billing. It never opens a BillingClient and never calls Play.
 * A fresh debug install is [SubscriptionState.ACTIVE] so the Spec 1 to 9 runners keep working.
 * It reports fake prices so the paywall can be checked; [intro] toggles whether
 * the first-year offer is "returned". The toggle lives in memory only.
 */
class FakeSubscriptionBilling(
    private val store: EntitlementStore,
) : SubscriptionBilling {
    private var failed = false
    private var celebrate = false

    /** Whether the fake returns the first-year offer. Resets to true when the process restarts. */
    var intro: Boolean = true
        private set

    private val uiState = MutableStateFlow(current())
    override val ui: StateFlow<SubscriptionUiState> = uiState.asStateFlow()

    override fun start() {
        if (!store.hasRecord()) {
            store.write(record(SubscriptionState.ACTIVE, System.currentTimeMillis()))
        }
        publish()
    }

    override fun onResume() {
        publish()
    }

    /** Stands in for a completed Play purchase: grants [SubscriptionState.ACTIVE]. Never connects. */
    override fun buy(activity: Activity) {
        applyState(STATE_ACTIVE)
    }

    override fun restore() {
        publish()
    }

    override fun price(): PriceInfo? = if (intro) {
        PriceInfo(intro = FAKE_INTRO_PRICE, renewal = FAKE_RENEWAL_PRICE)
    } else {
        PriceInfo(intro = null, renewal = FAKE_RENEWAL_PRICE)
    }

    override fun dumpSource(): String = "fake"

    override fun clearCelebrate() {
        celebrate = false
        publish()
    }

    fun setIntro(enabled: Boolean) {
        intro = enabled
        publish()
    }

    /**
     * free, active, pending or expired (Spec 7's unlocked and locked are accepted
     * as active and free). Writes the same cache fields a Play grant or revoke
     * would. Unlike Play, free also clears everSubscribed so both states can be checked.
     */
    fun applyState(state: String): EntitlementRecord? {
        val target = parseState(state) ?: return null
        val previous = store.read()
        val record = record(target, System.currentTimeMillis())
        celebrate = record.active && !previous.active
        failed = false
        store.write(record)
        publish()
        return record
    }

    private fun record(state: SubscriptionState, now: Long) = EntitlementRecord(
        active = state == SubscriptionState.ACTIVE,
        pending = state == SubscriptionState.PENDING,
        lastQueryAt = now,
        grantSource = EntitlementPolicy.SOURCE_FAKE,
        lastQueryOk = true,
        everSubscribed = state == SubscriptionState.ACTIVE || state == SubscriptionState.EXPIRED,
    )

    private fun publish() {
        uiState.value = current()
    }

    private fun current(): SubscriptionUiState = SubscriptionUiState.from(
        record = store.read(),
        price = price(),
        failed = failed,
        celebrate = celebrate,
    )

    companion object {
        const val FAKE_INTRO_PRICE = "A$3.99"
        const val FAKE_RENEWAL_PRICE = "A$9.99"
        const val STATE_ACTIVE = "active"

        fun parseState(raw: String): SubscriptionState? = when (raw.trim().lowercase()) {
            "free", "locked" -> SubscriptionState.FREE
            STATE_ACTIVE, "unlocked" -> SubscriptionState.ACTIVE
            "pending" -> SubscriptionState.PENDING
            "expired" -> SubscriptionState.EXPIRED
            else -> null
        }
    }
}

fun createSubscriptionBilling(context: android.content.Context, store: EntitlementStore): SubscriptionBilling {
    return FakeSubscriptionBilling(store)
}
