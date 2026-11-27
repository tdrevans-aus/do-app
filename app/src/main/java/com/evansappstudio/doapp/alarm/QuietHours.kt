package com.evansappstudio.doapp.alarm

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One daily quiet window, minute precision, in the device's current zone.
 * The window is [start, end) in local wall time. A nag at exactly the start
 * is inside. A nag at exactly the end is not. Start equal to end is not a window.
 *
 * [inWindow] and [windowEnd] are geometric: they ignore [QuietConfig.enabled].
 * [placeNag] is what arms a nag, and it returns the planned instant when the
 * window is off, the level is exempt, or the instant is outside the window.
 */
data class QuietConfig(
    val enabled: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val followDnd: Boolean,
    val criticalPauses: Boolean,
) {
    companion object {
        const val DEFAULT_START_MINUTE: Int = 22 * 60
        const val DEFAULT_END_MINUTE: Int = 7 * 60

        val DEFAULT = QuietConfig(
            enabled = false,
            startMinute = DEFAULT_START_MINUTE,
            endMinute = DEFAULT_END_MINUTE,
            followDnd = false,
            criticalPauses = false,
        )
    }
}

/** Optional quiet keys from a backup. A null field means "leave that setting". */
data class QuietPatch(
    val enabled: Boolean? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val followDnd: Boolean? = null,
    val criticalPauses: Boolean? = null,
)

object QuietHours {
    const val MINUTES_PER_DAY: Int = 24 * 60

    fun formatMinute(minute: Int): String {
        val safe = minute.coerceIn(0, MINUTES_PER_DAY - 1)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    /** HH:mm. Hours may be one or two digits. Anything else is rejected. */
    fun parseMinute(text: String): Int? {
        val match = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(text.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun minuteInRange(minute: Int): Boolean = minute in 0 until MINUTES_PER_DAY

    /**
     * True for Prominent always, and for Critical unless [QuietConfig.criticalPauses]
     * is on. Standard is never exempt. Keyed on the requested level.
     */
    fun exempt(level: AlertLevel, config: QuietConfig): Boolean = when (level) {
        AlertLevel.PROMINENT -> true
        AlertLevel.CRITICAL -> !config.criticalPauses
        AlertLevel.STANDARD -> false
    }

    fun inWindow(t: Long, config: QuietConfig, zone: ZoneId): Boolean {
        val start = localTime(config.startMinute) ?: return false
        val end = localTime(config.endMinute) ?: return false
        if (start == end) return false
        val time = Instant.ofEpochMilli(t).atZone(zone).toLocalTime()
        return if (start < end) {
            time >= start && time < end
        } else {
            time >= start || time < end
        }
    }

    /**
     * The next local end minute strictly after an instant that is inside the window.
     * Seconds and millis are zero. A spring-forward gap uses [ZonedDateTime.of]'s
     * default (moved later by the gap). An autumn overlap keeps the earlier offset.
     * Null when [t] is not inside the window.
     */
    fun windowEnd(t: Long, config: QuietConfig, zone: ZoneId): Long? {
        if (!inWindow(t, config, zone)) return null
        val end = localTime(config.endMinute) ?: return null
        val start = localTime(config.startMinute) ?: return null
        val zoned = Instant.ofEpochMilli(t).atZone(zone)
        val date = if (config.startMinute > config.endMinute && zoned.toLocalTime() >= start) {
            zoned.toLocalDate().plusDays(1)
        } else {
            zoned.toLocalDate()
        }
        return ZonedDateTime.of(date, end, zone).toInstant().toEpochMilli()
    }

    fun placeNag(planned: Long, alertLevel: AlertLevel, config: QuietConfig, zone: ZoneId): Long {
        if (!config.enabled) return planned
        if (exempt(alertLevel, config)) return planned
        if (!inWindow(planned, config, zone)) return planned
        return windowEnd(planned, config, zone) ?: planned
    }

    fun holdForDnd(alertLevel: AlertLevel, config: QuietConfig, dndActive: Boolean): Boolean {
        return config.followDnd && dndActive && !exempt(alertLevel, config)
    }

    /**
     * Applies a backup patch. An out-of-range minute is ignored. A start that
     * would equal the end (or a pair that is equal) is ignored, and the other
     * keys still apply.
     */
    fun merge(current: QuietConfig, patch: QuietPatch): QuietConfig {
        val startPatch = patch.startMinute?.takeIf { minuteInRange(it) }
        val endPatch = patch.endMinute?.takeIf { minuteInRange(it) }
        var start = startPatch ?: current.startMinute
        var end = endPatch ?: current.endMinute
        if (startPatch != null && endPatch != null && startPatch == endPatch) {
            start = current.startMinute
            end = current.endMinute
        } else if (start == end) {
            if (startPatch != null && endPatch == null) start = current.startMinute
            if (endPatch != null && startPatch == null) end = current.endMinute
        }
        return current.copy(
            enabled = patch.enabled ?: current.enabled,
            startMinute = start,
            endMinute = end,
            followDnd = patch.followDnd ?: current.followDnd,
            criticalPauses = patch.criticalPauses ?: current.criticalPauses,
        )
    }

    private fun localTime(minute: Int): LocalTime? {
        if (!minuteInRange(minute)) return null
        return LocalTime.of(minute / 60, minute % 60)
    }
}
