package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.MonthNth
import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.MonthTarget
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekDay
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Due time, pattern anchor and rule after a this/all-future edit. */
data class DuePlacement(
    val rule: RepeatRule,
    val dueAt: Long,
    val patternDueAt: Long?,
)

/**
 * Calendar and elapsed recurrence.
 *
 * From-due-date steps move one pattern interval past [patternDueAt], never past
 * a postponed [dueAt] and never forward to "the next future date". Minute and
 * hour steps are elapsed durations. Day, week, month and year keep local wall
 * time; a gap uses java.time's next valid local time and an overlap uses the
 * earlier offset.
 */
object RepeatMath {
    fun nextPattern(patternDueAt: Long, rule: RepeatRule, zone: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(patternDueAt).atZone(zone)
        return when (rule.unit) {
            RepeatUnit.MINUTE -> plusElapsed(zoned, Duration.ofMinutes(rule.interval.toLong()))
            RepeatUnit.HOUR -> plusElapsed(zoned, Duration.ofHours(rule.interval.toLong()))
            RepeatUnit.DAY -> zoned.plusDays(rule.interval.toLong()).toInstant().toEpochMilli()
            RepeatUnit.WEEK -> nextWeek(zoned, rule.interval, rule.weekDays, zone)
            RepeatUnit.MONTH -> nextMonth(zoned, rule.interval, rule.monthOn, zone)
            RepeatUnit.YEAR -> zoned.plusYears(rule.interval.toLong()).toInstant().toEpochMilli()
        }
    }

    /**
     * Current occurrence at [dueAt], then [count]-1 pattern steps after
     * [patternDueAt]. From-completion has no calendar next, so only [dueAt]
     * is returned.
     */
    fun sequence(
        dueAt: Long,
        patternDueAt: Long?,
        rule: RepeatRule,
        zone: ZoneId,
        count: Int,
    ): List<Long> {
        require(count >= 1) { "count must be at least 1" }
        if (rule.from == RepeatFrom.COMPLETION) return listOf(dueAt)
        val out = ArrayList<Long>(count)
        out.add(dueAt)
        var cursor = patternDueAt ?: dueAt
        while (out.size < count) {
            val next = nextPattern(cursor, rule, zone)
            out.add(next)
            cursor = next
        }
        return out
    }

    /**
     * One step. A from-due-date step uses the pattern anchor, not the
     * possibly postponed due and not "now", so a missed occurrence advances
     * once even when that next instant is still in the past.
     */
    fun step(
        dueAt: Long,
        patternDueAt: Long?,
        rule: RepeatRule,
        completedAt: Long,
        zone: ZoneId,
    ): DuePlacement = when (rule.from) {
        RepeatFrom.COMPLETION -> {
            val next = afterCompletion(completedAt, dueAt, rule, zone)
            DuePlacement(rule, next, null)
        }
        RepeatFrom.DUE_DATE -> {
            val anchor = patternDueAt ?: dueAt
            val next = nextPattern(anchor, rule, zone)
            DuePlacement(rule, next, next)
        }
    }

    /**
     * Completion local date, at the reminder's due time of day, plus the
     * interval. Minute and hour add that duration after the due time of day.
     */
    fun afterCompletion(completedAt: Long, dueAt: Long, rule: RepeatRule, zone: ZoneId): Long {
        val completionDate = Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()
        val dueTime = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalTime()
        val base = ZonedDateTime.of(completionDate, dueTime, zone)
        val shifted = when (rule.unit) {
            RepeatUnit.MINUTE -> base.plus(Duration.ofMinutes(rule.interval.toLong()))
            RepeatUnit.HOUR -> base.plus(Duration.ofHours(rule.interval.toLong()))
            RepeatUnit.DAY -> base.plusDays(rule.interval.toLong())
            RepeatUnit.WEEK -> base.plusWeeks(rule.interval.toLong())
            RepeatUnit.MONTH -> base.plusMonths(rule.interval.toLong())
            RepeatUnit.YEAR -> base.plusYears(rule.interval.toLong())
        }
        return shifted.toInstant().toEpochMilli()
    }

