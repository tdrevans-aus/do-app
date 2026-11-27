package com.evansappstudio.doapp.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Spec 10 unit acceptance tests (AT-5 is in alarm/SubscriptionCapTest). */
class SubscriptionLogicTest {
    // AT-1
    @Test
    fun at1ActiveGrantsPendingDoesNotAndAFailedQueryKeepsTheCache() {
        val purchased = view(PurchaseViewState.PURCHASED, acknowledged = true, token = "t1")
        val active = apply(EntitlementRecord.EMPTY, succeeded = true, listOf(purchased))
        assertEquals(SubscriptionState.ACTIVE, SubscriptionState.of(active.record))
        assertTrue(SubscriptionState.of(active.record).liftsCap)
        assertTrue(active.justGranted)

        val pending = view(PurchaseViewState.PENDING, acknowledged = false, token = "t2")
        val waiting = apply(EntitlementRecord.EMPTY, succeeded = true, listOf(pending))
        assertEquals(SubscriptionState.PENDING, SubscriptionState.of(waiting.record))
        assertFalse(SubscriptionState.of(waiting.record).liftsCap)
        assertFalse(waiting.record.everSubscribed)

        val other = view(PurchaseViewState.OTHER, acknowledged = false, token = "t3")
        assertEquals(
            SubscriptionState.FREE,
            SubscriptionState.of(apply(EntitlementRecord.EMPTY, succeeded = true, listOf(other)).record),
        )

        val never = apply(EntitlementRecord.EMPTY, succeeded = true, emptyList())
        assertEquals(SubscriptionState.FREE, SubscriptionState.of(never.record))
        val lapsed = apply(active.record, succeeded = true, emptyList())
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(lapsed.record))
        assertFalse(SubscriptionState.of(lapsed.record).liftsCap)

        // A failed query keeps whatever was cached, active or expired.
        assertEquals(active.record, apply(active.record, succeeded = false, emptyList(), now = 99L).record)
        assertEquals(lapsed.record, apply(lapsed.record, succeeded = false, emptyList(), now = 99L).record)

