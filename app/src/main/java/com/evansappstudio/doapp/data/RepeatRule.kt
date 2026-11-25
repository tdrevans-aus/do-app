package com.evansappstudio.doapp.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** How far apart occurrences are. Minute and hour are elapsed; the rest are calendar units. */
enum class RepeatUnit {
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ;

    fun keepsWallClock(): Boolean = this == DAY || this == WEEK || this == MONTH || this == YEAR

    companion object {
        fun fromName(raw: String?): RepeatUnit? = entries.firstOrNull { it.name == raw }
    }
}

/** Whether the series follows the calendar pattern or the completion instant. */
enum class RepeatFrom {
    DUE_DATE,
    COMPLETION,
    ;

    companion object {
        fun fromName(raw: String?): RepeatFrom? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Which day begins the week. Factory is Monday.
 * This orders the weekday picker and is the boundary for every-N-weeks.
 */
enum class WeekStart {
    MONDAY,
    SATURDAY,
    SUNDAY,
    ;

    fun dayOfWeek(): DayOfWeek = when (this) {
        MONDAY -> DayOfWeek.MONDAY
        SATURDAY -> DayOfWeek.SATURDAY
        SUNDAY -> DayOfWeek.SUNDAY
    }

    /** Weekdays in picker order, beginning on this start day. */
    fun orderedDays(): List<WeekDay> {
        val all = WeekDay.entries
        val index = all.indexOf(WeekDay.fromDayOfWeek(dayOfWeek()))
        return all.drop(index) + all.take(index)
    }

    companion object {
        val DEFAULT: WeekStart = MONDAY

        fun fromName(raw: String?): WeekStart? = entries.firstOrNull { it.name == raw }
    }
}

/** ISO weekdays, Monday first in storage. Debug tokens are the three-letter names. */
enum class WeekDay {
    MON,
    TUE,
    WED,
    THU,
    FRI,
    SAT,
    SUN,
    ;

    fun dayOfWeek(): DayOfWeek = when (this) {
        MON -> DayOfWeek.MONDAY
        TUE -> DayOfWeek.TUESDAY
        WED -> DayOfWeek.WEDNESDAY
        THU -> DayOfWeek.THURSDAY
        FRI -> DayOfWeek.FRIDAY
        SAT -> DayOfWeek.SATURDAY
        SUN -> DayOfWeek.SUNDAY
    }

    fun label(): String = when (this) {
        MON -> "Monday"
        TUE -> "Tuesday"
        WED -> "Wednesday"
        THU -> "Thursday"
        FRI -> "Friday"
        SAT -> "Saturday"
        SUN -> "Sunday"
    }

    companion object {
        val WEEKDAYS: List<WeekDay> = listOf(MON, TUE, WED, THU, FRI)

        fun fromDayOfWeek(day: DayOfWeek): WeekDay = entries.first { it.dayOfWeek() == day }

        fun fromToken(raw: String?): WeekDay? = entries.firstOrNull { it.name == raw?.trim()?.uppercase() }

        fun parseList(raw: String?): List<WeekDay>? {
            if (raw.isNullOrBlank()) return emptyList()
            val days = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (days.isEmpty()) return emptyList()
            val parsed = days.map { fromToken(it) ?: return null }
            if (parsed.distinct().size != parsed.size) return null
            return parsed.sortedBy { it.ordinal }
        }
    }
}

/** 1st, 2nd, 3rd, 4th, or Last. A 5th weekday is stored as Last. */
enum class MonthNth {
    FIRST,
    SECOND,
    THIRD,
    FOURTH,
    LAST,
    ;

    fun label(): String = when (this) {
        FIRST -> "1st"
        SECOND -> "2nd"
        THIRD -> "3rd"
        FOURTH -> "4th"
        LAST -> "Last"
    }

    fun token(): String = when (this) {
        FIRST -> "1"
        SECOND -> "2"
        THIRD -> "3"
        FOURTH -> "4"
        LAST -> "LAST"
    }

    companion object {
        fun fromToken(raw: String?): MonthNth? = when (raw?.trim()?.uppercase()) {
            "1", "FIRST" -> FIRST
            "2", "SECOND" -> SECOND
            "3", "THIRD" -> THIRD
            "4", "FOURTH" -> FOURTH
            "LAST" -> LAST
            else -> null
        }

        fun fromCount(count: Int, isLast: Boolean): MonthNth = when {
            count >= 5 || (isLast && count > 4) -> LAST
            count == 1 -> FIRST
            count == 2 -> SECOND
            count == 3 -> THIRD
            count == 4 -> FOURTH
            else -> LAST
        }
    }
}

/** What a monthly nth rule lands on. DAY and WEEKDAY are only valid with 1st or Last. */
enum class MonthTarget {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY,
    DAY,
    WEEKDAY,
    ;

    fun label(): String = when (this) {
        MONDAY -> "Monday"
        TUESDAY -> "Tuesday"
        WEDNESDAY -> "Wednesday"
        THURSDAY -> "Thursday"
        FRIDAY -> "Friday"
        SATURDAY -> "Saturday"
        SUNDAY -> "Sunday"
        DAY -> "day"
        WEEKDAY -> "weekday"
    }

    fun weekDay(): WeekDay? = when (this) {
        MONDAY -> WeekDay.MON
        TUESDAY -> WeekDay.TUE
        WEDNESDAY -> WeekDay.WED
        THURSDAY -> WeekDay.THU
        FRIDAY -> WeekDay.FRI
        SATURDAY -> WeekDay.SAT
        SUNDAY -> WeekDay.SUN
        DAY, WEEKDAY -> null
    }

    companion object {
        fun fromToken(raw: String?): MonthTarget? = entries.firstOrNull { it.name == raw?.trim()?.uppercase() }

        fun fromWeekDay(day: WeekDay): MonthTarget = when (day) {
            WeekDay.MON -> MONDAY
            WeekDay.TUE -> TUESDAY
            WeekDay.WED -> WEDNESDAY
            WeekDay.THU -> THURSDAY
            WeekDay.FRI -> FRIDAY
            WeekDay.SAT -> SATURDAY
            WeekDay.SUN -> SUNDAY
        }
    }
}

/** Where a monthly rule lands. Encoded as `DOM:25` or `NTH:3,FRIDAY`. */
sealed interface MonthOn {
    fun token(): String

    data class DayOfMonth(val day: Int) : MonthOn {
        override fun token(): String = "DOM:$day"
    }

    data class Nth(val which: MonthNth, val target: MonthTarget) : MonthOn {
        override fun token(): String = "NTH:${which.token()},${target.name}"
    }

    companion object {
        fun parse(raw: String?): MonthOn? {
            if (raw.isNullOrBlank()) return null
            val text = raw.trim()
            if (text.startsWith("DOM:")) {
                val day = text.removePrefix("DOM:").toIntOrNull() ?: return null
                if (day !in 1..31) return null
                return DayOfMonth(day)
            }
            if (!text.startsWith("NTH:")) return null
            val parts = text.removePrefix("NTH:").split(",")
            if (parts.size != 2) return null
            val which = MonthNth.fromToken(parts[0]) ?: return null
            val target = MonthTarget.fromToken(parts[1]) ?: return null
            if ((target == MonthTarget.DAY || target == MonthTarget.WEEKDAY) &&
                which != MonthNth.FIRST &&
                which != MonthNth.LAST
            ) {
                return null
            }
            return Nth(which, target)
        }
    }
}

/**
 * One series on the reminder row. Null means Repeat Off.
 * [weekDays] is sorted Monday-first. [monthOn] is set only for [RepeatUnit.MONTH].
 */
data class RepeatRule(
    val interval: Int,
    val unit: RepeatUnit,
    val from: RepeatFrom,
    val weekDays: List<WeekDay>,
    val monthOn: MonthOn?,
) {
    fun keepsWallClock(): Boolean = unit.keepsWallClock()

    fun token(): String {
        val days = if (weekDays.isEmpty()) "" else weekDays.joinToString(",") { it.name }
        val month = monthOn?.token().orEmpty()
        return "$interval ${unit.name}${if (days.isEmpty()) "" else " $days"}${if (month.isEmpty()) "" else " $month"}"
    }

    companion object {
        const val MIN_INTERVAL: Int = 1
        const val MAX_INTERVAL: Int = 99

        fun of(
            interval: Int,
            unit: RepeatUnit,
            from: RepeatFrom,
            weekDays: List<WeekDay> = emptyList(),
            monthOn: MonthOn? = null,
        ): RepeatRule? {
            if (interval !in MIN_INTERVAL..MAX_INTERVAL) return null
            val days = weekDays.distinct().sortedBy { it.ordinal }
            return when (unit) {
                RepeatUnit.MINUTE, RepeatUnit.HOUR, RepeatUnit.DAY, RepeatUnit.YEAR -> {
                    if (days.isNotEmpty() || monthOn != null) null
                    else RepeatRule(interval, unit, from, emptyList(), null)
                }
                RepeatUnit.WEEK -> {
                    if (monthOn != null) null
                    else if (from == RepeatFrom.DUE_DATE && days.isEmpty()) null
                    else RepeatRule(interval, unit, from, days, null)
                }
                RepeatUnit.MONTH -> {
                    if (days.isNotEmpty()) null
                    else if (from == RepeatFrom.DUE_DATE && monthOn == null) null
                    else RepeatRule(interval, unit, from, emptyList(), monthOn)
                }
            }
        }

        fun encodeDraft(rule: RepeatRule?): String {
            if (rule == null) return ""
            val days = rule.weekDays.joinToString(",") { it.name }
            val month = rule.monthOn?.token().orEmpty()
            return listOf(
                rule.interval.toString(),
                rule.unit.name,
                rule.from.name,
                days,
                month,
            ).joinToString("|")
        }

        fun decodeDraft(text: String?): RepeatRule? {
            if (text.isNullOrBlank()) return null
            val parts = text.split("|")
            if (parts.size != 5) return null
            val interval = parts[0].toIntOrNull() ?: return null
            val unit = RepeatUnit.fromName(parts[1]) ?: return null
            val from = RepeatFrom.fromName(parts[2]) ?: return null
            val days = WeekDay.parseList(parts[3].ifBlank { null }) ?: return null
            val month = if (parts[4].isBlank()) null else MonthOn.parse(parts[4]) ?: return null
            return of(interval, unit, from, days, month)
        }

        fun fromColumns(
            interval: Int?,
            unit: String?,
            from: String?,
            weekDays: String?,
            monthOn: String?,
        ): RepeatRule? {
            if (interval == null && unit == null && from == null && weekDays == null && monthOn == null) {
                return null
            }
            val parsedUnit = RepeatUnit.fromName(unit) ?: return null
            val parsedFrom = RepeatFrom.fromName(from) ?: return null
            val parsedDays = WeekDay.parseList(weekDays) ?: return null
            val parsedMonth = if (monthOn.isNullOrBlank()) null else MonthOn.parse(monthOn) ?: return null
            return of(interval ?: return null, parsedUnit, parsedFrom, parsedDays, parsedMonth)
        }
    }
}

/** How a due-time edit of a from-due-date series is applied. */
enum class DueShiftMode {
    THIS,
    ALL,
    ;

    companion object {
        fun fromName(raw: String?): DueShiftMode? = entries.firstOrNull { it.name == raw }
    }
}

/** Factory preference for the this/all-future prompt. Prompt is the default. */
enum class RepeatPromptMode {
    PROMPT,
    ALWAYS_THIS,
    ;

    companion object {
        fun fromName(raw: String?): RepeatPromptMode? = entries.firstOrNull { it.name == raw }
    }
}

/** One-tap repeat menu. Custom is anything that is not one of the five presets. */
enum class RepeatChoice {
    OFF,
    DAILY,
    WEEKDAYS,
    WEEKLY,
    MONTHLY,
    YEARLY,
    CUSTOM,
}

object RepeatPresets {
    fun daily(): RepeatRule = RepeatRule.of(1, RepeatUnit.DAY, RepeatFrom.DUE_DATE)!!

    fun weekdays(): RepeatRule = RepeatRule.of(
        interval = 1,
        unit = RepeatUnit.WEEK,
        from = RepeatFrom.DUE_DATE,
        weekDays = WeekDay.WEEKDAYS,
    )!!

    fun weekly(dueAt: Long, zone: ZoneId): RepeatRule {
        val day = WeekDay.fromDayOfWeek(Instant.ofEpochMilli(dueAt).atZone(zone).dayOfWeek)
        return RepeatRule.of(1, RepeatUnit.WEEK, RepeatFrom.DUE_DATE, listOf(day))!!
    }

    fun monthly(dueAt: Long, zone: ZoneId): RepeatRule {
        val day = Instant.ofEpochMilli(dueAt).atZone(zone).dayOfMonth
        return RepeatRule.of(
            interval = 1,
            unit = RepeatUnit.MONTH,
            from = RepeatFrom.DUE_DATE,
            monthOn = MonthOn.DayOfMonth(day),
        )!!
    }

    fun yearly(): RepeatRule = RepeatRule.of(1, RepeatUnit.YEAR, RepeatFrom.DUE_DATE)!!

    fun choice(rule: RepeatRule?, dueAt: Long, zone: ZoneId): RepeatChoice {
        if (rule == null) return RepeatChoice.OFF
        if (rule == daily()) return RepeatChoice.DAILY
        if (rule == weekdays()) return RepeatChoice.WEEKDAYS
        if (rule == weekly(dueAt, zone)) return RepeatChoice.WEEKLY
        if (rule == monthly(dueAt, zone)) return RepeatChoice.MONTHLY
        if (rule == yearly()) return RepeatChoice.YEARLY
        return RepeatChoice.CUSTOM
    }

    /**
     * On a new reminder, moving the draft due retargets a preset that was built
     * from the previous due. Custom rules are left alone.
     */
    fun retargetIfPreset(rule: RepeatRule, oldDue: Long, newDue: Long, zone: ZoneId): RepeatRule? {
        if (rule.from != RepeatFrom.DUE_DATE) return null
        return when (choice(rule, oldDue, zone)) {
            RepeatChoice.DAILY -> daily()
            RepeatChoice.WEEKDAYS -> weekdays()
            RepeatChoice.WEEKLY -> weekly(newDue, zone)
            RepeatChoice.MONTHLY -> monthly(newDue, zone)
            RepeatChoice.YEARLY -> yearly()
            RepeatChoice.OFF, RepeatChoice.CUSTOM -> null
        }
    }
}

/** SQL added by the Room 1 to 2 migration. Existing rows stay Repeat Off. */
object RepeatSchema {
    val V1_TO_V2: List<String> = listOf(
        "ALTER TABLE reminder ADD COLUMN repeatInterval INTEGER",
        "ALTER TABLE reminder ADD COLUMN repeatUnit TEXT",
        "ALTER TABLE reminder ADD COLUMN repeatFrom TEXT",
        "ALTER TABLE reminder ADD COLUMN repeatWeekDays TEXT",
        "ALTER TABLE reminder ADD COLUMN repeatMonthOn TEXT",
        "ALTER TABLE reminder ADD COLUMN patternDueAt INTEGER",
    )

    const val V1_CREATE: String =
        "CREATE TABLE IF NOT EXISTS `reminder` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`dueAt` INTEGER NOT NULL, " +
            "`nagMinutes` INTEGER, " +
            "`isDone` INTEGER NOT NULL, " +
            "`doneAt` INTEGER, " +
            "`createdAt` INTEGER NOT NULL)"
}
