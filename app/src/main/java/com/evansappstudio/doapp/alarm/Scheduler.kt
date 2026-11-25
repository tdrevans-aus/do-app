package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.notify.ReminderNotifier
import kotlinx.coroutines.sync.Mutex

/**
 * Arms, cancels and rebuilds the single alarm for every active reminder.
 * [rescheduleAll] is idempotent: it cancels, then sets, for each active reminder.
 * Callers share one mutex so a fire, a Done action and a reschedule cannot interleave.
 * The lock is held across Room suspensions on purpose.
 */
class Scheduler(
    private val daoProvider: () -> ReminderDao,
    private val mirror: ReminderMirror,
    private val alarms: AlarmScheduler,
    private val notifications: ReminderNotifier,
    private val unlocked: () -> Boolean,
) {
    private val gate = Mutex()

    suspend fun add(title: String, dueAt: Long, nagMinutes: Int?): Long = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "title is required" }
        val nag = NagSchedule.normalize(nagMinutes)
        val now = System.currentTimeMillis()
        val id = dao().insert(
            Reminder(
                title = cleanTitle,
                dueAt = dueAt,
                nagMinutes = nag,
                isDone = false,
                doneAt = null,
                createdAt = now,
            ),
        )
        val stored = dao().getById(id) ?: error("Inserted reminder $id was not readable")
        val next = NagSchedule.catchUp(stored.dueAt, now)
        mirror.upsert(stored.toMirror(nextFireAt = next))
        alarms.schedule(stored.id, next)
        id
    }

    suspend fun markDone(id: Long) = guarded {
        alarms.cancel(id)
        notifications.dismiss(id)
        if (!unlocked()) {
            val existing = mirror.get(id)
            mirror.upsert(
                (existing ?: MirrorEntry(
                    id = id,
                    title = "",
                    dueAt = 0L,
                    nagMinutes = null,
                    nextFireAt = null,
                )).copy(pendingDone = true, nextFireAt = null),
            )
            return@guarded
        }
        dao().markDone(id, System.currentTimeMillis())
        mirror.remove(id)
    }

    suspend fun delete(id: Long) = guarded {
        if (unlocked()) dao().deleteById(id)
        alarms.cancel(id)
        notifications.dismiss(id)
        mirror.remove(id)
    }

    suspend fun onFire(id: Long, actual: Long) = guarded {
        if (unlocked()) {
            val reminder = dao().getById(id)
            if (reminder == null || reminder.isDone) {
                alarms.cancel(id)
                notifications.dismiss(id)
                mirror.remove(id)
                return@guarded
            }
            notifications.show(reminder.id, reminder.title, reminder.dueAt)
            scheduleAfterDelivery(
                reminder.id,
                reminder.dueAt,
                reminder.nagMinutes,
                reminder.title,
                actual,
            )
        } else {
            val entry = mirror.get(id)
            if (entry == null || entry.pendingDone) {
                alarms.cancel(id)
                notifications.dismiss(id)
                return@guarded
            }
            notifications.show(entry.id, entry.title, entry.dueAt)
            scheduleAfterDelivery(entry.id, entry.dueAt, entry.nagMinutes, entry.title, actual)
        }
    }

    suspend fun rescheduleAll() = guarded {
        check(unlocked()) { "Cannot reconcile from Room before unlock" }
        val now = System.currentTimeMillis()
        val pending = mirror.all().filter { it.pendingDone }
        for (entry in pending) {
            dao().markDone(entry.id, now)
            alarms.cancel(entry.id)
            notifications.dismiss(entry.id)
            mirror.remove(entry.id)
        }
        val active = dao().active()
        val activeIds = active.map { it.id }.toSet()
        val stale = mirror.all().filter { it.id !in activeIds }
        for (entry in stale) {
            alarms.cancel(entry.id)
            notifications.dismiss(entry.id)
            mirror.remove(entry.id)
        }
        for (reminder in active) {
            val entry = mirror.get(reminder.id)
            val next = ScheduleResolver.resolve(
                dueAt = reminder.dueAt,
                now = now,
                hasMirror = entry != null,
                mirroredNext = entry?.nextFireAt,
            )
            alarms.cancel(reminder.id)
            mirror.upsert(reminder.toMirror(nextFireAt = next))
            if (next != null) alarms.schedule(reminder.id, next)
        }
    }

    /** Direct-boot path. Does not open Room. */
    suspend fun rescheduleFromMirror() = guarded {
        val now = System.currentTimeMillis()
        for (entry in mirror.all()) {
            val nextFireAt = entry.nextFireAt
            if (entry.pendingDone || nextFireAt == null) {
                alarms.cancel(entry.id)
                continue
            }
            val whenAt = if (nextFireAt > now) nextFireAt else now
            if (whenAt != nextFireAt) {
                mirror.upsert(entry.copy(nextFireAt = whenAt))
            }
            alarms.cancel(entry.id)
            alarms.schedule(entry.id, whenAt)
        }
    }

    private fun scheduleAfterDelivery(
        id: Long,
        dueAt: Long,
        nagMinutes: Int?,
        title: String,
        actual: Long,
    ) {
        val next = NagSchedule.afterDelivery(dueAt, nagMinutes, actual)
        mirror.upsert(
            MirrorEntry(
                id = id,
                title = title,
                dueAt = dueAt,
                nagMinutes = nagMinutes,
                nextFireAt = next,
            ),
        )
        if (next == null) {
            alarms.cancel(id)
        } else {
            alarms.schedule(id, next)
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        gate.lock()
        try {
            return block()
        } finally {
            gate.unlock()
        }
    }

    private fun dao(): ReminderDao = daoProvider()
}
