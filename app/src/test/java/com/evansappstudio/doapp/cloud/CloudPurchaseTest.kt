package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.billing.EntitlementRecord
import com.evansappstudio.doapp.billing.SubscriptionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 13 section F on the phone: verifyPurchase wins when present; Spec 10's local result is the offline fast path. */
class CloudPurchaseTest {
    private val pkg = "com.evansappstudio.doapp"
    private val localActive = EntitlementRecord(active = true, pending = false, lastQueryAt = 1L, grantSource = "play", lastQueryOk = true, everSubscribed = true)

    @Test
    fun aPurchaseSignsInVerifiesAndTheServerWins() = runBlocking {
        val cx = CloudFixture()
        cx.entitlement.record = localActive
        cx.port.verdict = VerifiedEntitlement("revoked", false, 5L, false, "do_unlimited", 9L)
        assertEquals(VerifyOutcome.VERIFIED, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = true, packageName = pkg))
        assertEquals(PurchaseCheck(pkg, "do_unlimited", "tok-1", "play"), cx.port.verifyCalls.single())
        assertEquals("uid-1", cx.store.identityUid())
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(cx.entitlement.record))
        assertEquals(CloudEntitlement.SOURCE_CLOUD, cx.entitlement.record.grantSource)
        assertEquals(1, cx.entitlement.changes)
    }

    @Test
    fun onResumeOnlyADeviceWithAnIdentityAsks() = runBlocking {
        val cx = CloudFixture()
        assertEquals(VerifyOutcome.NO_IDENTITY, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg))
        assertTrue(cx.port.calls.isEmpty())
        cx.signedIn()
        assertEquals(VerifyOutcome.VERIFIED, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg))
        assertTrue(cx.entitlement.record.active)
    }

    @Test
    fun withinTheHourTheCachedVerdictIsReappliedWithoutACall() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.port.verdict = VerifiedEntitlement("on_hold", false, null, true, "do_unlimited", 1L)
        cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg)
        // Play's local query then says active again (Play leaves account hold out, but say it lagged).
        cx.entitlement.record = localActive
        cx.fx.clockMillis += 10 * 60_000L
        assertEquals(VerifyOutcome.CACHED, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg))
        assertEquals(1, cx.port.verifyCalls.size)
        assertFalse(cx.entitlement.record.active)
        cx.fx.clockMillis += CloudEntitlement.VERIFY_EVERY_MS
        cx.port.verdict = VerifiedEntitlement("active", true, null, true, "do_unlimited", 2L)
        assertEquals(VerifyOutcome.VERIFIED, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg))
        assertTrue(cx.entitlement.record.active)
    }

    @Test
    fun unreachableKeepsTheLocalResult() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.entitlement.record = localActive
        cx.port.failVerify = CloudCallCode.UNAVAILABLE
        assertEquals(VerifyOutcome.KEPT_LOCAL, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = true, packageName = pkg))
        assertEquals(localActive, cx.entitlement.record)
        cx.port.failVerify = CloudCallCode.NOT_FOUND
        assertEquals(VerifyOutcome.KEPT_LOCAL, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = true, packageName = pkg))
        assertEquals(0, cx.entitlement.changes)
    }

    @Test
    fun aVerdictGoesStaleAndAnExpiredGrantIsNotTrustedOffline() {
        val ok = VerifiedEntitlement("active", true, 10_000L, true, "do_unlimited", 1L)
        assertEquals(ok, CloudEntitlement.fresh(ok, verifiedAt = 1_000L, now = 2_000L))
        assertNull(CloudEntitlement.fresh(ok, verifiedAt = 1_000L, now = 10_000L))
        val noExpiry = ok.copy(expiryTime = null)
        assertNull(CloudEntitlement.fresh(noExpiry, verifiedAt = 1_000L, now = 1_000L + CloudEntitlement.FRESH_FOR_MS))
        assertNull(CloudEntitlement.fresh(noExpiry, verifiedAt = 5_000L, now = 1_000L))
        assertNull(CloudEntitlement.fresh(null, 1L, 2L))
        assertTrue(CloudEntitlement.shouldVerify(justBought = true, lastVerifiedAt = 100L, now = 101L))
        assertFalse(CloudEntitlement.shouldVerify(justBought = false, lastVerifiedAt = 100L, now = 101L))
        assertTrue(CloudEntitlement.shouldVerify(justBought = false, lastVerifiedAt = 0L, now = 101L))
        val pending = CloudEntitlement.apply(EntitlementRecord.EMPTY, ok.copy(state = "pending", entitled = false), 5L)
        assertEquals(SubscriptionState.PENDING, SubscriptionState.of(pending))
        val grace = CloudEntitlement.apply(EntitlementRecord.EMPTY, ok.copy(state = "grace_period"), 5L)
        assertEquals(SubscriptionState.ACTIVE, SubscriptionState.of(grace))
        assertTrue(grace.everSubscribed)
    }

    @Test
    fun releaseBillingHandsPurchasedTokensToTheObserverAfterTheLocalWrite() {
        val text = projectFile("src/release/java/com/evansappstudio/doapp/billing/PlaySubscriptionBilling.kt").readText()
        val onPurchases = text.substringAfter("private fun onPurchases(").substringBefore("private fun queryDetails(")
        assertTrue(onPurchases.indexOf("store.write(applied.record)") < onPurchases.indexOf("notifyPurchases("))
        assertTrue(text.contains("it.purchaseState == Purchase.PurchaseState.PURCHASED"))
        val container = projectFile("src/main/java/com/evansappstudio/doapp/AppContainer.kt").readText()
        assertTrue(container.contains("billing.setPurchaseObserver"))
        assertTrue(container.contains("override fun changed() = billing.reload()"))
    }
}
