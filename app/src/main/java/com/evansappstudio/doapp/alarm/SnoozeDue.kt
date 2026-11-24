package com.evansappstudio.doapp.alarm

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SnoozeKind {
    FROM_NOW,
    AT_TIME,
    ;

    companion object {
        fun fromName(raw: String?): SnoozeKind? = when (raw) {
            FROM_NOW.name -> FROM_NOW
            AT_TIME.name -> AT_TIME
            else -> null
        }
    }
}

/**
 * One of the three one-tap snoozes.
 *
 * [value] is minutes for [SnoozeKind.FROM_NOW], and minutes from midnight
 * (0 to 1439) for [SnoozeKind.AT_TIME].
 */
data class SnoozeOption(
    val kind: SnoozeKind,
    val value: Int,
) {
    fun isValid(): Boolean = when (kind) {
        SnoozeKind.FROM_NOW -> value >= 0
        SnoozeKind.AT_TIME -> value in 0..MINUTES_PER_DAY - 1
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun orNull(kind: SnoozeKind, value: Int): SnoozeOption? {
            val option = SnoozeOption(kind, value)
            return if (option.isValid()) option else null
        }
    }
}

/**
 * New due time for a one-tap snooze.
 *
 * From Now is [now] plus the duration. One day is 24 hours, not the same
 * clock time tomorrow.
 *
 * At Time is the next occurrence of that clock time. If today's occurrence
 * is not strictly after [now], 24 hours are added until it is.
 *
 * A computed target that is already at or before [now] is a missed fire:
 * [nextFire] is [now], and nag stays anchored on the stored due.
 */
object SnoozeDue {
    val DEFAULTS: List<SnoozeOption> = listOf(
        SnoozeOption(SnoozeKind.FROM_NOW, 60),
        SnoozeOption(SnoozeKind.FROM_NOW, 3 * 60),
        SnoozeOption(SnoozeKind.FROM_NOW, SnoozeOption.MINUTES_PER_DAY),
    )

    private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

    fun compute(option: SnoozeOption, now: Long, zone: ZoneId): Long = when (option.kind) {
        SnoozeKind.FROM_NOW -> now + option.value.coerceAtLeast(0).toLong() * NagSchedule.MINUTE_MILLIS
        SnoozeKind.AT_TIME -> nextClockTime(option.value, now, zone)
    }

    fun nextFire(dueAt: Long, now: Long): Long = NagSchedule.catchUp(dueAt, now)

    /**
     * Next alarm after the nag interval changes and the due time does not.
     * A future due stays put. After the reminder has already fired, nag off
     * stays quiet and nag on takes the next anchor strictly after [now].
     */
    fun nextFireAfterNagChange(dueAt: Long, nagMinutes: Int?, now: Long): Long? {
        if (now < dueAt) return dueAt
        return NagSchedule.afterDelivery(dueAt, nagMinutes, now)
    }

    private fun nextClockTime(minutesFromMidnight: Int, now: Long, zone: ZoneId): Long {
        val minutes = minutesFromMidnight.coerceIn(0, SnoozeOption.MINUTES_PER_DAY - 1)
        val zonedNow = Instant.ofEpochMilli(now).atZone(zone)
        var candidate = ZonedDateTime.of(
            zonedNow.toLocalDate(),
            LocalTime.of(minutes / 60, minutes % 60),
            zone,
        ).toInstant().toEpochMilli()
        var guard = 0
        while (candidate <= now && guard < 4) {
            candidate += DAY_MILLIS
            guard++
        }
        return candidate
    }
}

object SnoozeLabels {
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun format(option: SnoozeOption): String = when (option.kind) {
        SnoozeKind.FROM_NOW -> fromNow(option.value)
        SnoozeKind.AT_TIME -> atTime(option.value)
    }

    fun fromNow(minutes: Int): String {
        if (minutes > 0 && minutes % SnoozeOption.MINUTES_PER_DAY == 0) {
            val days = minutes / SnoozeOption.MINUTES_PER_DAY
            return if (days == 1) "+1 day" else "+$days days"
        }
        if (minutes > 0 && minutes % 60 == 0) {
            val hours = minutes / 60
            return if (hours == 1) "+1 hour" else "+$hours hours"
        }
        return if (minutes == 1) "+1 minute" else "+$minutes minutes"
    }

    fun atTime(minutesFromMidnight: Int): String {
        val minutes = minutesFromMidnight.coerceIn(0, SnoozeOption.MINUTES_PER_DAY - 1)
        val time = LocalTime.of(minutes / 60, minutes % 60)
        return clock.format(time)
            .replace('\u202f', ' ')
            .replace('\u00a0', ' ')
            .lowercase(Locale.US)
    }
}
