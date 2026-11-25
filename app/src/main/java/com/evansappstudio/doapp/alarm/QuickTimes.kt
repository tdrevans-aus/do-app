package com.evansappstudio.doapp.alarm

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * One Quick Times button.
 *
 * [amount] is minutes from midnight (0 to 1439) for [SnoozeKind.AT_TIME].
 * The unit is ignored for At Time. For [SnoozeKind.FROM_NOW], [amount] is a
 * non-zero signed count of [unit]. Days and weeks are calendar amounts, not
 * a flattened number of minutes.
 */
data class QuickTimeSlot(
    val kind: SnoozeKind,
    val amount: Int,
    val unit: QuickTimeUnit,
) {
    fun isValid(): Boolean = when (kind) {
        SnoozeKind.AT_TIME -> amount in 0 until SnoozeOption.MINUTES_PER_DAY
        SnoozeKind.FROM_NOW -> amount != 0
    }

    companion object {
        fun orNull(kind: SnoozeKind, amount: Int, unit: QuickTimeUnit): QuickTimeSlot? {
            val slot = QuickTimeSlot(kind, amount, unit)
            return if (slot.isValid()) slot else null
        }
    }
}

enum class QuickTimeUnit {
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    ;

    companion object {
        fun fromName(raw: String?): QuickTimeUnit? = entries.firstOrNull { it.name == raw }
    }
}

/** Where an overdue adjustment counts from. Auto is the factory default. */
enum class PostponeMode {
    AUTO,
    FROM_DUE,
    FROM_NOW,
    ;

    companion object {
        fun fromName(raw: String?): PostponeMode? = entries.firstOrNull { it.name == raw }
    }
}

/** Long-press override for one adjustment tap. */
enum class QuickTimeBase {
    NOW,
    DUE,
    ;

    companion object {
        fun fromName(raw: String?): QuickTimeBase? = entries.firstOrNull { it.name == raw }
    }
}

/** Long-press override for one At Time tap. */
enum class QuickTimeDay {
    TODAY,
    TOMORROW,
    DUE,
    ;

    companion object {
        fun fromName(raw: String?): QuickTimeDay? = entries.firstOrNull { it.name == raw }
    }
}

data class QuickTimesSettings(
    val slots: List<QuickTimeSlot>,
    val postpone: PostponeMode,
)

/**
 * Quick Times on the add/edit draft. Nothing here writes a reminder.
 *
 * At Time sets that clock time on the draft's due date, then moves one local
 * day at a time until the instant is after now. From Now adds a signed amount
 * to the draft due. When the draft is overdue, [PostponeMode] chooses the
 * base. Minutes and hours are elapsed durations. Days and weeks keep the
 * local wall time. Gaps and overlaps use java.time's default resolver.
 *
 * A [QuickTimeBase] or [QuickTimeDay] replaces that choice for the tap being
 * applied and does not change the stored postpone mode.
 */
object QuickTimes {
    const val SLOT_COUNT: Int = 12
    val DEFAULT_POSTPONE: PostponeMode = PostponeMode.AUTO

    val FACTORY: List<QuickTimeSlot> = listOf(
        at(9 * 60 + 30),
        at(12 * 60),
        at(18 * 60 + 30),
        at(22 * 60),
        adjust(10, QuickTimeUnit.MINUTE),
        adjust(1, QuickTimeUnit.HOUR),
        adjust(3, QuickTimeUnit.HOUR),
        adjust(1, QuickTimeUnit.DAY),
        adjust(-10, QuickTimeUnit.MINUTE),
        adjust(-1, QuickTimeUnit.HOUR),
        adjust(-3, QuickTimeUnit.HOUR),
        adjust(-1, QuickTimeUnit.DAY),
    )

