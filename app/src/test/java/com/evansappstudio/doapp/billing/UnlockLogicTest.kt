package com.evansappstudio.doapp.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockLogicTest {
    @Test
    fun theFreeCapIsOneConstantAndFiveIsTheDefault() {
        assertEquals(5, FreeTier.ACTIVE_REMINDER_CAP)
        assertEquals("do_unlock", FreeTier.PRODUCT_ID)
        assertFalse(FreeTier.blocksAdd(activeCount = 4, unlocked = false))
        assertTrue(FreeTier.blocksAdd(activeCount = 5, unlocked = false))
        assertFalse(FreeTier.blocksAdd(activeCount = 4, unlocked = false))
        assertFalse(FreeTier.blocksAdd(activeCount = 100, unlocked = true))
        assertFalse(FreeTier.blocksAdd(activeCount = 5, unlocked = false, cap = null))
        assertFalse(FreeTier.blocksAdd(activeCount = 5, unlocked = false, cap = 0))
        assertEquals("5", FreeTier.capLabel())
        assertEquals("none", FreeTier.capLabel(null))
        assertEquals("none", FreeTier.capLabel(0))
    }

    @Test
    fun doneRemindersFreeASlotAndImportsAboveTheCapStillBlockAdds() {
        val active = 5
        assertTrue(FreeTier.blocksAdd(active, unlocked = false))
        val afterOneDone = active - 1
        assertFalse(FreeTier.blocksAdd(afterOneDone, unlocked = false))
        val importedActive = 8
        assertTrue(FreeTier.blocksAdd(importedActive, unlocked = false))
        assertFalse(FreeTier.blocksAdd(importedActive, unlocked = true))
    }

    @Test
    fun acknowledgementIsOnlyForPurchasedUnacknowledgedTokens() {
        val secret = "purchase-token-secret"
        val purchased = view(PurchaseViewState.PURCHASED, acknowledged = false, token = secret)
        val granted = apply(EntitlementRecord.EMPTY, succeeded = true, listOf(purchased))
        assertTrue(granted.record.unlocked)
        assertFalse(granted.record.pending)
        assertEquals(listOf(secret), granted.acknowledgeTokens)
        assertTrue(granted.justGranted)
        assertFalse(granted.record.toString().contains(secret))

        val already = apply(EntitlementRecord.EMPTY, succeeded = true, listOf(purchased.copy(acknowledged = true)))
        assertTrue(already.acknowledgeTokens.isEmpty())

        val pending = view(PurchaseViewState.PENDING, acknowledged = false, token = "pending-token")
        val waiting = apply(EntitlementRecord.EMPTY, succeeded = true, listOf(pending))
        assertFalse(waiting.record.unlocked)
        assertTrue(waiting.record.pending)
        assertTrue(waiting.acknowledgeTokens.isEmpty())
        assertFalse(waiting.justGranted)

        val both = apply(EntitlementRecord.EMPTY, succeeded = true, listOf(pending, purchased))
        assertTrue(both.record.unlocked)
        assertFalse(both.record.pending)
        assertEquals(listOf(secret), both.acknowledgeTokens)
    }

    @Test
    fun aSuccessfulQueryWithoutTheProductRevokesAndAFailedQueryKeepsTheCache() {
        val unlocked = EntitlementRecord(
            unlocked = true,
            pending = false,
            lastQueryAt = 5L,
            grantSource = EntitlementPolicy.SOURCE_PLAY,
            lastQueryOk = true,
        )
        val revoked = apply(unlocked, succeeded = true, purchases = emptyList(), now = 9L)
        assertFalse(revoked.record.unlocked)
        assertFalse(revoked.record.pending)
        assertEquals(9L, revoked.record.lastQueryAt)
        assertTrue(revoked.acknowledgeTokens.isEmpty())
        assertTrue(revoked.reachedStore)

        val other = view(PurchaseViewState.PURCHASED, acknowledged = false, token = "other-token", product = "other")
        val stillRevoked = apply(unlocked, succeeded = true, listOf(other))
        assertFalse(stillRevoked.record.unlocked)
        assertTrue(stillRevoked.acknowledgeTokens.isEmpty())

        val offline = apply(unlocked, succeeded = false, purchases = emptyList(), now = 50L)
        assertEquals(unlocked, offline.record)
        assertTrue(offline.acknowledgeTokens.isEmpty())
        assertFalse(offline.reachedStore)
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
