package com.evansappstudio.doapp.billing

/**
 * Free-tier cap and the Play product ids. This is the only place they live.
 *
 * [ACTIVE_REMINDER_CAP] is how many not-done reminders can exist without Do Unlimited.
 * It counts every not-done reminder, repeating or not, whatever created it.
 * It is the only free-tier limit (decision of 27 Nov 2026: there is no
 * separate repeating cap). Null or 0 turns the cap off. An active
 * subscription always removes it. There is no time trial.
 *
 * Do Unlimited is the auto-renewing subscription [PRODUCT_ID] with one base
 * plan [BASE_PLAN_ID] and one new-customer offer [INTRO_OFFER_ID]. Prices
 * always come from Play.
 */
object FreeTier {
    val ACTIVE_REMINDER_CAP: Int? = 5

    const val PRODUCT_ID = "do_unlimited"
    const val BASE_PLAN_ID = "annual"
    const val INTRO_OFFER_ID = "first-year"

    private const val MANAGE_URL = "https://play.google.com/store/account/subscriptions"

    fun blocksAdd(activeCount: Int, unlocked: Boolean, cap: Int? = ACTIVE_REMINDER_CAP): Boolean {
        if (unlocked) return false
        if (cap == null || cap <= 0) return false
        return activeCount >= cap
    }

    fun capLabel(cap: Int? = ACTIVE_REMINDER_CAP): String = when (cap) {
        null, 0 -> "none"
        else -> cap.toString()
    }

    /** Play's management page for Do Unlimited (Play deep link with sku and package). */
    fun manageSubscriptionUrl(packageName: String, productId: String = PRODUCT_ID): String =
        "$MANAGE_URL?sku=$productId&package=$packageName"
}