    /**
     * Editor save and SHIFT_DUE. [mode] THIS keeps the pattern. ALL retargets
     * it. A weekly rule with several On days keeps that set and only the time
     * of day moves onto the pattern; the date change stays this occurrence.
     */
    fun applyDueShift(
        existingPattern: Long?,
        existingDue: Long,
        draftRule: RepeatRule,
        newDue: Long,
        mode: DueShiftMode,
        zone: ZoneId,
    ): DuePlacement {
        if (draftRule.from != RepeatFrom.DUE_DATE) {
            return DuePlacement(draftRule, newDue, null)
        }
        return when (mode) {
            DueShiftMode.THIS -> {
                val pattern = existingPattern ?: existingDue
                DuePlacement(draftRule, newDue, pattern)
            }
            DueShiftMode.ALL -> {
                if (draftRule.unit == RepeatUnit.WEEK && draftRule.weekDays.size > 1) {
                    val anchor = Instant.ofEpochMilli(existingPattern ?: existingDue).atZone(zone)
                    val newTime = Instant.ofEpochMilli(newDue).atZone(zone).toLocalTime()
                    val pattern = resolve(anchor.toLocalDate(), newTime, zone)
                    DuePlacement(draftRule, newDue, pattern)
                } else {
                    val retargeted = retarget(draftRule, newDue, zone)
                    DuePlacement(retargeted, newDue, newDue)
                }
            }
        }
    }

    /**
     * Calendar units keep local wall time in [to]. Minute and hour keep the instant.
     * Gap and overlap use java.time's resolver.
     */
    fun adjustZone(instant: Long, rule: RepeatRule, from: ZoneId, to: ZoneId): Long {
        if (!rule.keepsWallClock() || from == to) return instant
        return sameWallTime(instant, from, to)
    }

    /** Same local date and clock time in [to]. Gap and overlap use java.time's resolver. */
    fun sameWallTime(instant: Long, from: ZoneId, to: ZoneId): Long {
        if (from == to) return instant
        val local = Instant.ofEpochMilli(instant).atZone(from).toLocalDateTime()
        return ZonedDateTime.of(local, to).toInstant().toEpochMilli()
    }

    private fun retarget(rule: RepeatRule, newDue: Long, zone: ZoneId): RepeatRule {
        val date = Instant.ofEpochMilli(newDue).atZone(zone).toLocalDate()
        return when (rule.unit) {
            RepeatUnit.WEEK -> {
                val day = WeekDay.fromDayOfWeek(date.dayOfWeek)
                rule.copy(weekDays = listOf(day))
            }
            RepeatUnit.MONTH -> rule.copy(monthOn = retargetMonth(rule.monthOn, date))
            else -> rule
        }
    }

    private fun retargetMonth(monthOn: MonthOn?, date: LocalDate): MonthOn {
        val yearMonth = YearMonth.from(date)
        return when (monthOn) {
            is MonthOn.Nth -> when (monthOn.target) {
                MonthTarget.DAY -> when (date.dayOfMonth) {
                    1 -> MonthOn.Nth(MonthNth.FIRST, MonthTarget.DAY)
                    yearMonth.lengthOfMonth() -> MonthOn.Nth(MonthNth.LAST, MonthTarget.DAY)
                    else -> MonthOn.DayOfMonth(date.dayOfMonth)
                }
                MonthTarget.WEEKDAY -> when {
                    date == firstWeekday(yearMonth) -> MonthOn.Nth(MonthNth.FIRST, MonthTarget.WEEKDAY)
                    date == lastWeekday(yearMonth) -> MonthOn.Nth(MonthNth.LAST, MonthTarget.WEEKDAY)
                    else -> nthOfDate(date)
                }
                else -> nthOfDate(date)
            }
            else -> MonthOn.DayOfMonth(date.dayOfMonth)
        }
    }

