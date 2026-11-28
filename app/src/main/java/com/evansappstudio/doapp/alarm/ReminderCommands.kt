package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.RepeatRule

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

    /**
     * [RestoreResult.BLOCKED_BY_CAP] when a free user is at the cap and the undo window
     * has passed (open the paywall, as for a null add). [RestoreResult.REFUSED] when the
     * row is missing, not deleted, or past the purge window.
     */
    suspend fun restore(origin: Origin, id: Long): RestoreResult = scheduler.restore(id, origin)

    suspend fun replaceAll(origin: Origin, reminders: List<Reminder>): List<Long> =
        scheduler.replaceAll(reminders, origin)

    suspend fun mergeAll(origin: Origin, reminders: List<Reminder>): List<Long> =
        scheduler.mergeAll(reminders, origin)
}