        // The old one-time product no longer grants anything.
        val oldUnlock = view(PurchaseViewState.PURCHASED, acknowledged = true, token = "t4", product = "do_unlock")
        assertEquals(
            SubscriptionState.FREE,
            SubscriptionState.of(apply(EntitlementRecord.EMPTY, succeeded = true, listOf(oldUnlock)).record),
        )
    }

    // AT-2
    @Test
    fun at2EverSubscribedIsStickyAndSurvivesARestart() {
        val prefs = MemoryPrefs()
        val store = EntitlementStore(prefs)
        assertFalse(store.hasRecord())
        assertEquals(EntitlementRecord.EMPTY, store.read())

        val purchased = view(PurchaseViewState.PURCHASED, acknowledged = true, token = "t1")
        store.write(apply(store.read(), succeeded = true, listOf(purchased), now = 1L).record)
        assertTrue(store.read().everSubscribed)

        store.write(apply(store.read(), succeeded = true, emptyList(), now = 2L).record)
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(store.read()))

        // A pending renewal and another revoke still do not clear it.
        val pending = view(PurchaseViewState.PENDING, acknowledged = false, token = "t2")
        store.write(apply(store.read(), succeeded = true, listOf(pending), now = 3L).record)
        assertEquals(SubscriptionState.PENDING, SubscriptionState.of(store.read()))
        store.write(apply(store.read(), succeeded = true, emptyList(), now = 4L).record)

        val restarted = EntitlementStore(prefs)
        val record = restarted.read()
        assertTrue(record.everSubscribed)
        assertFalse(record.active)
        assertEquals(4L, record.lastQueryAt)
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(record))

        // Offline after the restart: the cache is kept as it is.
        assertEquals(record, apply(record, succeeded = false, emptyList(), now = 5L).record)

        // Renewing goes back to ACTIVE and celebrates.
        val renewed = apply(record, succeeded = true, listOf(purchased), now = 6L)
        assertEquals(SubscriptionState.ACTIVE, SubscriptionState.of(renewed.record))
        assertTrue(renewed.justGranted)
    }

    // AT-3
    @Test
    fun at3OnlyUnacknowledgedPurchasedSubscriptionsAreAcknowledged() {
        val fresh = view(PurchaseViewState.PURCHASED, acknowledged = false, token = "new-token")
        val renewedAlreadyAcked = view(PurchaseViewState.PURCHASED, acknowledged = true, token = "old-token")
        val pending = view(PurchaseViewState.PENDING, acknowledged = false, token = "pending-token")
        val otherProduct = view(PurchaseViewState.PURCHASED, acknowledged = false, token = "other", product = "other")
        val blank = view(PurchaseViewState.PURCHASED, acknowledged = false, token = " ")

        val result = apply(
            EntitlementRecord.EMPTY,
            succeeded = true,
            listOf(fresh, renewedAlreadyAcked, pending, otherProduct, blank, fresh),
        )
        assertEquals(listOf("new-token"), result.acknowledgeTokens)
        assertFalse(result.record.toString().contains("new-token"))

        assertTrue(apply(EntitlementRecord.EMPTY, succeeded = true, listOf(renewedAlreadyAcked)).acknowledgeTokens.isEmpty())
        assertTrue(apply(EntitlementRecord.EMPTY, succeeded = true, listOf(pending)).acknowledgeTokens.isEmpty())
        assertTrue(apply(EntitlementRecord.EMPTY, succeeded = false, listOf(fresh)).acknowledgeTokens.isEmpty())
    }

    // AT-4
    @Test
    fun at4TheIntroOfferIsChosenWhenPlayReturnsItElseTheBasePlan() {
        val base = OfferView(FreeTier.BASE_PLAN_ID, offerId = null, offerToken = "base-token", phasePrices = listOf("A$9.99"))
        val intro = OfferView(
            FreeTier.BASE_PLAN_ID,
            offerId = FreeTier.INTRO_OFFER_ID,
            offerToken = "intro-token",
            phasePrices = listOf("A$3.99", "A$9.99"),
        )
        val both = SubscriptionOffers.choose(listOf(base, intro))!!
        assertEquals("intro-token", both.offerToken)
        assertEquals(PriceInfo(intro = "A$3.99", renewal = "A$9.99"), both.price)

        val baseOnly = SubscriptionOffers.choose(listOf(base))!!
        assertEquals("base-token", baseOnly.offerToken)
        assertEquals(PriceInfo(intro = null, renewal = "A$9.99"), baseOnly.price)

        // Prices are whatever Play formats, never assumed.
        val euro = SubscriptionOffers.choose(listOf(intro.copy(phasePrices = listOf("2,49 €", "6,99 €"))))!!
        assertEquals(PriceInfo("2,49 €", "6,99 €"), euro.price)

        // Other base plans, unknown offers and broken data are ignored.
        val otherPlan = base.copy(basePlanId = "monthly", offerToken = "monthly-token")
        val otherOffer = intro.copy(offerId = "winback", offerToken = "winback-token")
        assertEquals("base-token", SubscriptionOffers.choose(listOf(otherPlan, otherOffer, base))!!.offerToken)
        assertEquals("base-token", SubscriptionOffers.choose(listOf(intro.copy(offerToken = ""), base))!!.offerToken)
        assertEquals("base-token", SubscriptionOffers.choose(listOf(intro.copy(phasePrices = listOf("A$3.99")), base))!!.offerToken)
        assertNull(SubscriptionOffers.choose(emptyList()))
        assertNull(SubscriptionOffers.choose(listOf(otherPlan)))
        assertNull(SubscriptionOffers.choose(listOf(base.copy(phasePrices = listOf("")))))
    }

    // AT-6
    @Test
    fun at6PaywallTextHasTheThreePriceVariants() {
        val t = templatesFromStringsXml()
        val free = ui(SubscriptionState.FREE, PriceInfo("A$3.99", "A$9.99"))

        val withIntro = PaywallCopy.build(free, t)
        assertEquals("A$3.99 for your first year", withIntro.priceHeadline)
        assertEquals("then A$9.99 a year. Cancel any time in Google Play.", withIntro.priceDetail)
        assertEquals("Renews at A$9.99 a year after the first year unless cancelled.", withIntro.smallPrint)
        assertEquals("Get Do Unlimited", withIntro.buyLabel)
        assertEquals("Keep 5 for free", withIntro.keepFree)
        assertTrue(withIntro.buyEnabled)
        assertNull(withIntro.statusLine)

        val renewalOnly = PaywallCopy.build(free.copy(price = PriceInfo(null, "A$9.99")), t)
        assertEquals("A$9.99 a year. Cancel any time in Google Play.", renewalOnly.priceHeadline)
        assertNull(renewalOnly.priceDetail)
        assertNull(renewalOnly.smallPrint)
        assertTrue(renewalOnly.buyEnabled)

        val loading = PaywallCopy.build(free.copy(price = null), t)
        assertEquals("Price loading", loading.priceHeadline)
        assertNull(loading.priceDetail)
        assertNull(loading.smallPrint)
        assertFalse(loading.buyEnabled)

        val expired = PaywallCopy.build(ui(SubscriptionState.EXPIRED, PriceInfo(null, "A$9.99")), t)
        assertEquals("Renew Do Unlimited", expired.buyLabel)
        assertEquals("Your subscription has ended. Renew any time.", expired.statusLine)
        assertTrue(expired.buyEnabled)

        val pending = PaywallCopy.build(ui(SubscriptionState.PENDING, PriceInfo("A$3.99", "A$9.99")), t)
        assertEquals("Payment pending", pending.statusLine)
        assertFalse(pending.buyEnabled)

        val active = PaywallCopy.build(ui(SubscriptionState.ACTIVE, PriceInfo("A$3.99", "A$9.99")), t)
        assertEquals("You have Do Unlimited.", active.statusLine)
        assertFalse(active.buyEnabled)

        // The fixed copy from the spec is in strings.xml, with no em dashes.
        val strings = stringsXml()
        for (line in listOf(
            "Keep everything on track",
            "Do Unlimited removes the limit, for reminders you add, pick from the catalogue, or get from your assistant.",
            "Unlimited reminders and repeats",
            "Everything else stays free, AI assistants included",
            "If you stop paying, your reminders keep nagging",
            "Manage subscription",
        )) {
            assertTrue(line, strings.values.contains(line))
        }
        assertFalse(strings.values.any { it.contains('—') })
    }

    // AT-6 (QA bug 5)
    @Test
    fun anUnreachablePlayStoreSaysSoAndKeepsALoadedPrice() {
        val t = templatesFromStringsXml().copy(unavailable = stringsXml().getValue("paywall_store_unavailable"))
        val free = ui(SubscriptionState.FREE, null).copy(storeUnavailable = true)
        val down = PaywallCopy.build(free, t)
        assertEquals("Can't reach Google Play right now", down.priceHeadline)
        assertFalse(down.buyEnabled)
        assertNull(down.smallPrint)

        // A price loaded before the failure is still shown, with its renewal price.
        val kept = PaywallCopy.build(free.copy(price = PriceInfo("A$3.99", "A$9.99")), t)
        assertEquals("A$3.99 for your first year", kept.priceHeadline)
        assertEquals("then A$9.99 a year. Cancel any time in Google Play.", kept.priceDetail)

        // Without the unavailable flag it is still "Price loading".
        assertEquals("Price loading", PaywallCopy.build(free.copy(storeUnavailable = false), t).priceHeadline)
    }

    @Test
    fun theManageLinkIsPlaysSubscriptionPageForDo() {
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=do_unlimited&package=com.evansappstudio.doapp",
            FreeTier.manageSubscriptionUrl("com.evansappstudio.doapp"),
        )
        assertEquals("annual", FreeTier.BASE_PLAN_ID)
        assertEquals("first-year", FreeTier.INTRO_OFFER_ID)
    }

    private fun ui(state: SubscriptionState, price: PriceInfo?) =
        SubscriptionUiState(state = state, price = price, failed = false, celebrate = false)

    private fun stringsXml(): Map<String, String> {
        val candidates = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
        val text = candidates.first { it.isFile }.readText()
        return Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private fun templatesFromStringsXml(): PaywallTemplates {
        val s = stringsXml()
        return PaywallTemplates(
            introPrice = s.getValue("paywall_price_intro"),
            thenRenewal = s.getValue("paywall_price_then"),
            renewalOnly = s.getValue("paywall_price_renewal"),
            loading = s.getValue("paywall_price_loading"),
            smallPrint = s.getValue("paywall_small_print"),
            get = s.getValue("paywall_get"),
            renew = s.getValue("paywall_renew"),
            keepFree = s.getValue("paywall_keep_free"),
            active = s.getValue("paywall_active"),
            pending = s.getValue("paywall_pending"),
            expired = s.getValue("paywall_expired"),
        )
    }

    private fun view(
        state: PurchaseViewState,
        acknowledged: Boolean,
        token: String,
        product: String = FreeTier.PRODUCT_ID,
    ) = PurchaseView(listOf(product), state, acknowledged, token)

    private fun apply(
        cache: EntitlementRecord,
        succeeded: Boolean,
        purchases: List<PurchaseView>,
        now: Long = 10L,
    ) = EntitlementPolicy.apply(
        cache = cache,
        querySucceeded = succeeded,
        purchases = purchases,
        now = now,
        source = EntitlementPolicy.SOURCE_PLAY,
    )
}
