package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.DateParser
import com.evansappstudio.doapp.alarm.cutText
import com.evansappstudio.doapp.data.RepeatRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

// Spec 12 pure capture logic. No Android imports: this file is in CorePurityTest's list.

/** Text arriving from another app or a link, made into something short enough to be a title. */
object ShareText {
    const val MAX_LENGTH = 200

    /**
     * The first line that is not blank, with runs of white space made single,
     * cut to [MAX_LENGTH] characters (never splitting an emoji). Null when there
     * is nothing left, which the callers treat as "refused".
     */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val line = raw.lineSequence()
            .map { it.replace(WHITE, " ").trim() }
            .firstOrNull { visible(it) }
            ?: return null
        return cutText(line, MAX_LENGTH).trim().takeIf { visible(it) }
    }

    /**
     * False when nothing is left once format characters (directional marks and
     * isolates, zero-width joiners and the like) are taken away. They are kept in
     * the text itself, because joined emoji need them.
     */
    fun visible(text: String): Boolean = text.replace(FORMAT, "").isNotBlank()

    /** Links in shared text: kept in the title, never read for dates. */
    val URL: Regex = Regex("""(?i)\b(?:https?://|www\.)\S+""")

    /** [text] with each link replaced by a same-length run of "x", so parser spans still index [text]. */
    fun maskUrls(text: String): String = URL.replace(text) { match -> "x".repeat(match.value.length) }

    private val WHITE = Regex("[\\s\\u00a0\\u200b\\u2028\\u2029\\ufeff]+")
    private val FORMAT = Regex("\\p{Cf}")
}

/**
 * What the share sheet shows before Save. [matched] is the words the date came
 * from ("Friday after work"), or null when no date was found and [dueAt] is the
 * default (tomorrow 9 am).
 */
data class CaptureDraft(
    val text: String,
    val title: String,
    val dueAt: Long,
    val rule: RepeatRule?,
    val matched: String?,
) {
    val defaulted: Boolean get() = matched == null
}

object CaptureDrafts {
    /** Null when the text is empty after [ShareText.clean]. */
    fun fromText(raw: String?, now: Long, zone: ZoneId, parseDates: Boolean = true): CaptureDraft? {
        val text = ShareText.clean(raw) ?: return null
        val fallback = CaptureTimes.defaultDue(now, zone)
        // Links are hidden from the parser (Spec 12 QA, BUG-S12-01): a path like /2026/nov/27/ is not a date.
        val masked = ShareText.maskUrls(text)
        val match = if (parseDates) DateParser.parse(masked, now, fallback, zone) else null
        if (match == null) {
            return CaptureDraft(text = text, title = text, dueAt = fallback, rule = null, matched = null)
        }
        val title = leftover(text, match.start, match.end).ifEmpty { text }
        return CaptureDraft(
            text = text,
            title = title,
            dueAt = match.dueAt,
            rule = match.rule,
            matched = text.substring(match.start, match.end),
        )
    }

    /** The same tidy as DateParser's title: both sides of the match, trimmed, single-spaced. */
    private fun leftover(text: String, start: Int, end: Int): String {
        val left = text.substring(0, start).trim()
        val right = text.substring(end).trim()
        return listOf(left, right).filter { it.isNotEmpty() }.joinToString(" ").replace(Regex("\\s+"), " ")
    }
}

/** The share sheet's four quick choices, and the default due when no date is found. */
object CaptureTimes {
    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
    val TONIGHT_TIME: LocalTime = LocalTime.of(19, 0)
    const val HOUR_MS = 60L * 60L * 1000L

    /** Tomorrow at 9 am. Spec 14 may add a user setting for this. */
    fun defaultDue(now: Long, zone: ZoneId): Long = tomorrow(now, zone)

    /** Today at 7 pm; once 7 pm has passed, an hour from now. */
    fun tonight(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val sevenPm = at(today, TONIGHT_TIME, zone)
        return if (sevenPm > now) sevenPm else now + HOUR_MS
    }

    fun tomorrow(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return at(today.plusDays(1), DEFAULT_TIME, zone)
    }

    /** The next Saturday at 9 am that is still ahead (today, if it is Saturday before 9). */
    fun weekend(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val saturday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val candidate = at(saturday, DEFAULT_TIME, zone)
        return if (candidate > now) candidate else at(saturday.plusWeeks(1), DEFAULT_TIME, zone)
    }

    /** A picked date and clock time. */
    fun chosen(date: LocalDate, time: LocalTime, zone: ZoneId): Long = at(date, time, zone)

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
}
