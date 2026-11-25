package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.MonthNth
import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.MonthTarget
import com.evansappstudio.doapp.data.RepeatChoice
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatPresets
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class RepeatMathTest {
    private val perth: ZoneId = ZoneId.of("Australia/Perth")
    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    @Test
    fun presetsFollowTheDueAndRejectBadRules() {
        val due = at(perth, 2026, 11, 25, 17, 30)
        assertEquals(RepeatRule.of(1, RepeatUnit.DAY, RepeatFrom.DUE_DATE), RepeatPresets.daily())
        assertEquals(WeekDay.WEEKDAYS, RepeatPresets.weekdays().weekDays)
        assertEquals(listOf(WeekDay.WED), RepeatPresets.weekly(due, perth).weekDays)
        assertEquals(MonthOn.DayOfMonth(25), RepeatPresets.monthly(due, perth).monthOn)
        assertEquals(RepeatUnit.YEAR, RepeatPresets.yearly().unit)
        assertEquals(RepeatChoice.WEEKLY, RepeatPresets.choice(RepeatPresets.weekly(due, perth), due, perth))
        assertEquals(RepeatChoice.CUSTOM, RepeatPresets.choice(weeks(2, WeekDay.WED), due, perth))
        assertNull(RepeatRule.of(0, RepeatUnit.DAY, RepeatFrom.DUE_DATE))
        assertNull(RepeatRule.of(100, RepeatUnit.DAY, RepeatFrom.DUE_DATE))
        assertNull(RepeatRule.of(1, RepeatUnit.WEEK, RepeatFrom.DUE_DATE))
        assertNull(RepeatRule.of(1, RepeatUnit.MONTH, RepeatFrom.DUE_DATE))
        assertNull(
            RepeatRule.of(1, RepeatUnit.DAY, RepeatFrom.DUE_DATE, weekDays = listOf(WeekDay.MON)),
        )
        assertNull(RepeatRule.of(1, RepeatUnit.HOUR, RepeatFrom.DUE_DATE, monthOn = MonthOn.DayOfMonth(1)))
        assertEquals(
            RepeatPresets.monthly(at(perth, 2026, 11, 28, 9, 0), perth),
            RepeatPresets.retargetIfPreset(
                RepeatPresets.monthly(due, perth),
                due,
                at(perth, 2026, 11, 28, 9, 0),
                perth,
            ),
        )
    }

    @Test
    fun dailyWeekdaysWeeklyMonthlyAndYearly() {
        val due = at(perth, 2026, 11, 25, 17, 30)
        assertEquals(
            listOf(
                due,
                at(perth, 2026, 11, 26, 17, 30),
                at(perth, 2026, 11, 27, 17, 30),
                at(perth, 2026, 11, 28, 17, 30),
            ),
            RepeatMath.sequence(due, due, RepeatPresets.daily(), perth, 4),
        )
        assertEquals(
            listOf(
                due,
                at(perth, 2026, 11, 26, 17, 30),
                at(perth, 2026, 11, 27, 17, 30),
                at(perth, 2026, 11, 30, 17, 30),
            ),
            RepeatMath.sequence(due, due, RepeatPresets.weekdays(), perth, 4),
        )
        assertEquals(
            listOf(due, at(perth, 2026, 12, 2, 17, 30), at(perth, 2026, 12, 9, 17, 30)),
            RepeatMath.sequence(due, due, RepeatPresets.weekly(due, perth), perth, 3),
        )
        assertEquals(
            listOf(due, at(perth, 2026, 12, 25, 17, 30), at(perth, 2027, 1, 25, 17, 30)),
            RepeatMath.sequence(due, due, RepeatPresets.monthly(due, perth), perth, 3),
        )
        assertEquals(
            listOf(due, at(perth, 2027, 11, 25, 17, 30), at(perth, 2028, 11, 25, 17, 30)),
            RepeatMath.sequence(due, due, RepeatPresets.yearly(), perth, 3),
        )
    }

    @Test
    fun everyTwoWeeksOnWednesdayAndThisOccurrenceDoesNotMoveThePattern() {
        val wednesday = at(perth, 2026, 11, 25, 19, 0)
        val rule = weeks(2, WeekDay.WED)
        assertEquals(
            listOf(wednesday, at(perth, 2026, 12, 9, 19, 0), at(perth, 2026, 12, 23, 19, 0)),
            RepeatMath.sequence(wednesday, wednesday, rule, perth, 3),
        )
        val thursday = at(perth, 2026, 11, 26, 19, 0)
        val shifted = RepeatMath.applyDueShift(wednesday, wednesday, rule, thursday, DueShiftMode.THIS, perth)
        assertEquals(thursday, shifted.dueAt)
        assertEquals(wednesday, shifted.patternDueAt)
        assertEquals(rule.weekDays, shifted.rule.weekDays)
        val next = RepeatMath.step(shifted.dueAt, shifted.patternDueAt, shifted.rule, thursday, perth)
        assertEquals(at(perth, 2026, 12, 9, 19, 0), next.dueAt)
        assertEquals(
            listOf(thursday, at(perth, 2026, 12, 9, 19, 0)),
            RepeatMath.sequence(shifted.dueAt, shifted.patternDueAt, shifted.rule, perth, 2),
        )
    }

    @Test
    fun mondayWednesdayFridayAndIsoWeekInterval() {
        val wednesday = at(perth, 2026, 11, 25, 8, 15)
        val rule = weeks(1, WeekDay.MON, WeekDay.WED, WeekDay.FRI)
        assertEquals(
            listOf(
                wednesday,
                at(perth, 2026, 11, 27, 8, 15),
                at(perth, 2026, 11, 30, 8, 15),
                at(perth, 2026, 12, 2, 8, 15),
            ),
            RepeatMath.sequence(wednesday, wednesday, rule, perth, 4),
        )
        val sunday = at(perth, 2026, 11, 29, 19, 0)
        val everyOtherMonday = weeks(2, WeekDay.MON)
        assertEquals(at(perth, 2026, 12, 7, 19, 0), RepeatMath.nextPattern(sunday, everyOtherMonday, perth))
        val mondayOnly = weeks(1, WeekDay.MON)
        assertEquals(
            listOf(wednesday, at(perth, 2026, 11, 30, 8, 15)),
            RepeatMath.sequence(wednesday, wednesday, mondayOnly, perth, 2),
        )
    }

    @Test
    fun nthLastWeekdayFirstAndLastDayAndShortMonths() {
        val march = at(perth, 2026, 3, 20, 9, 0)
        val thirdFriday = month(1, MonthOn.Nth(MonthNth.THIRD, MonthTarget.FRIDAY))
        assertEquals(
            listOf(march, at(perth, 2026, 4, 17, 9, 0), at(perth, 2026, 5, 15, 9, 0)),
            RepeatMath.sequence(march, march, thirdFriday, perth, 3),
        )
        val marchFirst = at(perth, 2026, 3, 1, 9, 0)
        val lastMonday = month(1, MonthOn.Nth(MonthNth.LAST, MonthTarget.MONDAY))
        assertEquals(at(perth, 2026, 4, 27, 9, 0), RepeatMath.nextPattern(marchFirst, lastMonday, perth))
        assertEquals(
            at(perth, 2026, 11, 30, 9, 0),
            RepeatMath.nextPattern(at(perth, 2026, 10, 15, 9, 0), lastMonday, perth),
        )

        val lastWeekday = month(1, MonthOn.Nth(MonthNth.LAST, MonthTarget.WEEKDAY))
        assertEquals(at(perth, 2026, 2, 27, 9, 0), RepeatMath.nextPattern(at(perth, 2026, 1, 15, 9, 0), lastWeekday, perth))
        assertEquals(at(perth, 2027, 1, 29, 9, 0), RepeatMath.nextPattern(at(perth, 2026, 12, 15, 9, 0), lastWeekday, perth))
        assertEquals(LocalDate.of(2026, 2, 28).dayOfWeek.name, "SATURDAY")
        assertEquals(LocalDate.of(2027, 1, 31).dayOfWeek.name, "SUNDAY")

        val firstDay = month(1, MonthOn.Nth(MonthNth.FIRST, MonthTarget.DAY))
        val lastDay = month(1, MonthOn.Nth(MonthNth.LAST, MonthTarget.DAY))
        assertEquals(at(perth, 2026, 2, 1, 9, 0), RepeatMath.nextPattern(at(perth, 2026, 1, 15, 9, 0), firstDay, perth))
        assertEquals(at(perth, 2026, 2, 28, 9, 0), RepeatMath.nextPattern(at(perth, 2026, 1, 15, 9, 0), lastDay, perth))

        val dom31 = month(1, MonthOn.DayOfMonth(31))
        val jan31 = at(perth, 2026, 1, 31, 17, 30)
        val feb = RepeatMath.nextPattern(jan31, dom31, perth)
        assertEquals(at(perth, 2026, 2, 28, 17, 30), feb)
        assertEquals(at(perth, 2026, 3, 31, 17, 30), RepeatMath.nextPattern(feb, dom31, perth))
        assertEquals(at(perth, 2026, 4, 30, 17, 30), RepeatMath.nextPattern(at(perth, 2026, 3, 31, 17, 30), dom31, perth))
        assertEquals(
            at(perth, 2024, 2, 29, 17, 30),
            RepeatMath.nextPattern(at(perth, 2024, 1, 31, 17, 30), dom31, perth),
        )

        val everyThree = month(3, MonthOn.DayOfMonth(25))
        val oct25 = at(perth, 2026, 10, 25, 17, 30)
        assertEquals(
            listOf(oct25, at(perth, 2027, 1, 25, 17, 30), at(perth, 2027, 4, 25, 17, 30)),
            RepeatMath.sequence(oct25, oct25, everyThree, perth, 3),
        )
    }

    @Test
    fun leapDayYearClampsAndStaysOnTheClampedDay() {
        val leap = at(perth, 2024, 2, 29, 9, 0)
        val next = RepeatMath.nextPattern(leap, RepeatPresets.yearly(), perth)
        assertEquals(at(perth, 2025, 2, 28, 9, 0), next)
        assertEquals(at(perth, 2026, 2, 28, 9, 0), RepeatMath.nextPattern(next, RepeatPresets.yearly(), perth))
    }

    @Test
    fun fromCompletionUsesTheDueTimeOfDayIncludingAfterPostpone() {
        val due = at(perth, 2026, 11, 25, 9, 30)
        val rule = RepeatRule.of(3, RepeatUnit.MONTH, RepeatFrom.COMPLETION)!!
        val completed = at(perth, 2026, 12, 2, 14, 22)
        assertEquals(listOf(due), RepeatMath.sequence(due, null, rule, perth, 6))
        val stepped = RepeatMath.step(due, null, rule, completed, perth)
        assertEquals(at(perth, 2027, 3, 2, 9, 30), stepped.dueAt)
        assertNull(stepped.patternDueAt)

        val postponed = at(perth, 2026, 11, 25, 16, 0)
        val afterPostpone = RepeatMath.step(postponed, null, rule, completed, perth)
        assertEquals(at(perth, 2027, 3, 2, 16, 0), afterPostpone.dueAt)

        val hourly = RepeatRule.of(8, RepeatUnit.HOUR, RepeatFrom.COMPLETION)!!
        val doneLater = at(perth, 2026, 3, 5, 14, 0)
        assertEquals(at(perth, 2026, 3, 5, 17, 30), RepeatMath.afterCompletion(doneLater, due, hourly, perth))
        val minutes = RepeatRule.of(15, RepeatUnit.MINUTE, RepeatFrom.DUE_DATE)!!
        assertEquals(due + 15 * 60_000L, RepeatMath.nextPattern(due, minutes, perth))
        assertEquals(due + 30 * 60_000L, RepeatMath.nextPattern(due + 15 * 60_000L, minutes, perth))
    }

    @Test
    fun dueFixtureThisVersusAllFutureAndWeeklyOnSets() {
        val october27 = at(perth, 2026, 10, 27, 17, 30)
        val october28 = at(perth, 2026, 10, 28, 17, 30)
        val rule = month(2, MonthOn.DayOfMonth(27))
        val all = RepeatMath.applyDueShift(october27, october27, rule, october28, DueShiftMode.ALL, perth)
        assertEquals(october28, all.dueAt)
        assertEquals(october28, all.patternDueAt)
        assertEquals(MonthOn.DayOfMonth(28), all.rule.monthOn)
        assertEquals(2, all.rule.interval)
        assertEquals(at(perth, 2026, 12, 28, 17, 30), RepeatMath.nextPattern(all.patternDueAt!!, all.rule, perth))

        val onlyThis = RepeatMath.applyDueShift(october27, october27, rule, october28, DueShiftMode.THIS, perth)
        assertEquals(october28, onlyThis.dueAt)
        assertEquals(october27, onlyThis.patternDueAt)
        assertEquals(MonthOn.DayOfMonth(27), onlyThis.rule.monthOn)
        assertEquals(
            at(perth, 2026, 12, 27, 17, 30),
            RepeatMath.step(onlyThis.dueAt, onlyThis.patternDueAt, onlyThis.rule, october28, perth).dueAt,
        )

        val several = weeks(1, WeekDay.MON, WeekDay.WED, WeekDay.FRI)
        val wednesday = at(perth, 2026, 11, 25, 17, 30)
        val thursdayLater = at(perth, 2026, 11, 26, 19, 0)
        val kept = RepeatMath.applyDueShift(wednesday, wednesday, several, thursdayLater, DueShiftMode.ALL, perth)
        assertEquals(listOf(WeekDay.MON, WeekDay.WED, WeekDay.FRI), kept.rule.weekDays)
        assertEquals(thursdayLater, kept.dueAt)
        assertEquals(at(perth, 2026, 11, 25, 19, 0), kept.patternDueAt)

        val single = weeks(1, WeekDay.WED)
        val retargeted = RepeatMath.applyDueShift(wednesday, wednesday, single, thursdayLater, DueShiftMode.ALL, perth)
        assertEquals(listOf(WeekDay.THU), retargeted.rule.weekDays)
        assertEquals(thursdayLater, retargeted.patternDueAt)
    }

    @Test
    fun missedOccurrenceAdvancesOneStepAndDoesNotSkip() {
        val missed = at(perth, 2026, 11, 1, 9, 0)
        val rule = RepeatPresets.daily()
        val next = RepeatMath.step(missed, missed, rule, at(perth, 2026, 11, 20, 12, 0), perth)
        assertEquals(at(perth, 2026, 11, 2, 9, 0), next.dueAt)
        assertTrue(next.dueAt < at(perth, 2026, 11, 20, 12, 0))
        val stillPast = RepeatMath.step(next.dueAt, next.patternDueAt, rule, at(perth, 2026, 11, 20, 12, 0), perth)
        assertEquals(at(perth, 2026, 11, 3, 9, 0), stillPast.dueAt)
    }

    @Test
    fun wallTimeAcrossDstAndElapsedHours() {
        val beforeSpring = at(sydney, 2026, 10, 3, 9, 0)
        val nextDay = RepeatMath.nextPattern(beforeSpring, RepeatPresets.daily(), sydney)
        assertWall(sydney, nextDay, 2026, 10, 4, 9, 0)
        assertNotEquals(beforeSpring + DAY_MILLIS, nextDay)

        val eightHours = RepeatRule.of(8, RepeatUnit.HOUR, RepeatFrom.DUE_DATE)!!
        val beforeGap = at(sydney, 2026, 10, 4, 1, 0)
        val elapsed = RepeatMath.nextPattern(beforeGap, eightHours, sydney)
        assertEquals(beforeGap + 8 * 3_600_000L, elapsed)
        assertFalse(eightHours.keepsWallClock())

        val intoGap = at(sydney, 2026, 10, 3, 2, 30)
        val gapResult = RepeatMath.nextPattern(intoGap, RepeatPresets.daily(), sydney)
        val gapLocal = LocalDateTime.of(2026, 10, 4, 2, 30)
        assertTrue(sydney.rules.getValidOffsets(gapLocal).isEmpty())
        assertEquals(ZonedDateTime.of(gapLocal, sydney).toInstant().toEpochMilli(), gapResult)
        assertEquals(LocalTime.of(3, 30), Instant.ofEpochMilli(gapResult).atZone(sydney).toLocalTime())

        val intoOverlap = at(sydney, 2026, 4, 4, 2, 30)
        val overlapLocal = LocalDateTime.of(2026, 4, 5, 2, 30)
        val earlier = sydney.rules.getValidOffsets(overlapLocal)
        assertEquals(2, earlier.size)
        val overlapResult = RepeatMath.nextPattern(intoOverlap, RepeatPresets.daily(), sydney)
        assertEquals(ZonedDateTime.of(overlapLocal, sydney).toInstant().toEpochMilli(), overlapResult)
        assertEquals(earlier.first(), Instant.ofEpochMilli(overlapResult).atZone(sydney).offset)
        assertEquals(LocalTime.of(2, 30), Instant.ofEpochMilli(overlapResult).atZone(sydney).toLocalTime())
    }

    @Test
    fun timeZoneRewriteKeepsWallTimeForCalendarUnitsOnly() {
        val due = at(perth, 2026, 11, 25, 9, 0)
        val daily = RepeatPresets.daily()
        val moved = RepeatMath.adjustZone(due, daily, perth, sydney)
        assertWall(sydney, moved, 2026, 11, 25, 9, 0)
        assertNotEquals(due, moved)
        val tokyoWall = RepeatMath.adjustZone(due, daily, perth, tokyo)
        assertWall(tokyo, tokyoWall, 2026, 11, 25, 9, 0)

        val hourly = RepeatRule.of(8, RepeatUnit.HOUR, RepeatFrom.DUE_DATE)!!
        assertEquals(due, RepeatMath.adjustZone(due, hourly, perth, sydney))
        val pattern = at(perth, 2026, 11, 25, 9, 0)
        assertEquals(moved, RepeatMath.adjustZone(pattern, RepeatPresets.yearly(), perth, sydney))
        assertEquals(pattern, RepeatMath.adjustZone(pattern, RepeatRule.of(15, RepeatUnit.MINUTE, RepeatFrom.DUE_DATE)!!, perth, tokyo))
    }

    @Test
    fun phrasesMatchDueWording() {
        val due = at(perth, 2026, 11, 25, 17, 30)
        assertEquals("Every day", RepeatText.describe(RepeatPresets.daily(), due, perth, false, Locale.US))
        assertEquals("Weekdays", RepeatText.describe(RepeatPresets.weekdays(), due, perth, false, Locale.US))
        assertEquals(
            "Every 2 weeks on Wednesday",
            RepeatText.describe(weeks(2, WeekDay.WED), due, perth, false, Locale.US),
        )
        assertEquals(
            "25th each month at 5:30 pm",
            RepeatText.describe(RepeatPresets.monthly(due, perth), due, perth, false, Locale.US),
        )
        assertEquals(
            "3rd Friday",
            RepeatText.describe(month(1, MonthOn.Nth(MonthNth.THIRD, MonthTarget.FRIDAY)), due, perth, true, Locale.US),
        )
        val completion = RepeatRule.of(3, RepeatUnit.MONTH, RepeatFrom.COMPLETION)!!
        assertEquals(
            "Repeat 3 months after completion",
            RepeatText.describe(completion, due, perth, false, Locale.US),
        )
        assertTrue(
            RepeatText.menuLabel(RepeatChoice.MONTHLY, due, perth, false, Locale.US)
                .contains("25th each month at 5:30 pm"),
        )
        val encoded = RepeatRule.encodeDraft(weeks(2, WeekDay.WED))
        assertEquals(weeks(2, WeekDay.WED), RepeatRule.decodeDraft(encoded))
        assertEquals("2 WEEK WED", weeks(2, WeekDay.WED).token())
        assertEquals("1 MONTH NTH:LAST,WEEKDAY", month(1, MonthOn.Nth(MonthNth.LAST, MonthTarget.WEEKDAY)).token())
        assertEquals(MonthOn.DayOfMonth(25), MonthOn.parse("DOM:25"))
        assertEquals(MonthOn.Nth(MonthNth.THIRD, MonthTarget.FRIDAY), MonthOn.parse("NTH:3,FRIDAY"))
    }

    private fun weeks(interval: Int, vararg days: WeekDay): RepeatRule {
        return RepeatRule.of(interval, RepeatUnit.WEEK, RepeatFrom.DUE_DATE, days.toList())!!
    }

    private fun month(interval: Int, on: MonthOn): RepeatRule {
        return RepeatRule.of(interval, RepeatUnit.MONTH, RepeatFrom.DUE_DATE, monthOn = on)!!
    }

    private fun at(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    }

    private fun assertWall(zone: ZoneId, instant: Long, year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        val zoned = Instant.ofEpochMilli(instant).atZone(zone)
        assertEquals(LocalDate.of(year, month, day), zoned.toLocalDate())
        assertEquals(LocalTime.of(hour, minute), zoned.toLocalTime())
    }

    private companion object {
        const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