    fun apply(
        slot: QuickTimeSlot,
        draftDueAt: Long,
        now: Long,
        zone: ZoneId,
        postpone: PostponeMode,
        base: QuickTimeBase? = null,
        day: QuickTimeDay? = null,
    ): Long = when (slot.kind) {
        SnoozeKind.AT_TIME -> atTime(slot.amount, draftDueAt, now, zone, day)
        SnoozeKind.FROM_NOW -> {
            val baseInstant = adjustmentBase(slot, draftDueAt, now, zone, postpone, base)
            add(baseInstant, slot.amount, slot.unit, zone)
        }
    }

    /**
     * Applies the same tap [count] times. Each tap sees the draft produced by
     * the previous tap and the same [now], so a second +10 min adds another
     * 10 minutes and an overdue Auto tap can change base between taps.
     */
    fun applyRepeated(
        slot: QuickTimeSlot,
        draftDueAt: Long,
        now: Long,
        zone: ZoneId,
        postpone: PostponeMode,
        count: Int,
        base: QuickTimeBase? = null,
        day: QuickTimeDay? = null,
    ): Long {
        require(count >= 1) { "count must be at least 1" }
        var due = draftDueAt
        repeat(count) {
            due = apply(slot, due, now, zone, postpone, base, day)
        }
        return due
    }

    fun label(slot: QuickTimeSlot, is24Hour: Boolean, locale: Locale): String = when (slot.kind) {
        SnoozeKind.AT_TIME -> atTimeLabel(slot.amount, is24Hour, locale)
        SnoozeKind.FROM_NOW -> adjustmentLabel(slot.amount, slot.unit)
    }

    fun bannerText(
        dueAt: Long,
        now: Long,
        zone: ZoneId,
        is24Hour: Boolean,
        locale: Locale,
    ): String = "${formatDateTime(dueAt, zone, is24Hour, locale)} (${relativePhrase(dueAt, now)})"

    fun formatDateTime(dueAt: Long, zone: ZoneId, is24Hour: Boolean, locale: Locale): String {
        val zoned = Instant.ofEpochMilli(dueAt).atZone(zone)
        val pattern = if (is24Hour) "EEE d MMM yyyy, HH:mm" else "EEE d MMM yyyy, h:mm a"
        return normalizeClock(DateTimeFormatter.ofPattern(pattern, locale).format(zoned), is24Hour)
    }

    /**
     * Short relative phrase for the date banner. Counts use whole minutes,
     * then whole hours, then whole days: 90 minutes is "in 1 hour".
     * Less than a minute ahead is "in 1 minute". Due or overdue by less than
     * a minute is "now".
     */
    fun relativePhrase(dueAt: Long, now: Long): String {
        val delta = dueAt - now
        val absMillis = if (delta < 0) -delta else delta
        if (absMillis < 60_000L) {
            return if (dueAt <= now) "now" else "in 1 minute"
        }
        val minutes = absMillis / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        val past = dueAt < now
        return when {
            days >= 1L -> span(days, "day", "days", past)
            hours >= 1L -> span(hours, "hour", "hours", past)
            else -> span(minutes, "minute", "minutes", past)
        }
    }

    fun dumpLine(
        postpone: PostponeMode,
        index: Int,
        slot: QuickTimeSlot,
        is24Hour: Boolean,
        locale: Locale,
    ): String {
        val shown = label(slot, is24Hour, locale)
        return "DoAlarm qat-config postpone=${postpone.name} slot=$index " +
            "kind=${slot.kind.name} amount=${slot.amount} unit=${slot.unit.name} label=$shown"
    }

    private fun atTime(
        minutesFromMidnight: Int,
        draftDueAt: Long,
        now: Long,
        zone: ZoneId,
        day: QuickTimeDay?,
    ): Long {
        val time = clockTime(minutesFromMidnight)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dueDate = Instant.ofEpochMilli(draftDueAt).atZone(zone).toLocalDate()
        if (day != null) {
            val date = when (day) {
                QuickTimeDay.TODAY -> today
                QuickTimeDay.TOMORROW -> today.plusDays(1)
                QuickTimeDay.DUE -> dueDate
            }
            return resolve(date, time, zone)
        }
        var date = if (dueDate.isBefore(today)) today else dueDate
        repeat(4) {
            val candidate = resolve(date, time, zone)
            if (candidate > now) return candidate
            date = date.plusDays(1)
        }
        return resolve(date, time, zone)
    }

