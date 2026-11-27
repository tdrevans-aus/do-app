package com.evansappstudio.doapp.alarm

// The pure Spec 9 command types. No imports on purpose: this file is in the core
// set (CorePurityTest) so it can later be shared with an iPhone app. ReminderCommands.kt
// is the Android-side facade over Scheduler.

/**
 * Where a reminder came from. Stored on the reminder at create and never
 * changed afterwards, except by import, which keeps the exported value.
 */
enum class ReminderSource {
    USER,
    CATALOGUE,
    AGENT,
    SHARE,
    WIDGET,
    IMPORT,
    ;

    companion object {
        val DEFAULT: ReminderSource = USER

        fun fromName(raw: String?): ReminderSource? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** Who caused one change. Stored on each event, not on the reminder. */
enum class Actor {
    USER,
    NOTIFICATION,
    SYSTEM,
    AGENT,
    IMPORT,
    DEBUG,
}

/**
 * Who did it and on whose behalf. [sourceId] is the catalogue item id or the
 * agent's id, free text, cut to [Origin.MAX_SOURCE_ID] characters.
 */
data class Origin(
    val actor: Actor,
    val source: ReminderSource = ReminderSource.USER,
    val sourceId: String? = null,
) {
    /** [sourceId] trimmed, blank as null, longer than the limit cut rather than refused. */
    fun cleanSourceId(): String? = clipSourceId(sourceId)

    companion object {
        const val MAX_SOURCE_ID = 200

        fun clipSourceId(raw: String?): String? {
            val clean = raw?.trim()
            if (clean.isNullOrEmpty()) return null
            return cutText(clean, MAX_SOURCE_ID)
        }
    }
}

/** What happened to a reminder. Stored by name on [com.evansappstudio.doapp.data.ReminderEvent.type]. */
enum class EventType {
    CREATED,
    EDITED,

    /** The first fire at due, including a catch-up of a missed first fire. */
    FIRED,

    /** A nag fire that alerted. */
    NAGGED,

    /** A nag fire held by Do Not Disturb (with quiet hours following it). */
    HELD,
    SNOOZED,
    ALERT_STOPPED,
    LEVEL_CHANGED,
    REPEAT_CHANGED,
    DONE,

    /** A repeating reminder moved to its next occurrence on Done. */
    ADVANCED,
    DELETED,
    RESTORED,
    IMPORTED,
}

/** Agent approval state. Stored only in this spec; the cloud agent API spec uses it. */
enum class Approval {
    NONE,
    PENDING,
    APPROVED,
    DECLINED,
    ;

    companion object {
        val DEFAULT: Approval = NONE

        fun fromName(raw: String?): Approval? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** Life areas a catalogue reminder can belong to. Anything else is stored as null. */
object ReminderCategory {
    val ALL: List<String> = listOf("home", "renter", "car", "kids", "pets", "health", "money", "digital")

    fun normalize(raw: String?): String? {
        val clean = raw?.trim()?.lowercase() ?: return null
        return clean.takeIf { it in ALL }
    }
}

/** Soft delete. Rows deleted more than [PURGE_AFTER_DAYS] days ago are removed for good. */
object SoftDelete {
    const val PURGE_AFTER_DAYS = 30
    const val DAY_MS = 24L * 60L * 60L * 1000L

    fun cutoff(now: Long, days: Int = PURGE_AFTER_DAYS): Long = now - days.toLong() * DAY_MS

    /** Restore is valid only while the row would not yet be purged. */
    fun canRestore(deletedAt: Long, now: Long, days: Int = PURGE_AFTER_DAYS): Boolean =
        deletedAt >= cutoff(now, days)
}

/** The append-only event log. Only the newest [MAX_PER_REMINDER] events per reminder are kept. */
object EventLog {
    const val MAX_PER_REMINDER = 200
    const val MAX_DETAIL = 500

    /** Space-separated key=value pairs. Null values are left out. Never pass the title. */
    fun detail(vararg pairs: Pair<String, Any?>): String? {
        val text = pairs
            .filter { it.second != null }
            .joinToString(" ") { (key, value) -> "$key=$value" }
        return clipDetail(text)
    }

    fun clipDetail(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        return cutText(raw, MAX_DETAIL)
    }
}

/**
 * [text] cut to at most [max] UTF-16 units, never splitting a surrogate pair:
 * when the cut would leave a lone high surrogate at the end, that unit goes too.
 */
fun cutText(text: String, max: Int): String {
    if (text.length <= max) return text
    val end = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
    return text.substring(0, end)
}