    private fun nthOfDate(date: LocalDate): MonthOn.Nth {
        val target = MonthTarget.fromWeekDay(WeekDay.fromDayOfWeek(date.dayOfWeek))
        var count = 0
        var cursor = date.withDayOfMonth(1)
        while (!cursor.isAfter(date)) {
            if (cursor.dayOfWeek == date.dayOfWeek) count++
            cursor = cursor.plusDays(1)
        }
        val which = if (count >= 5) MonthNth.LAST else MonthNth.fromCount(count, isLast = false)
        return MonthOn.Nth(which, target)
    }

    private fun nextWeek(
        pattern: ZonedDateTime,
        interval: Int,
        days: List<WeekDay>,
        zone: ZoneId,
    ): Long {
        val selected = days.map { it.dayOfWeek() }.toSet()
        val time = pattern.toLocalTime()
        val anchorMonday = pattern.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var date = pattern.toLocalDate().plusDays(1)
        val last = anchorMonday.plusWeeks(interval.toLong() + 2L)
        while (!date.isAfter(last)) {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeks = ChronoUnit.WEEKS.between(anchorMonday, monday)
            if (weeks % interval.toLong() == 0L && date.dayOfWeek in selected) {
                return resolve(date, time, zone)
            }
            date = date.plusDays(1)
        }
        error("No weekly occurrence within one interval")
    }

    private fun nextMonth(
        pattern: ZonedDateTime,
        interval: Int,
        monthOn: MonthOn?,
        zone: ZoneId,
    ): Long {
        val on = monthOn ?: error("Monthly rule is missing monthOn")
        val yearMonth = YearMonth.from(pattern).plusMonths(interval.toLong())
        val date = when (on) {
            is MonthOn.DayOfMonth -> yearMonth.atDay(on.day.coerceAtMost(yearMonth.lengthOfMonth()))
            is MonthOn.Nth -> nthDate(yearMonth, on.which, on.target)
        }
        return resolve(date, pattern.toLocalTime(), zone)
    }

    private fun nthDate(yearMonth: YearMonth, which: MonthNth, target: MonthTarget): LocalDate {
        return when (target) {
            MonthTarget.DAY -> if (which == MonthNth.LAST) yearMonth.atEndOfMonth() else yearMonth.atDay(1)
            MonthTarget.WEEKDAY -> if (which == MonthNth.LAST) lastWeekday(yearMonth) else firstWeekday(yearMonth)
            else -> {
                val day = target.weekDay()?.dayOfWeek() ?: DayOfWeek.MONDAY
                if (which == MonthNth.LAST) {
                    yearMonth.atEndOfMonth().with(TemporalAdjusters.previousOrSame(day))
                } else {
                    val ordinal = when (which) {
                        MonthNth.FIRST -> 1
                        MonthNth.SECOND -> 2
                        MonthNth.THIRD -> 3
                        MonthNth.FOURTH -> 4
                        MonthNth.LAST -> 1
                    }
                    val first = yearMonth.atDay(1).with(TemporalAdjusters.nextOrSame(day))
                    first.plusWeeks((ordinal - 1).toLong())
                }
            }
        }
    }

    private fun firstWeekday(yearMonth: YearMonth): LocalDate {
        var date = yearMonth.atDay(1)
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.plusDays(1)
        }
        return date
    }

    private fun lastWeekday(yearMonth: YearMonth): LocalDate {
        var date = yearMonth.atEndOfMonth()
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.minusDays(1)
        }
        return date
    }

    private fun plusElapsed(zoned: ZonedDateTime, duration: Duration): Long {
        return zoned.plus(duration).toInstant().toEpochMilli()
    }

    /** Gap moves forward to the next valid local time. Overlap keeps the earlier offset. */
    private fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Long {
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }
}
