package com.evansappstudio.doapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NagScheduleTest {
    private val dueAt = 1_700_000_000_000L

    @Test
    fun anchorIsDueAtPlusKIntervalsWithoutDrift() {
        val interval = NagSchedule.MINUTE_MILLIS
        for (k in 0L..3L) {
            assertEquals(dueAt + k * interval, NagSchedule.anchorAt(dueAt, nagMinutes = 1, k))
        }
        assertEquals(dueAt + 180L * 60L * NagSchedule.MINUTE_MILLIS, NagSchedule.anchorAt(dueAt, 60, 180))
    }

    @Test
    fun onTimeAndLateFiresStayOnTheAnchor() {
        assertEquals(dueAt + 60_000L, NagSchedule.afterDelivery(dueAt, 1, dueAt))
        assertEquals(dueAt + 60_000L, NagSchedule.afterDelivery(dueAt, 1, dueAt + 2_000L))
        assertEquals(dueAt + 120_000L, NagSchedule.afterDelivery(dueAt, 1, dueAt + 60_000L))
        assertEquals(
            dueAt + 120_000L,
            NagSchedule.afterDelivery(dueAt, 1, dueAt + 60_000L + 2_000L),
        )
    }

    @Test
    fun missedFireIsNowThenCadenceResumesFromTheAnchor() {
        val now = dueAt + 10 * NagSchedule.MINUTE_MILLIS + 5_000L
        assertEquals(now, NagSchedule.catchUp(dueAt, now))
        assertEquals(
            now,
            ScheduleResolver.resolve(dueAt, now, hasMirror = true, mirroredNext = dueAt),
        )
        assertEquals(dueAt + 11 * NagSchedule.MINUTE_MILLIS, NagSchedule.afterDelivery(dueAt, 1, now))
    }

    @Test
    fun nagOffAlertsOnceThenStops() {
        val before = dueAt - 30_000L
        assertEquals(dueAt, NagSchedule.catchUp(dueAt, before))
        assertNull(NagSchedule.afterDelivery(dueAt, nagMinutes = null, now = dueAt))
        assertNull(NagSchedule.afterDelivery(dueAt, nagMinutes = null, now = dueAt + 60_000L))

        val missed = dueAt + 15_000L
        assertEquals(missed, NagSchedule.catchUp(dueAt, missed))
        assertNull(NagSchedule.afterDelivery(dueAt, null, missed))
        assertNull(
            ScheduleResolver.resolve(
                dueAt = dueAt,
                now = missed + 60_000L,
                hasMirror = true,
                mirroredNext = null,
            ),
        )
    }

    @Test
    fun eachIntervalSecondFireIsDuePlusThatInterval() {
        for (minutes in listOf(5, 10, 15, 30, 60)) {
            val second = NagSchedule.afterDelivery(dueAt, minutes, dueAt)
            assertEquals(dueAt + minutes * NagSchedule.MINUTE_MILLIS, second)
            assertEquals(NagSchedule.anchorAt(dueAt, minutes, k = 1), second)
        }
    }

    @Test
    fun rescheduleKeepsAFutureFireAndCatchesUpAMissedOne() {
        val futureNow = dueAt - 5_000L
        assertEquals(
            dueAt,
            ScheduleResolver.resolve(dueAt, futureNow, hasMirror = true, mirroredNext = dueAt),
        )
        assertEquals(
            dueAt,
            ScheduleResolver.resolve(dueAt, futureNow, hasMirror = false, mirroredNext = null),
        )
        val pastNow = dueAt + 5_000L
        assertEquals(
            pastNow,
            ScheduleResolver.resolve(dueAt, pastNow, hasMirror = true, mirroredNext = dueAt),
        )
    }

    @Test
    fun normalizeTreatsZeroAndUnknownAsOff() {
        assertNull(NagSchedule.normalize(null))
        assertNull(NagSchedule.normalize(0))
        assertNull(NagSchedule.normalize(2))
        assertEquals(5, NagSchedule.normalize(5))
        assertEquals(1, NagSchedule.normalize(1))
    }
}
