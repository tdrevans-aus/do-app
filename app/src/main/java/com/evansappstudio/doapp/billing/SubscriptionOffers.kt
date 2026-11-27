package com.evansappstudio.doapp.billing

/**
 * A small view of Play's `ProductDetails.SubscriptionOfferDetails`, so the offer
 * choice can be tested without Play. [offerId] is null for the base plan's own
 * offer. [phasePrices] are the pricing phases' formatted prices, in Play's order.
 */
data class OfferView(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val phasePrices: List<String>,
)

/** The offer to buy and the price text to show for it. */
data class OfferChoice(
    val offerToken: String,
    val price: PriceInfo,
)

/**
 * Chooses which subscription offer the paywall sells.
 *
 * Play returns only the offers this user is eligible for. If the intro offer
 * is among them (on our base plan, with at least two pricing phases), it is
 * chosen: intro is its first phase's price and renewal its last. Otherwise the
 * base plan offer is chosen with renewal only. Offers on other base plans, or
 * with a blank token or price, are ignored. Null means there is nothing to sell yet.
 */
object SubscriptionOffers {
    fun choose(
        offers: List<OfferView>,
        basePlanId: String = FreeTier.BASE_PLAN_ID,
        introOfferId: String = FreeTier.INTRO_OFFER_ID,
    ): OfferChoice? {
        val usable = offers.filter { offer ->
            offer.basePlanId == basePlanId &&
                offer.offerToken.isNotBlank() &&
                offer.phasePrices.isNotEmpty() &&
                offer.phasePrices.all { it.isNotBlank() }
        }
        val intro = usable.firstOrNull { it.offerId == introOfferId && it.phasePrices.size >= 2 }
        if (intro != null) {
            return OfferChoice(
                offerToken = intro.offerToken,
                price = PriceInfo(intro = intro.phasePrices.first(), renewal = intro.phasePrices.last()),
            )
        }
        val base = usable.firstOrNull { it.offerId == null } ?: return null
        return OfferChoice(
            offerToken = base.offerToken,
            price = PriceInfo(intro = null, renewal = base.phasePrices.last()),
        )
    }
}
