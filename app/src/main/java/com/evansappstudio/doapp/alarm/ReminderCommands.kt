package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.RepeatRule

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
            return if (clean.length > MAX_SOURCE_ID) clean.substring(0, MAX_SOURCE_ID) else clean
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
        return if (raw.length > MAX_DETAIL) raw.substring(0, MAX_DETAIL) else raw
    }
}

/**
 * The one public way to change reminders. Every entry point (screens, notification
 * buttons, the Prominent screen, import, debug hooks, the health test) calls this
 * with an [Origin]. It wraps [Scheduler], which writes the reminder and its event
 * inside one guarded block and one Room transaction.
 *
 * Fires and the reschedule paths stay on [Scheduler]: they are system events.
 */
class ReminderCommands(private val scheduler: Scheduler) {
    suspend fun add(
        origin: Origin,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
        category: String? = null,
        templateId: String? = null,
        templateVersion: Int? = null,
    ): Long? = scheduler.add(
        title = title,
        dueAt = dueAt,
        nagMinutes = nagMinutes,
        rule = rule,
        alertLevel = alertLevel,
        origin = origin,
        category = category,
        templateId = templateId,
        templateVersion = templateVersion,
    )

    suspend fun update(
        origin: Origin,
        id: Long,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule?,
        dueShift: DueShiftMode? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
    ): EditResult = scheduler.update(id, title, dueAt, nagMinutes, rule, dueShift, alertLevel, origin)

    suspend fun markDone(origin: Origin, id: Long) = scheduler.markDone(id, origin)

    suspend fun snooze(origin: Origin, id: Long, optionIndex: Int, option: SnoozeOption) =
        scheduler.snooze(id, optionIndex, option, origin)

    suspend fun snoozeShowing(origin: Origin, optionIndex: Int, option: SnoozeOption) =
        scheduler.snoozeShowing(optionIndex, option, origin)

    suspend fun stopAlert(origin: Origin, id: Long) = scheduler.stopAlert(id, origin)

    suspend fun setAlertLevel(origin: Origin, id: Long, level: AlertLevel) =
        scheduler.setAlertLevel(id, level, origin)

    suspend fun setRepeat(origin: Origin, id: Long, rule: RepeatRule?) = scheduler.setRepeat(id, rule, origin)

    suspend fun shiftDue(origin: Origin, id: Long, newDue: Long, mode: DueShiftMode): Reminder? =
        scheduler.shiftDue(id, newDue, mode, origin)

    suspend fun setTitle(origin: Origin, id: Long, title: String): Reminder? = scheduler.setTitle(id, title, origin)

    /** Soft delete. The row stays for [SoftDelete.PURGE_AFTER_DAYS] days. */
    suspend fun delete(origin: Origin, id: Long) = scheduler.delete(id, origin)

    /** False when the row is missing, not deleted, or past the purge window. */
    suspend fun restore(origin: Origin, id: Long): Boolean = scheduler.restore(id, origin)

    suspend fun replaceAll(origin: Origin, reminders: List<Reminder>): List<Long> =
        scheduler.replaceAll(reminders, origin)

    suspend fun mergeAll(origin: Origin, reminders: List<Reminder>): List<Long> =
        scheduler.mergeAll(reminders, origin)
}
