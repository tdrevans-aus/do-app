package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.MonthNth
import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.MonthTarget
import com.evansappstudio.doapp.data.RepeatChoice
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatPresets
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekDay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Due's phrases for a repeat rule. Times follow the device 12/24-hour setting. */
object RepeatText {
    fun describe(rule: RepeatRule, dueAt: Long, zone: ZoneId, is24Hour: Boolean, locale: Locale): String {
        if (rule.from == RepeatFrom.COMPLETION) {
            return "Repeat ${amount(rule.interval, rule.unit)} after completion"
        }
        return when (rule.unit) {
            RepeatUnit.MINUTE -> every(rule.interval, "minute", "minutes")
            RepeatUnit.HOUR -> every(rule.interval, "hour", "hours")
            RepeatUnit.DAY -> every(rule.interval, "day", "days")
            RepeatUnit.WEEK -> describeWeek(rule)
            RepeatUnit.MONTH -> describeMonth(rule, dueAt, zone, is24Hour, locale)
            RepeatUnit.YEAR -> describeYear(rule, dueAt, zone, locale)
        }
    }

    fun menuLabel(choice: RepeatChoice, dueAt: Long, zone: ZoneId, is24Hour: Boolean, locale: Locale): String {
        val zoned = Instant.ofEpochMilli(dueAt).atZone(zone)
        val time = formatTime(zoned.toLocalTime(), is24Hour, locale)
        return when (choice) {
            RepeatChoice.OFF -> "Repeat Off"
            RepeatChoice.DAILY -> "Daily $time"
            RepeatChoice.WEEKDAYS -> "Weekdays $time"
            RepeatChoice.WEEKLY -> "${pluralWeekday(zoned.dayOfWeek)} $time"
            RepeatChoice.MONTHLY -> "${ordinal(zoned.dayOfMonth)} each month at $time"
            RepeatChoice.YEARLY -> {
                val date = DateTimeFormatter.ofPattern("d MMM", locale).format(zoned)
                "Every $date at $time"
            }
            RepeatChoice.CUSTOM -> "Custom..."
        }
    }

    fun presetRule(choice: RepeatChoice, dueAt: Long, zone: ZoneId): RepeatRule? = when (choice) {
        RepeatChoice.OFF, RepeatChoice.CUSTOM -> null
        RepeatChoice.DAILY -> RepeatPresets.daily()
        RepeatChoice.WEEKDAYS -> RepeatPresets.weekdays()
        RepeatChoice.WEEKLY -> RepeatPresets.weekly(dueAt, zone)
        RepeatChoice.MONTHLY -> RepeatPresets.monthly(dueAt, zone)
        RepeatChoice.YEARLY -> RepeatPresets.yearly()
    }

    fun nextLine(at: Long, zone: ZoneId, is24Hour: Boolean, locale: Locale): String {
        return "Next ${QuickTimes.formatDateTime(at, zone, is24Hour, locale)}"
    }

    private fun describeWeek(rule: RepeatRule): String {
        if (rule.interval == 1 && rule.weekDays == WeekDay.WEEKDAYS) return "Weekdays"
        val days = joinDays(rule.weekDays)
        return when {
            rule.interval == 1 && rule.weekDays.size == 1 -> "Every ${rule.weekDays.single().label()}"
            rule.interval == 1 -> "Every week on $days"
            days.isEmpty() -> every(rule.interval, "week", "weeks")
            else -> "Every ${rule.interval} weeks on $days"
        }
    }

    private fun describeMonth(
        rule: RepeatRule,
        dueAt: Long,
        zone: ZoneId,
        is24Hour: Boolean,
        locale: Locale,
    ): String {
        val on = rule.monthOn
        val landing = when (on) {
            is MonthOn.DayOfMonth -> "${ordinal(on.day)} each month"
            is MonthOn.Nth -> nthPhrase(on)
            null -> "each month"
        }
        if (rule.interval == 1 && on is MonthOn.DayOfMonth) {
            val time = formatTime(Instant.ofEpochMilli(dueAt).atZone(zone).toLocalTime(), is24Hour, locale)
            return "$landing at $time"
        }
        if (rule.interval == 1 && on is MonthOn.Nth) {
            return nthPhrase(on)
        }
        val short = when (on) {
            is MonthOn.DayOfMonth -> "the ${ordinal(on.day)}"
            is MonthOn.Nth -> nthPhrase(on)
            null -> "the pattern day"
        }
        return "Every ${rule.interval} months on $short"
    }

    private fun describeYear(rule: RepeatRule, dueAt: Long, zone: ZoneId, locale: Locale): String {
        val date = DateTimeFormatter.ofPattern("d MMMM", locale)
            .format(Instant.ofEpochMilli(dueAt).atZone(zone))
        return if (rule.interval == 1) "Every $date" else "Every ${rule.interval} years on $date"
    }

    private fun nthPhrase(on: MonthOn.Nth): String = when (on.target) {
        MonthTarget.DAY -> if (on.which == MonthNth.LAST) "Last day" else "1st day"
        MonthTarget.WEEKDAY -> if (on.which == MonthNth.LAST) "Last weekday" else "1st weekday"
        else -> "${on.which.label()} ${on.target.label()}"
    }

    private fun amount(interval: Int, unit: RepeatUnit): String = when (unit) {
        RepeatUnit.MINUTE -> count(interval, "minute", "minutes")
        RepeatUnit.HOUR -> count(interval, "hour", "hours")
        RepeatUnit.DAY -> count(interval, "day", "days")
        RepeatUnit.WEEK -> count(interval, "week", "weeks")
        RepeatUnit.MONTH -> count(interval, "month", "months")
        RepeatUnit.YEAR -> count(interval, "year", "years")
    }

    private fun every(interval: Int, singular: String, plural: String): String {
        return if (interval == 1) "Every $singular" else "Every $interval $plural"
    }

    private fun count(interval: Int, singular: String, plural: String): String {
        return if (interval == 1) "1 $singular" else "$interval $plural"
    }

    private fun joinDays(days: List<WeekDay>): String {
        val names = days.map { it.label() }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    private fun pluralWeekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Mondays"
        DayOfWeek.TUESDAY -> "Tuesdays"
        DayOfWeek.WEDNESDAY -> "Wednesdays"
        DayOfWeek.THURSDAY -> "Thursdays"
        DayOfWeek.FRIDAY -> "Fridays"
        DayOfWeek.SATURDAY -> "Saturdays"
        DayOfWeek.SUNDAY -> "Sundays"
    }

    fun ordinal(day: Int): String {
        val teen = day % 100
        if (teen in 11..13) return "${day}th"
        return when (day % 10) {
            1 -> "${day}st"
            2 -> "${day}nd"
            3 -> "${day}rd"
            else -> "${day}th"
        }
    }

    fun formatTime(time: LocalTime, is24Hour: Boolean, locale: Locale): String {
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        val formatted = DateTimeFormatter.ofPattern(pattern, locale).format(time)
        val spaced = formatted.replace('\u202f', ' ').replace('\u00a0', ' ')
        return if (is24Hour) spaced else spaced.replace("AM", "am").replace("PM", "pm")
    }
}
