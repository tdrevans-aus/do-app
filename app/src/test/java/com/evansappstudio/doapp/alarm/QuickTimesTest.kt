package com.evansappstudio.doapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class QuickTimesTest {
    private val perth: ZoneId = ZoneId.of("Australia/Perth")
    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")

    @Test
    fun factorySlotsMatchDuesScreenshot() {
        assertEquals(QuickTimes.SLOT_COUNT, QuickTimes.FACTORY.size)
        assertEquals(PostponeMode.AUTO, QuickTimes.DEFAULT_POSTPONE)
        assertEquals(
            listOf(
                "9:30 am",
                "12:00 pm",
                "6:30 pm",
                "10:00 pm",
                "+10 min",
                "+1 hr",
                "+3 hr",
                "+1 day",
                "-10 min",
                "-1 hr",
                "-3 hr",
                "-1 day",
            ),
            QuickTimes.FACTORY.map { QuickTimes.label(it, is24Hour = false, Locale.US) },
        )
        assertEquals(SnoozeKind.AT_TIME, QuickTimes.FACTORY[0].kind)
        assertEquals(570, QuickTimes.FACTORY[0].amount)
        assertEquals(SnoozeKind.FROM_NOW, QuickTimes.FACTORY[4].kind)
        assertEquals(10, QuickTimes.FACTORY[4].amount)
        assertEquals(QuickTimeUnit.MINUTE, QuickTimes.FACTORY[4].unit)
        assertEquals(-1, QuickTimes.FACTORY[11].amount)
        assertEquals(QuickTimeUnit.DAY, QuickTimes.FACTORY[11].unit)
    }

    @Test
    fun labelsFollow12And24HourAndLeaveSnoozeWordingAlone() {
        assertEquals(
            listOf("09:30", "12:00", "18:30", "22:00"),
            QuickTimes.FACTORY.take(4).map { QuickTimes.label(it, is24Hour = true, Locale.US) },
        )
        assertEquals("+2 days", label(adjust(2, QuickTimeUnit.DAY), is24Hour = true))
        assertEquals("+2 weeks", label(adjust(2, QuickTimeUnit.WEEK), is24Hour = false))
        assertEquals("+5 min", label(adjust(5, QuickTimeUnit.MINUTE), is24Hour = true))
        assertEquals("+2 hr", label(adjust(2, QuickTimeUnit.HOUR), is24Hour = false))
        assertEquals("+1 week", label(adjust(1, QuickTimeUnit.WEEK), is24Hour = true))
        assertEquals("-2 days", label(adjust(-2, QuickTimeUnit.DAY), is24Hour = true))
        assertEquals(
            QuickTimes.label(QuickTimes.FACTORY[4], is24Hour = false, Locale.US),
            QuickTimes.label(QuickTimes.FACTORY[4], is24Hour = true, Locale.FRANCE),
        )
        assertEquals("+1 hour", SnoozeLabels.fromNow(60))
        assertEquals("+3 hours", SnoozeLabels.fromNow(180))
        assertEquals("9:00 am", SnoozeLabels.atTime(9 * 60))
    }

    @Test
    fun atTimeUsesTheDueDateThenRollsForward() {
        val tomorrowMorning = at(2026, 11, 26, 8, 0)
        val todayMorning = at(2026, 11, 25, 8, 0)
        val before = at(2026, 11, 25, 8, 15)
        val after = at(2026, 11, 25, 10, 0)
        val exactly = at(2026, 11, 25, 9, 30)
        val slot = QuickTimes.FACTORY[0]

        assertEquals(at(2026, 11, 26, 9, 30), apply(slot, tomorrowMorning, before))
        assertEquals(at(2026, 11, 25, 9, 30), apply(slot, todayMorning, before))
        assertEquals(at(2026, 11, 26, 9, 30), apply(slot, todayMorning, after))
        assertEquals(at(2026, 11, 26, 9, 30), apply(slot, todayMorning, exactly))

        val yesterday = at(2026, 11, 24, 8, 0)
        assertEquals(at(2026, 11, 25, 9, 30), apply(slot, yesterday, before))
    }

    @Test
    fun jumpThenNudgeKeepsTheSameDate() {
        val due = at(2026, 11, 25, 8, 0)
        val now = at(2026, 11, 25, 8, 0)
        val atSixThirty = apply(QuickTimes.FACTORY[2], due, now)
        assertEquals(at(2026, 11, 25, 18, 30), atSixThirty)
        assertEquals(at(2026, 11, 25, 19, 30), apply(QuickTimes.FACTORY[5], atSixThirty, now))
    }

    @Test
    fun stackingAddsOnTheDraft() {
        val due = at(2026, 11, 25, 8, 0)
        val now = at(2026, 11, 25, 7, 0)
        val once = apply(QuickTimes.FACTORY[4], due, now)
        val twice = apply(QuickTimes.FACTORY[4], once, now)
        val counted = apply(QuickTimes.FACTORY[4], due, now, count = 2)
        assertEquals(due + 20 * 60_000L, counted)
        assertEquals(twice, counted)
    }

    @Test
    fun postponeAutoMatchesThe230Example() {
        val due = at(2026, 11, 25, 14, 30)
        val now = at(2026, 11, 25, 14, 43)
        assertEquals(at(2026, 11, 25, 14, 53), apply(QuickTimes.FACTORY[4], due, now, PostponeMode.AUTO))
        assertEquals(at(2026, 11, 25, 15, 30), apply(QuickTimes.FACTORY[5], due, now, PostponeMode.AUTO))
        val stacked = apply(QuickTimes.FACTORY[4], due, now, PostponeMode.AUTO, count = 2)
        assertEquals(at(2026, 11, 25, 15, 3), stacked)
    }

    @Test
    fun postponeFromDueAndFromNow() {
        val due = at(2026, 11, 25, 14, 30)
        val now = at(2026, 11, 25, 14, 43)
        assertEquals(at(2026, 11, 25, 14, 40), apply(QuickTimes.FACTORY[4], due, now, PostponeMode.FROM_DUE))
        assertEquals(at(2026, 11, 25, 15, 30), apply(QuickTimes.FACTORY[5], due, now, PostponeMode.FROM_DUE))
        assertEquals(at(2026, 11, 25, 14, 53), apply(QuickTimes.FACTORY[4], due, now, PostponeMode.FROM_NOW))
        assertEquals(at(2026, 11, 25, 15, 43), apply(QuickTimes.FACTORY[5], due, now, PostponeMode.FROM_NOW))
        assertTrue(apply(QuickTimes.FACTORY[4], due, now, PostponeMode.FROM_DUE) < now)
    }

    @Test
    fun adjustmentsCountFromTheDueDateWhenNotOverdue() {
        val due = at(2026, 11, 25, 16, 0)
        val now = at(2026, 11, 25, 14, 43)
        for (mode in PostponeMode.entries) {
            assertEquals(at(2026, 11, 25, 16, 10), apply(QuickTimes.FACTORY[4], due, now, mode))
            assertEquals(at(2026, 11, 25, 17, 0), apply(QuickTimes.FACTORY[5], due, now, mode))
        }
    }

    @Test
    fun customAtTimeAndNegativeMinutes() {
        val eight = QuickTimeSlot(SnoozeKind.AT_TIME, 8 * 60, QuickTimeUnit.HOUR)
        val tomorrow = at(2026, 11, 26, 9, 0)
        val before = at(2026, 11, 25, 7, 0)
        assertEquals(at(2026, 11, 26, 8, 0), apply(eight, tomorrow, before))
        val afterEight = at(2026, 11, 25, 9, 0)
        val today = at(2026, 11, 25, 12, 0)
        assertEquals(at(2026, 11, 26, 8, 0), apply(eight, today, afterEight))

        val minusHalfHour = adjust(-30, QuickTimeUnit.MINUTE)
        val due = at(2026, 11, 25, 16, 0)
        assertEquals(due - 30 * 60_000L, apply(minusHalfHour, due, before))
    }

    @Test
    fun dayAndWeekKeepWallTimeAcrossDst() {
        val springAfternoon = zoned(sydney, 2026, 10, 3, 15, 0)
        val plusDay = apply(adjust(1, QuickTimeUnit.DAY), springAfternoon.millis(), springAfternoon.millis(), zone = sydney)
        assertWall(sydney, plusDay, 2026, 10, 4, 15, 0)
        assertNotEquals(springAfternoon.millis() + DAY_MILLIS, plusDay)

        val plusWeek = apply(adjust(1, QuickTimeUnit.WEEK), springAfternoon.millis(), springAfternoon.millis(), zone = sydney)
        assertWall(sydney, plusWeek, 2026, 10, 10, 15, 0)
        assertNotEquals(springAfternoon.millis() + 7 * DAY_MILLIS, plusWeek)

        val fallAfternoon = zoned(sydney, 2026, 4, 4, 15, 0)
        val acrossFall = apply(adjust(1, QuickTimeUnit.WEEK), fallAfternoon.millis(), fallAfternoon.millis(), zone = sydney)
        assertWall(sydney, acrossFall, 2026, 4, 11, 15, 0)
        assertNotEquals(fallAfternoon.millis() + 7 * DAY_MILLIS, acrossFall)

        val intoGap = zoned(sydney, 2026, 10, 3, 2, 30)
        val gapResult = apply(adjust(1, QuickTimeUnit.DAY), intoGap.millis(), intoGap.millis(), zone = sydney)
        val resolvedGap = ZonedDateTime.of(LocalDateTime.of(2026, 10, 4, 2, 30), sydney)
        assertTrue(sydney.rules.getValidOffsets(LocalDateTime.of(2026, 10, 4, 2, 30)).isEmpty())
        assertEquals(resolvedGap.toInstant().toEpochMilli(), gapResult)
        assertEquals(LocalTime.of(3, 30), Instant.ofEpochMilli(gapResult).atZone(sydney).toLocalTime())

        val intoOverlap = zoned(sydney, 2026, 4, 4, 2, 30)
        val overlapResult = apply(adjust(1, QuickTimeUnit.DAY), intoOverlap.millis(), intoOverlap.millis(), zone = sydney)
        val overlapLocal = LocalDateTime.of(2026, 4, 5, 2, 30)
        val earlier = sydney.rules.getValidOffsets(overlapLocal).first()
        assertEquals(2, sydney.rules.getValidOffsets(overlapLocal).size)
        assertEquals(earlier, Instant.ofEpochMilli(overlapResult).atZone(sydney).offset)
        assertEquals(LocalTime.of(2, 30), Instant.ofEpochMilli(overlapResult).atZone(sydney).toLocalTime())
    }

    @Test
    fun hourAndMinuteAreElapsedDurations() {
        val due = at(2026, 11, 25, 14, 30)
        assertEquals(due + 10 * 60_000L, apply(QuickTimes.FACTORY[4], due, at(2026, 11, 25, 8, 0)))
        assertEquals(due + 3_600_000L, apply(QuickTimes.FACTORY[5], due, at(2026, 11, 25, 8, 0)))
        assertEquals(due - 10 * 60_000L, apply(QuickTimes.FACTORY[8], due, at(2026, 11, 25, 8, 0)))

        val acrossGap = zoned(sydney, 2026, 10, 4, 1, 30)
        assertEquals(
            acrossGap.millis() + 3_600_000L,
            apply(adjust(1, QuickTimeUnit.HOUR), acrossGap.millis(), acrossGap.millis(), zone = sydney),
        )
        val acrossGapMinutes = zoned(sydney, 2026, 10, 4, 1, 55)
        assertEquals(
            acrossGapMinutes.millis() + 10 * 60_000L,
            apply(adjust(10, QuickTimeUnit.MINUTE), acrossGapMinutes.millis(), acrossGapMinutes.millis(), zone = sydney),
        )
    }

    @Test
    fun atTimeGapUsesNextValidLocalTimeAndOverlapUsesTheEarlierOffset() {
        val gapDue = zoned(sydney, 2026, 10, 4, 1, 0)
        val gapSlot = QuickTimeSlot(SnoozeKind.AT_TIME, 2 * 60 + 30, QuickTimeUnit.MINUTE)
        val gapExpected = ZonedDateTime.of(LocalDateTime.of(2026, 10, 4, 2, 30), sydney)
        val gapResult = apply(gapSlot, gapDue.millis(), gapDue.millis(), zone = sydney)
        assertEquals(gapExpected.toInstant().toEpochMilli(), gapResult)
        assertEquals(LocalTime.of(3, 30), Instant.ofEpochMilli(gapResult).atZone(sydney).toLocalTime())
        assertEquals(ZoneOffset.ofHours(11), Instant.ofEpochMilli(gapResult).atZone(sydney).offset)

        val overlapDue = zoned(sydney, 2026, 4, 5, 1, 0)
        val overlapLocal = LocalDateTime.of(2026, 4, 5, 2, 30)
        val earlier = sydney.rules.getValidOffsets(overlapLocal)
        assertEquals(2, earlier.size)
        val overlapExpected = ZonedDateTime.of(overlapLocal, sydney)
        assertEquals(earlier.first(), overlapExpected.offset)
        val overlapResult = apply(gapSlot, overlapDue.millis(), overlapDue.millis(), zone = sydney)
        assertEquals(overlapExpected.toInstant().toEpochMilli(), overlapResult)
        assertEquals(earlier.first(), Instant.ofEpochMilli(overlapResult).atZone(sydney).offset)
    }

    @Test
    fun minusIntoThePast() {
        val now = at(2026, 11, 25, 12, 0)
        val due = now + 5 * 60_000L
        for (mode in PostponeMode.entries) {
            val result = apply(QuickTimes.FACTORY[9], due, now, mode)
            assertEquals(due - 60 * 60_000L, result)
            assertTrue(result < now)
        }
    }

    @Test
    fun longPressOverridesDoNotChangeThePostponeRule() {
        val due = at(2026, 11, 25, 14, 30)
        val now = at(2026, 11, 25, 14, 43)
        assertEquals(
            at(2026, 11, 25, 14, 40),
            apply(QuickTimes.FACTORY[4], due, now, PostponeMode.AUTO, base = QuickTimeBase.DUE),
        )
        assertEquals(
            at(2026, 11, 25, 14, 53),
            apply(QuickTimes.FACTORY[4], due, now, PostponeMode.FROM_DUE, base = QuickTimeBase.NOW),
        )
        assertEquals(at(2026, 11, 25, 14, 53), apply(QuickTimes.FACTORY[4], due, now, PostponeMode.AUTO))

        val future = at(2026, 11, 25, 16, 0)
        assertEquals(
            at(2026, 11, 25, 14, 53),
            apply(QuickTimes.FACTORY[4], future, now, PostponeMode.AUTO, base = QuickTimeBase.NOW),
        )
        assertEquals(
            at(2026, 11, 25, 16, 10),
            apply(QuickTimes.FACTORY[4], future, now, PostponeMode.FROM_NOW, base = QuickTimeBase.DUE),
        )

        val morning = at(2026, 11, 25, 8, 0)
        val early = at(2026, 11, 25, 8, 0)
        val slot = QuickTimes.FACTORY[0]
        assertEquals(at(2026, 11, 25, 9, 30), apply(slot, morning, early))
        assertEquals(
            at(2026, 11, 26, 9, 30),
            apply(slot, morning, early, day = QuickTimeDay.TOMORROW),
        )
        val passed = at(2026, 11, 25, 10, 0)
        assertEquals(at(2026, 11, 26, 9, 30), apply(slot, morning, passed))
        assertEquals(at(2026, 11, 25, 9, 30), apply(slot, morning, passed, day = QuickTimeDay.TODAY))
        assertEquals(at(2026, 11, 25, 9, 30), apply(slot, morning, passed, day = QuickTimeDay.DUE))
        assertTrue(apply(slot, morning, passed, day = QuickTimeDay.TODAY) <= passed)
    }

    @Test
    fun amountZeroIsRejectedForFromNowAndMidnightIsAValidAtTime() {
        assertNull(QuickTimeSlot.orNull(SnoozeKind.FROM_NOW, 0, QuickTimeUnit.MINUTE))
        assertNull(QuickTimeSlot.orNull(SnoozeKind.FROM_NOW, 0, QuickTimeUnit.DAY))
        assertNull(QuickTimeSlot.orNull(SnoozeKind.AT_TIME, 24 * 60, QuickTimeUnit.MINUTE))
        assertNull(QuickTimeSlot.orNull(SnoozeKind.AT_TIME, -1, QuickTimeUnit.MINUTE))
        val midnight = QuickTimeSlot.orNull(SnoozeKind.AT_TIME, 0, QuickTimeUnit.WEEK)
        assertEquals(0, midnight?.amount)
        assertEquals("12:00 am", QuickTimes.label(midnight!!, is24Hour = false, Locale.US))
        assertEquals("00:00", QuickTimes.label(midnight, is24Hour = true, Locale.US))
    }

    @Test
    fun bannerAndDumpLines() {
        val due = at(2026, 11, 25, 18, 30)
        val now = due - 18 * 3_600_000L
        assertEquals(
            "Wed 25 Nov 2026, 6:30 pm (in 18 hours)",
            QuickTimes.bannerText(due, now, perth, is24Hour = false, Locale.US),
        )
        assertEquals(
            "Wed 25 Nov 2026, 18:30 (in 18 hours)",
            QuickTimes.bannerText(due, now, perth, is24Hour = true, Locale.US),
        )
        assertEquals("1 hour ago", QuickTimes.relativePhrase(due, due + 3_600_000L))
        assertEquals("now", QuickTimes.relativePhrase(due, due))
        val line = QuickTimes.dumpLine(
            PostponeMode.AUTO,
            0,
            QuickTimes.FACTORY[0],
            is24Hour = false,
            Locale.US,
        )
        assertEquals(
            "DoAlarm qat-config postpone=AUTO slot=0 kind=AT_TIME amount=570 unit=MINUTE label=9:30 am",
            line,
        )
        assertEquals(
            "DoAlarm qat-config postpone=FROM_NOW slot=4 kind=FROM_NOW amount=10 unit=MINUTE label=+10 min",
            QuickTimes.dumpLine(PostponeMode.FROM_NOW, 4, QuickTimes.FACTORY[4], true, Locale.US),
        )
    }

    private fun apply(
        slot: QuickTimeSlot,
        due: Long,
        now: Long,
        postpone: PostponeMode = PostponeMode.AUTO,
        zone: ZoneId = perth,
        count: Int = 1,
        base: QuickTimeBase? = null,
        day: QuickTimeDay? = null,
    ): Long = QuickTimes.applyRepeated(slot, due, now, zone, postpone, count, base, day)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return zoned(perth, year, month, day, hour, minute).millis()
    }

    private fun zoned(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
    }

    private fun ZonedDateTime.millis(): Long = toInstant().toEpochMilli()

    private fun assertWall(zone: ZoneId, millis: Long, year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        val zoned = Instant.ofEpochMilli(millis).atZone(zone)
        assertEquals(LocalDate.of(year, month, day), zoned.toLocalDate())
        assertEquals(LocalTime.of(hour, minute), zoned.toLocalTime())
    }

    private fun adjust(amount: Int, unit: QuickTimeUnit): QuickTimeSlot {
        return QuickTimeSlot(SnoozeKind.FROM_NOW, amount, unit)
    }

    private fun label(slot: QuickTimeSlot, is24Hour: Boolean): String {
        return QuickTimes.label(slot, is24Hour, Locale.US)
    }

    private companion object {
        const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
