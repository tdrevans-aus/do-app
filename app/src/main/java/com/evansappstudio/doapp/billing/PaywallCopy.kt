package com.evansappstudio.doapp.billing

/**
 * The paywall's copy templates, read from strings.xml by the screen. Price
 * templates take the Play-formatted price as `%1$s`; [keepFree] takes the cap.
 */
data class PaywallTemplates(
    val introPrice: String,
    val thenRenewal: String,
    val renewalOnly: String,
    val loading: String,
    val smallPrint: String,
    val get: String,
    val renew: String,
    val keepFree: String,
    val active: String,
    val pending: String,
    val expired: String,
)

/**
 * What the paywall shows. [priceHeadline] is the big price line (or "Price
 * loading"); [priceDetail] sits under it; [smallPrint] only appears with the
 * intro offer. [statusLine] is null for a user who has never subscribed.
 */
data class PaywallText(
    val priceHeadline: String,
    val priceDetail: String?,
    val smallPrint: String?,
    val statusLine: String?,
    val buyLabel: String,
    val buyEnabled: Boolean,
    val keepFree: String,
)

/** Pure paywall text builder, so the three price variants are testable without Android. */
object PaywallCopy {
    fun build(
        ui: SubscriptionUiState,
        templates: PaywallTemplates,
        cap: Int? = FreeTier.ACTIVE_REMINDER_CAP,
    ): PaywallText {
        val price = ui.price
        val headline: String
        val detail: String?
        val small: String?
        when {
            price == null -> {
                headline = templates.loading
                detail = null
                small = null
            }
            price.intro != null -> {
                headline = templates.introPrice.fill(price.intro)
                detail = templates.thenRenewal.fill(price.renewal)
                small = templates.smallPrint.fill(price.renewal)
            }
            else -> {
                headline = templates.renewalOnly.fill(price.renewal)
                detail = null
                small = null
            }
        }
        val status = when (ui.state) {
            SubscriptionState.FREE -> null
            SubscriptionState.ACTIVE -> templates.active
            SubscriptionState.PENDING -> templates.pending
            SubscriptionState.EXPIRED -> templates.expired
        }
        return PaywallText(
            priceHeadline = headline,
            priceDetail = detail,
            smallPrint = small,
            statusLine = status,
            buyLabel = if (ui.state == SubscriptionState.EXPIRED) templates.renew else templates.get,
            buyEnabled = ui.buyEnabled,
            keepFree = templates.keepFree.fill(FreeTier.capLabel(cap)),
        )
    }

    private fun String.fill(value: String): String = replace("%1\$s", value)
}
