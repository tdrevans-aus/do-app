package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.backup.parseJson
import com.evansappstudio.doapp.billing.EntitlementRecord
import com.evansappstudio.doapp.billing.SubscriptionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 13 after QA (M1, client half): purchases are bound to this installation with
 * obfuscatedAccountId, and `other_device` never switches a real buyer off.
 */
class PurchaseBindingTest {
    private val pkg = "com.evansappstudio.doapp"
    private val localActive = EntitlementRecord(active = true, pending = false, lastQueryAt = 1L, grantSource = "play", lastQueryOk = true, everSubscribed = true)
    private val localFree = EntitlementRecord.EMPTY

    // The hash: base64url(SHA-256("do-account:" + uid)), no padding, 43 characters (vectors from Python's hashlib).
    @Test
    fun theAccountIdIsTheContractsHash() {
        assertEquals("CDhzuTuPA-eMpYqBqWMW_JHG_too_1P8TwSR5kIUg88", CloudEntitlement.obfuscatedAccountId("uid-1"))
        assertEquals("W_1ZniDpB_4I6vF3k2E3FhkXffiCLzRBDbdH_cXZ-KQ", CloudEntitlement.obfuscatedAccountId("aBcD1234efGH5678ijKL9012mnOP"))
        for (uid in listOf("a", "uid-1", "x".repeat(128), "ünïcödé")) {
            val id = CloudEntitlement.obfuscatedAccountId(uid)
            assertEquals(43, id.length)
            assertTrue(id, id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
            assertFalse(id.contains(uid))
        }
        assertTrue(CloudEntitlement.obfuscatedAccountId("a") != CloudEntitlement.obfuscatedAccountId("b"))
        // The server derives it the same way.
        val server = repoFile("cloud/functions/src/billing/binding.ts").readText()
        assertTrue(server.contains("`do-account:\${uid}`"))
        assertTrue(server.contains("digest(\"base64url\")"))
        val contract = repoFile("cloud/CONTRACT.md").readText()
        assertTrue(contract.contains("base64url( SHA-256( \"do-account:\" + uid ) )"))
    }

    // The state mapping: other_device keeps the local result either way; transferred is not entitled.
    @Test
    fun otherDeviceIsNoOpinionAndTransferredIsNotEntitled() {
        fun verdict(state: String, entitled: Boolean) = VerifiedEntitlement(state, entitled, null, true, "do_unlimited", 1L)
        val other = verdict("other_device", false)
        assertTrue(other.noOpinion)
        assertFalse(CloudEntitlement.hasOpinion(other))
        assertEquals(localActive, CloudEntitlement.apply(localActive, other, 5L))
        assertEquals(localFree, CloudEntitlement.apply(localFree, other, 5L))
        assertNull(CloudEntitlement.fresh(other, verifiedAt = 1L, now = 2L))
        // Even if a server ever said entitled with other_device, it is still no opinion.
        assertEquals(localFree, CloudEntitlement.apply(localFree, verdict("other_device", true), 5L))
        val moved = CloudEntitlement.apply(localActive, verdict("transferred", true), 5L)
        assertFalse(moved.active)
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(moved))
        for ((state, entitled, expected) in listOf(
            Triple("active", true, SubscriptionState.ACTIVE),
            Triple("canceled", true, SubscriptionState.ACTIVE),
            Triple("grace_period", true, SubscriptionState.ACTIVE),
            Triple("on_hold", false, SubscriptionState.EXPIRED),
            Triple("paused", false, SubscriptionState.EXPIRED),
            Triple("expired", false, SubscriptionState.EXPIRED),
            Triple("revoked", false, SubscriptionState.EXPIRED),
            Triple("unknown", false, SubscriptionState.EXPIRED),
        )) {
            assertEquals(state, expected, SubscriptionState.of(CloudEntitlement.apply(localActive, verdict(state, entitled), 5L)))
        }
        assertEquals(SubscriptionState.PENDING, SubscriptionState.of(CloudEntitlement.apply(localFree, verdict("pending", false), 5L)))
        // purchaseTokenHash and source are read past.
        val parsed = CloudJson.entitlement(
            parseJson("""{"state":"other_device","entitled":false,"expiryTime":null,"autoRenewing":false,"productId":"do_unlimited","lastCheckedAt":1,"source":"verify","purchaseTokenHash":"abc"}"""),
        )
        assertTrue(parsed.noOpinion)
    }

