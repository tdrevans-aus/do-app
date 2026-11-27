package com.evansappstudio.doapp.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec 10 QA (Claude test agent, 27 Nov 2026). Extra checks for gaps in the
 * builder's AT-1 to AT-6 tests. Tests that expose a bug are marked
 * "QA: fails until ... is fixed" and are expected to fail on 077ca8c.
 */
class Spec10QaTest {

    // ---- Policy (AT-1, AT-2, AT-3) ----

    @Test
    fun purchasedAndPendingTogetherIsActiveNotPending() {
        val result = apply(
            EntitlementRecord.EMPTY,
            listOf(view(PurchaseViewState.PENDING, false, "p"), view(PurchaseViewState.PURCHASED, true, "a")),
        )
        assertEquals(SubscriptionState.ACTIVE, SubscriptionState.of(result.record))
        assertFalse(result.record.pending)
    }

    @Test
    fun aMultiProductPurchaseContainingDoUnlimitedGrants() {
        val purchase = PurchaseView(listOf("something_else", FreeTier.PRODUCT_ID), PurchaseViewState.PURCHASED, false, "multi")
        val result = apply(EntitlementRecord.EMPTY, listOf(purchase))
        assertTrue(result.record.active)
        assertEquals(listOf("multi"), result.acknowledgeTokens)
    }

    @Test
    fun aRefreshWhileAlreadyActiveDoesNotCelebrateAgain() {
        val first = apply(EntitlementRecord.EMPTY, listOf(view(PurchaseViewState.PURCHASED, true, "a")))
        assertTrue(first.justGranted)
        val second = apply(first.record, listOf(view(PurchaseViewState.PURCHASED, true, "a")))
        assertFalse(second.justGranted)
        assertTrue(second.record.active)
    }

    @Test
    fun aFailedQueryNeverGrantsNorAcknowledgesEvenWithPurchasesPassed() {
        val result = EntitlementPolicy.apply(
            cache = EntitlementRecord.EMPTY,
            querySucceeded = false,
            purchases = listOf(view(PurchaseViewState.PURCHASED, false, "x")),
            now = 5L,
            source = EntitlementPolicy.SOURCE_PLAY,
        )
        assertEquals(EntitlementRecord.EMPTY, result.record)
        assertFalse(result.record.everSubscribed)
        assertTrue(result.acknowledgeTokens.isEmpty())
        assertFalse(result.reachedStore)
    }

