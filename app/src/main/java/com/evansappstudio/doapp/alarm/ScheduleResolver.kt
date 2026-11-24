package com.evansappstudio.doapp.alarm

/**
 * Decides the next alarm when (re)scheduling an existing reminder.
 *
 * [mirroredNext] is the fire time written the last time we set an alarm.
 * Null means nag-off already delivered: do not arm another alarm.
 * A mirrored time still in the future is kept, so reschedule is idempotent.
 * A mirrored time already passed was missed, so the next fire is [now].
 * With no mirror, fall back to [NagSchedule.catchUp].
 */
object ScheduleResolver {
    fun resolve(
        dueAt: Long,
        now: Long,
        hasMirror: Boolean,
        mirroredNext: Long?,
    ): Long? {
        if (!hasMirror) return NagSchedule.catchUp(dueAt, now)
        val stored = mirroredNext ?: return null
        return if (stored > now) stored else now
    }
}