    private fun adjustmentBase(
        slot: QuickTimeSlot,
        draftDueAt: Long,
        now: Long,
        zone: ZoneId,
        postpone: PostponeMode,
        base: QuickTimeBase?,
    ): Long {
        if (base == QuickTimeBase.NOW) return now
        if (base == QuickTimeBase.DUE) return draftDueAt
        if (draftDueAt > now) return draftDueAt
        return when (postpone) {
            PostponeMode.FROM_DUE -> draftDueAt
            PostponeMode.FROM_NOW -> now
            PostponeMode.AUTO -> {
                val fromDue = add(draftDueAt, slot.amount, slot.unit, zone)
                if (fromDue > now) draftDueAt else now
            }
        }
    }

    private fun add(base: Long, amount: Int, unit: QuickTimeUnit, zone: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(base).atZone(zone)
        val shifted = when (unit) {
            QuickTimeUnit.MINUTE -> zoned.plusMinutes(amount.toLong())
            QuickTimeUnit.HOUR -> zoned.plusHours(amount.toLong())
            QuickTimeUnit.DAY -> zoned.plusDays(amount.toLong())
            QuickTimeUnit.WEEK -> zoned.plusWeeks(amount.toLong())
        }
        return shifted.toInstant().toEpochMilli()
    }

    /** java.time default resolver: gap moves to the next valid local time, overlap uses the earlier offset. */
    private fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Long {
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }

    private fun clockTime(minutesFromMidnight: Int): LocalTime {
        val minutes = minutesFromMidnight.coerceIn(0, SnoozeOption.MINUTES_PER_DAY - 1)
        return LocalTime.of(minutes / 60, minutes % 60)
    }

    private fun atTimeLabel(minutesFromMidnight: Int, is24Hour: Boolean, locale: Locale): String {
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        val formatted = DateTimeFormatter.ofPattern(pattern, locale).format(clockTime(minutesFromMidnight))
        return normalizeClock(formatted, is24Hour)
    }

    private fun adjustmentLabel(amount: Int, unit: QuickTimeUnit): String {
        val count = abs(amount)
        val word = when (unit) {
            // Due 26.6.1 writes the 10-minute slots as "min." and the 3-hour slots as "hrs".
            // Other counts stay "min" and "hr" (+5 min, +2 hr).
            QuickTimeUnit.MINUTE -> if (count == 10) "min." else "min"
            QuickTimeUnit.HOUR -> if (count == 3) "hrs" else "hr"
            QuickTimeUnit.DAY -> if (count == 1) "day" else "days"
            QuickTimeUnit.WEEK -> if (count == 1) "week" else "weeks"
        }
        val sign = if (amount > 0) "+" else "-"
        return "$sign$count $word"
    }

    private fun normalizeClock(formatted: String, is24Hour: Boolean): String {
        val spaced = formatted.replace('\u202f', ' ').replace('\u00a0', ' ')
        return if (is24Hour) spaced else spaced.replace("AM", "am").replace("PM", "pm")
    }

    private fun span(count: Long, singular: String, plural: String, past: Boolean): String {
        val unit = if (count == 1L) singular else plural
        return if (past) "$count $unit ago" else "in $count $unit"
    }

    private fun at(minutesFromMidnight: Int): QuickTimeSlot {
        return QuickTimeSlot(SnoozeKind.AT_TIME, minutesFromMidnight, QuickTimeUnit.MINUTE)
    }

    private fun adjust(amount: Int, unit: QuickTimeUnit): QuickTimeSlot {
        return QuickTimeSlot(SnoozeKind.FROM_NOW, amount, unit)
    }
}
