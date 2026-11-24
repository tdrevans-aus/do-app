package com.evansappstudio.doapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SnoozeDueTest {
    private val utc: ZoneId = ZoneId.of("UTC")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    @Test
    fun fromNowAddsTheDuration() {
        val now = 1_700_000_000_000L
        assertEquals(now + 60 * 60_000L, due(SnoozeKind.FROM_NOW, 60, now, utc))
        assertEquals(now + 3 * 60 * 60_000L, due(SnoozeKind.FROM_NOW, 180, now, utc))
        assertEquals(now + 15 * 60_000L, due(SnoozeKind.FROM_NOW, 15, now, utc))
    }

    @Test
    fun fromNowOneDayIsTwentyFourHoursNotTheSameClockTime() {
        val now = ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, newYork).toInstant().toEpochMilli()
        val due = due(SnoozeKind.FROM_NOW, 24 * 60, now, newYork)
        assertEquals(now + 24L * 60L * 60L * 1000L, due)
        val sameClockTomorrow = ZonedDateTime.of(2026, 3, 9, 1, 30, 0, 0, newYork)
            .toInstant()
            .toEpochMilli()
        assertNotEquals(sameClockTomorrow, due)
        assertEquals(due, due(SnoozeKind.FROM_NOW, 24 * 60, now, utc))
    }

    @Test
    fun atTimeUsesTodayWhenThatClockTimeIsStillAhead() {
        val now = ZonedDateTime.of(2026, 11, 24, 8, 0, 0, 0, utc).toInstant().toEpochMilli()
        val due = due(SnoozeKind.AT_TIME, 9 * 60, now, utc)
        val today = ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(today, due)
    }

    @Test
    fun atTimeJumpsTwentyFourHoursWhenTodayHasPassed() {
        val now = ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, utc).toInstant().toEpochMilli()
        val due = due(SnoozeKind.AT_TIME, 9 * 60, now, utc)
        val today = ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(today + 24L * 60L * 60L * 1000L, due)

        val duringSpringForward = ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, newYork)
            .toInstant()
            .toEpochMilli()
        val todayOne = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, newYork).toInstant().toEpochMilli()
        val jumped = due(SnoozeKind.AT_TIME, 60, duringSpringForward, newYork)
        assertEquals(todayOne + 24L * 60L * 60L * 1000L, jumped)
        val sameClockTomorrow = ZonedDateTime.of(2026, 3, 9, 1, 0, 0, 0, newYork)
            .toInstant()
            .toEpochMilli()
        assertNotEquals(sameClockTomorrow, jumped)
    }

    @Test
    fun pastTargetIsAMissedFireThenNagStaysOnThatAnchor() {
        val target = 1_700_000_000_000L
        val now = target + 90_000L
        assertEquals(now, SnoozeDue.nextFire(target, now))
        assertEquals(target + 2 * 60_000L, NagSchedule.afterDelivery(target, 1, now))
        assertEquals(target + 3 * 60_000L, NagSchedule.afterDelivery(target, 1, now + 60_000L))
    }

    @Test
    fun nagAfterSnoozeIsAnchoredAtTheNewDue() {
        val oldDue = 1_700_000_000_000L
        val now = oldDue + 7 * 60_000L
        val newDue = due(SnoozeKind.FROM_NOW, 60, now, utc)
        assertEquals(now + 60 * 60_000L, newDue)
        assertEquals(newDue, SnoozeDue.nextFire(newDue, now))
        assertEquals(newDue + 5 * 60_000L, NagSchedule.afterDelivery(newDue, 5, newDue))
        assertEquals(newDue + 10 * 60_000L, NagSchedule.afterDelivery(newDue, 5, newDue + 5 * 60_000L))
        assertEquals(newDue + 15 * 60_000L, NagSchedule.afterDelivery(newDue, 5, newDue + 10 * 60_000L))
        assertNotEquals(newDue + 5 * 60_000L, NagSchedule.afterDelivery(oldDue, 5, newDue))
    }

    @Test
    fun labelsMatchTheOneTapWording() {
        assertEquals("+1 minute", SnoozeLabels.fromNow(1))
        assertEquals("+15 minutes", SnoozeLabels.fromNow(15))
        assertEquals("+1 hour", SnoozeLabels.fromNow(60))
        assertEquals("+3 hours", SnoozeLabels.fromNow(180))
        assertEquals("+2 hours", SnoozeLabels.fromNow(120))
        assertEquals("+1 day", SnoozeLabels.fromNow(24 * 60))
        assertEquals("+2 days", SnoozeLabels.fromNow(2 * 24 * 60))
        assertEquals("9:00 am", SnoozeLabels.atTime(9 * 60))
        assertEquals("12:00 am", SnoozeLabels.atTime(0))
        assertEquals("3:30 pm", SnoozeLabels.atTime(15 * 60 + 30))
    }

    @Test
    fun snoozeRequestCodesAreDistinctPerReminderAndOption() {
        val alarm = ReminderIds.asInt(7L)
        val codes = (0..2).map { ReminderIds.snoozeRequestCode(7L, it) }
        assertEquals(3, codes.toSet().size)
        assertTrue(alarm !in codes)
        assertTrue(ReminderIds.SNOOZE_ALL_REQUEST_CODE !in codes)
        assertNotEquals(
            ReminderIds.snoozeRequestCode(7L, 0),
            ReminderIds.snoozeRequestCode(8L, 0),
        )
    }

    private fun due(kind: SnoozeKind, value: Int, now: Long, zone: ZoneId): Long {
        return SnoozeDue.compute(SnoozeOption(kind, value), now, zone)
    }
}