    @Test
    fun aLapsedSubscriberWithAPendingRenewalStillDoesNotLiftTheCap() {
        val lapsed = EntitlementRecord.EMPTY.copy(lastQueryOk = true, everSubscribed = true)
        val result = apply(lapsed, listOf(view(PurchaseViewState.PENDING, false, "p")))
        assertEquals(SubscriptionState.PENDING, SubscriptionState.of(result.record))
        assertFalse(SubscriptionState.of(result.record).liftsCap)
        assertTrue(result.record.everSubscribed)
        assertTrue(result.acknowledgeTokens.isEmpty())
        // When the pending payment fails and Play drops it, the user is EXPIRED again, not FREE.
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(apply(result.record, emptyList()).record))
    }

    @Test
    fun theStoreKeepsTheSpec7KeyAndRoundTripsEveryField() {
        val prefs = MemoryPrefs()
        val store = EntitlementStore(prefs)
        val record = EntitlementRecord(
            active = false,
            pending = true,
            lastQueryAt = 42L,
            grantSource = EntitlementPolicy.SOURCE_PLAY,
            lastQueryOk = true,
            everSubscribed = true,
        )
        store.write(record)
        assertTrue(prefs.contains("unlocked"))
        assertEquals(record, EntitlementStore(prefs).read())
    }

    // QA: fails until the policy treats an already active cache as a past grant (legacy cache without everSubscribed) is fixed
    @Test
    fun anActiveCacheWrittenBeforeEverSubscribedExistedRevokesToExpiredNotFree() {
        // A Spec 7 era (or Auto Backup restored) cache: unlocked=true, no ever_subscribed key.
        val prefs = MemoryPrefs()
        prefs.edit()
            .putBoolean("unlocked", true)
            .putBoolean("pending", false)
            .putLong("last_query_at", 1L)
            .putString("grant_source", EntitlementPolicy.SOURCE_PLAY)
            .putBoolean("last_query_ok", true)
            .commit()
        val cached = EntitlementStore(prefs).read()
        assertEquals(SubscriptionState.ACTIVE, SubscriptionState.of(cached))
        // The grant happened, so a revoke must read as EXPIRED ("Renew"), per section C.
        val revoked = apply(cached, emptyList())
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(revoked.record))
    }

    // ---- Free tier (AT-5) ----

    @Test
    fun onlyActiveLiftsTheCapAndFreeEqualsExpiredAtEveryCount() {
        for (count in 0..8) {
            val free = FreeTier.blocksAdd(count, SubscriptionState.FREE.liftsCap)
            assertEquals(count >= 5, free)
            assertEquals(free, FreeTier.blocksAdd(count, SubscriptionState.EXPIRED.liftsCap))
            assertEquals(free, FreeTier.blocksAdd(count, SubscriptionState.PENDING.liftsCap))
            assertFalse(FreeTier.blocksAdd(count, SubscriptionState.ACTIVE.liftsCap))
        }
        assertEquals(listOf(SubscriptionState.ACTIVE), SubscriptionState.values().filter { it.liftsCap })
    }

    @Test
    fun theAppContainerCapWiringUsesTheDerivedState() {
        // AT-5 tests set the fixture's paid flag directly; check the real wiring line.
        val text = source("app/src/main/java/com/evansappstudio/doapp/AppContainer.kt")
        assertTrue(text.contains("paidUnlocked = { SubscriptionState.of(entitlement.read()).liftsCap }"))
    }

    // ---- Offers (AT-4) ----

    @Test
    fun offerChoiceIgnoresListOrderAndUsesTheLastPhaseAsRenewal() {
        val base = OfferView(FreeTier.BASE_PLAN_ID, null, "base", listOf("A$9.99"))
        val intro = OfferView(FreeTier.BASE_PLAN_ID, FreeTier.INTRO_OFFER_ID, "intro", listOf("A$3.99", "A$9.99"))
        assertEquals("intro", SubscriptionOffers.choose(listOf(intro, base))!!.offerToken)
        assertEquals("intro", SubscriptionOffers.choose(listOf(base, intro))!!.offerToken)
        val threePhase = intro.copy(phasePrices = listOf("A$1.99", "A$3.99", "A$9.99"))
        assertEquals(PriceInfo("A$1.99", "A$9.99"), SubscriptionOffers.choose(listOf(threePhase))!!.price)
        // An intro offer id on some other base plan is not ours.
        val strayIntro = intro.copy(basePlanId = "monthly", offerToken = "stray")
        assertEquals("base", SubscriptionOffers.choose(listOf(strayIntro, base))!!.offerToken)
        assertNull(SubscriptionOffers.choose(listOf(strayIntro)))
    }

    @Test
    fun introOnlyReturnedStillSellsTheIntroWithItsRenewal() {
        val intro = OfferView(FreeTier.BASE_PLAN_ID, FreeTier.INTRO_OFFER_ID, "intro", listOf("A$3.99", "A$9.99"))
        val choice = SubscriptionOffers.choose(listOf(intro))
        assertNotNull(choice)
        assertEquals("A$9.99", choice!!.price.renewal)
    }

    // ---- Copy (AT-6) ----

    @Test
    fun wheneverAnIntroPriceIsShownTheRenewalPriceIsShownWithIt() {
        val t = templates()
        for (state in SubscriptionState.values()) {
            val text = PaywallCopy.build(ui(state, PriceInfo("A$3.99", "A$9.99")), t)
            if (text.priceHeadline.contains("A$3.99")) {
                assertTrue(state.name, text.priceDetail.orEmpty().contains("A$9.99"))
                assertTrue(state.name, text.smallPrint.orEmpty().contains("A$9.99"))
            }
        }
    }

    @Test
    fun pricesWithRegexOrFormatCharactersAreInsertedLiterally() {
        val t = templates()
        val text = PaywallCopy.build(ui(SubscriptionState.FREE, PriceInfo("$1 %d \\", "100%")), t)
        assertEquals("$1 %d \\ for your first year", text.priceHeadline)
        assertEquals("then 100% a year. Cancel any time in Google Play.", text.priceDetail)
    }

    @Test
    fun noPriceIsHardCodedInMainOrReleaseSources() {
        val roots = listOf("app/src/main/java", "app/src/release/java", "app/src/main/res/values")
        val pricePattern = Regex("""A\$\s?\d|\d+\.99""")
        val hits = roots.map { resolve(it) }.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile }.mapNotNull { file ->
                if (pricePattern.containsMatchIn(file.readText())) file.path else null
            }.toList()
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun copyIsAustralianEnglishWithNoEmDashes() {
        val strings = resolve("app/src/main/res/values/strings.xml").readText()
        for (us in listOf("catalog<", "catalog ", "canceled", "license ", "color")) {
            assertFalse(us, strings.contains(us))
        }
        assertFalse(strings.contains('—'))
    }

    // QA: fails until the Settings Do Unlimited row shows the renewal price beside the intro price is fixed
    @Test
    fun theSettingsRowNeverShowsTheIntroPriceWithoutTheRenewalPrice() {
        // SnoozeSettingsScreen shows `copy.statusLine ?: copy.priceHeadline`: for a FREE user with
        // the intro offer that is "A$3.99 for your first year" alone, with no renewal price.
        val text = source("app/src/main/java/com/evansappstudio/doapp/ui/SnoozeSettingsScreen.kt")
        val usesHeadline = text.contains("priceHeadline")
        val usesRenewal = text.contains("priceDetail") || text.contains("smallPrint") || text.contains(".renewal")
        assertTrue("Settings row shows the intro headline without the renewal price", !usesHeadline || usesRenewal)
    }

    // ---- Release billing (read as source; the billing library is release-only) ----

    @Test
    fun releaseBillingUsesSubsOnlyAndPassesTheOfferToken() {
        val text = source("app/src/release/java/com/evansappstudio/doapp/billing/PlaySubscriptionBilling.kt")
        assertFalse(text.contains("ProductType.INAPP"))
        assertEquals(2, Regex("""ProductType\.SUBS\)""").findAll(text).count())
        assertTrue(text.contains(".setOfferToken(token)"))
        assertTrue(text.contains("enableAutoServiceReconnection()"))
        assertFalse(text.contains("includeSuspendedSubscriptions"))
        assertFalse(Regex("""A\$\d""").containsMatchIn(text))
    }

    // QA: fails until buy() treats ITEM_ALREADY_OWNED from launchBillingFlow as "re-query", not "failed", is fixed
    @Test
    fun anAlreadyOwnedLaunchResultRequeriesInsteadOfShowingPurchaseFailed() {
        // launchBillingFlow can return ITEM_ALREADY_OWNED synchronously (BillingClient reference),
        // for example when the cache says EXPIRED but the user renewed elsewhere. buy() only
        // excuses OK and USER_CANCELED, so it shows "Purchase failed" and does not re-query.
        val text = source("app/src/release/java/com/evansappstudio/doapp/billing/PlaySubscriptionBilling.kt")
        val buy = text.substringAfter("override fun buy(").substringBefore("override fun restore(")
        assertTrue(buy.contains("ITEM_ALREADY_OWNED"))
    }

    // ---- helpers ----

    private fun ui(state: SubscriptionState, price: PriceInfo?) =
        SubscriptionUiState(state = state, price = price, failed = false, celebrate = false)

    private fun view(state: PurchaseViewState, acknowledged: Boolean, token: String) =
        PurchaseView(listOf(FreeTier.PRODUCT_ID), state, acknowledged, token)

    private fun apply(cache: EntitlementRecord, purchases: List<PurchaseView>) = EntitlementPolicy.apply(
        cache = cache,
        querySucceeded = true,
        purchases = purchases,
        now = 10L,
        source = EntitlementPolicy.SOURCE_PLAY,
    )

    private fun resolve(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        return File(path.removePrefix("app/"))
    }

    private fun source(path: String): String = resolve(path).readText()

    private fun templates(): PaywallTemplates {
        val s = Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""")
            .findAll(source("app/src/main/res/values/strings.xml"))
            .associate { it.groupValues[1] to it.groupValues[2] }
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
}
