package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.capture.AgentHold
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
 *
 * Spec 12: listeners added with [addListener] run after every command returns,
 * which is after its Room transaction has committed. The home-screen widgets
 * refresh from here, so they never poll.
 */
class ReminderCommands(private val scheduler: Scheduler) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Runs [listener] after each command. A listener that throws is logged and skipped. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun <T> T.committed(): T {
        for (listener in listeners) {
            try {
                listener()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            }
        }
        return this
    }

    /**
     * An [Actor.AGENT] add above Standard is held: it is created at Standard with
     * [Reminder.requestedAlertLevel] set and [Approval.PENDING] (see [AgentHold]).
     */
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
    ): Long? {
        val held = AgentHold.apply(origin.actor == Actor.AGENT, alertLevel)
        return scheduler.add(
            title = title,
            dueAt = dueAt,
            nagMinutes = nagMinutes,
            rule = rule,
            alertLevel = held.alertLevel,
            origin = origin,
            category = category,
            templateId = templateId,
            templateVersion = templateVersion,
            approval = held.approval,
            requestedAlertLevel = held.requested,
        ).committed()
    }

    suspend fun update(
        origin: Origin,
        id: Long,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule?,
        dueShift: DueShiftMode? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
    ): EditResult = scheduler.update(id, title, dueAt, nagMinutes, rule, dueShift, alertLevel, origin).committed()

    suspend fun markDone(origin: Origin, id: Long) = scheduler.markDone(id, origin).committed()

    suspend fun snooze(origin: Origin, id: Long, optionIndex: Int, option: SnoozeOption) =
        scheduler.snooze(id, optionIndex, option, origin).committed()

    suspend fun snoozeShowing(origin: Origin, optionIndex: Int, option: SnoozeOption) =
        scheduler.snoozeShowing(optionIndex, option, origin).committed()

    suspend fun stopAlert(origin: Origin, id: Long) = scheduler.stopAlert(id, origin).committed()

    suspend fun setAlertLevel(origin: Origin, id: Long, level: AlertLevel) =
        scheduler.setAlertLevel(id, level, origin).committed()

    suspend fun setRepeat(origin: Origin, id: Long, rule: RepeatRule?) = scheduler.setRepeat(id, rule, origin).committed()

    suspend fun shiftDue(origin: Origin, id: Long, newDue: Long, mode: DueShiftMode): Reminder? =
        scheduler.shiftDue(id, newDue, mode, origin).committed()

    suspend fun setTitle(origin: Origin, id: Long, title: String): Reminder? = scheduler.setTitle(id, title, origin).committed()

    /** Soft delete. The row stays for [SoftDelete.PURGE_AFTER_DAYS] days. */
    suspend fun delete(origin: Origin, id: Long) = scheduler.delete(id, origin).committed()

    /** False when the row is missing, not deleted, or past the purge window. */
    suspend fun restore(origin: Origin, id: Long): Boolean = scheduler.restore(id, origin).committed()

    suspend fun replaceAll(origin: Origin, reminders: List<Reminder>): List<Long> =
        scheduler.replaceAll(reminders, origin).committed()

    suspend fun mergeAll(origin: Origin, reminders: List<Reminder>): List<Long> =
        scheduler.mergeAll(reminders, origin).committed()
}