    // verifyPurchase carries this installation's id; other_device leaves a real buyer on Do Unlimited.
    @Test
    fun otherDeviceNeverSwitchesARealBuyerOff() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.entitlement.record = localActive
        cx.port.verdict = VerifiedEntitlement("other_device", false, null, false, "do_unlimited", 9L)
        assertEquals(VerifyOutcome.NO_OPINION, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg))
        assertEquals(CloudEntitlement.obfuscatedAccountId("uid-1"), cx.port.verifyCalls.single().obfuscatedAccountId)
        assertEquals(localActive, cx.entitlement.record)
        assertEquals(0, cx.entitlement.changes)
        assertNull(cx.store.serverEntitlement())
        // Asked again within the hour: no call, and still the local result.
        cx.fx.clockMillis += 60_000L
        assertEquals(VerifyOutcome.KEPT_LOCAL, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg))
        assertEquals(1, cx.port.verifyCalls.size)
        assertTrue(cx.entitlement.record.active)
    }

    // An earlier verdict is dropped once the server says other_device, so it cannot be reapplied later.
    @Test
    fun otherDeviceClearsAnEarlierVerdict() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.port.verdict = VerifiedEntitlement("expired", false, null, false, "do_unlimited", 1L)
        cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = true, packageName = pkg)
        assertFalse(cx.entitlement.record.active)
        cx.entitlement.record = localActive
        cx.port.verdict = VerifiedEntitlement("other_device", false, null, false, "do_unlimited", 2L)
        assertEquals(VerifyOutcome.NO_OPINION, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = true, packageName = pkg))
        cx.fx.clockMillis += 60_000L
        assertEquals(VerifyOutcome.KEPT_LOCAL, cx.cloud.onPurchase("do_unlimited", "tok-1", justBought = false, packageName = pkg))
        assertTrue(cx.entitlement.record.active)
    }

    // Before a purchase the phone signs in and hands Play the id; cloud off hands nothing.
    @Test
    fun aPurchaseStartsSignedInWithTheAccountId() = runBlocking {
        val cx = CloudFixture()
        assertEquals(CloudEntitlement.obfuscatedAccountId("uid-1"), cx.cloud.purchaseAccountId())
        assertEquals("uid-1", cx.store.identityUid())
        assertEquals(1, cx.port.calls.count { it == "signIn" })
        val off = CloudFixture(config = CloudConfig.OFF)
        assertNull(off.cloud.purchaseAccountId())
        assertEquals(0, off.portBuilt)
    }

    // The release flow sets the id on BillingFlowParams, and every buy goes through startPurchase.
    @Test
    fun releaseBillingPassesTheAccountIdToPlay() {
        val play = projectFile("src/release/java/com/evansappstudio/doapp/billing/PlaySubscriptionBilling.kt").readText()
        val buy = play.substringAfter("override fun buy(").substringBefore("override fun restore(")
        assertTrue(buy.contains("builder.setObfuscatedAccountId(obfuscatedAccountId)"))
        assertTrue(buy.indexOf("setObfuscatedAccountId") < buy.indexOf("launchBillingFlow"))
        val fake = projectFile("src/debug/java/com/evansappstudio/doapp/billing/FakeSubscriptionBilling.kt").readText()
        assertTrue(fake.contains("lastAccountId = obfuscatedAccountId"))
        val callers = listOf("src/main/java", "src/debug/java", "src/release/java").map(::projectFile).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { it.readText().contains("billing.buy(") }.map { it.name }.toList()
        }
        assertEquals(listOf("AppContainer.kt"), callers)
        val container = projectFile("src/main/java/com/evansappstudio/doapp/AppContainer.kt").readText()
        assertTrue(container.contains("cloud?.purchaseAccountId()"))
        assertTrue(container.contains("billing.buy(activity, accountId)"))
    }
}
