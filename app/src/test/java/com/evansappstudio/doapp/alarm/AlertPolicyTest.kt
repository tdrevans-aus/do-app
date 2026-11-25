package com.evansappstudio.doapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    @Test
    fun standardNeverFallsBack() {
        val decision = AlertPolicy.decide(
            requested = AlertLevel.STANDARD,
            canUseFullScreenIntent = false,
            hasDndAccess = false,
        )
        assertEquals(AlertLevel.STANDARD, decision.effective)
        assertTrue(decision.reasons.isEmpty())
        assertFalse(decision.fullScreen)
        assertFalse(decision.bypassDnd)
        assertFalse(decision.loopUntilAttended)
    }

    @Test
    fun prominentWithoutFullScreenBecomesCritical() {
        val decision = AlertPolicy.decide(
            requested = AlertLevel.PROMINENT,
            canUseFullScreenIntent = false,
            hasDndAccess = true,
        )
        assertEquals(AlertLevel.CRITICAL, decision.effective)
        assertEquals(listOf(AlertFallbackReason.NO_FSI), decision.reasons)
        assertFalse(decision.fullScreen)
        assertTrue(decision.bypassDnd)
        assertTrue(decision.loopUntilAttended)
    }

    @Test
    fun criticalWithoutDndStillPostsOnCritical() {
        val decision = AlertPolicy.decide(
            requested = AlertLevel.CRITICAL,
            canUseFullScreenIntent = true,
            hasDndAccess = false,
        )
        assertEquals(AlertLevel.CRITICAL, decision.effective)
        assertEquals(listOf(AlertFallbackReason.NO_DND), decision.reasons)
        assertFalse(decision.bypassDnd)
        assertFalse(decision.fullScreen)
        assertFalse(decision.loopUntilAttended)
    }

    @Test
    fun prominentMissingBothPermissions() {
        val decision = AlertPolicy.decide(
            requested = AlertLevel.PROMINENT,
            canUseFullScreenIntent = false,
            hasDndAccess = false,
        )
        assertEquals(AlertLevel.CRITICAL, decision.effective)
        assertEquals(
            listOf(AlertFallbackReason.NO_FSI, AlertFallbackReason.NO_DND),
            decision.reasons,
        )
        assertFalse(decision.fullScreen)
        assertFalse(decision.bypassDnd)
        assertTrue(decision.loopUntilAttended)
    }

    @Test
    fun missingCriticalChannelUsesStandard() {
        val decision = AlertPolicy.decide(
            requested = AlertLevel.PROMINENT,
            canUseFullScreenIntent = false,
            hasDndAccess = false,
            criticalChannelAvailable = false,
        )
        assertEquals(AlertLevel.STANDARD, decision.effective)
        assertTrue(decision.reasons.contains(AlertFallbackReason.NO_FSI))
        assertTrue(decision.reasons.contains(AlertFallbackReason.NO_CHANNEL))
        assertFalse(decision.loopUntilAttended)
        assertFalse(decision.fullScreen)
    }

    @Test
    fun prominentWithBothPermissionsStaysProminent() {
        val decision = AlertPolicy.decide(
            requested = AlertLevel.PROMINENT,
            canUseFullScreenIntent = true,
            hasDndAccess = true,
        )
        assertEquals(AlertLevel.PROMINENT, decision.effective)
        assertTrue(decision.reasons.isEmpty())
        assertTrue(decision.fullScreen)
        assertTrue(decision.bypassDnd)
        assertTrue(decision.loopUntilAttended)
    }
}
