package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.WeekStart
import com.evansappstudio.doapp.data.updateDetails
import com.evansappstudio.doapp.notify.ReminderNotifier
import kotlinx.coroutines.sync.Mutex
import java.time.ZoneId

/**
 * Arms, cancels and rebuilds the single alarm for every active reminder.
 * [rescheduleAll] is idempotent: it cancels, then sets, for each active reminder.
 * Callers share one mutex so a fire, a Done action and a reschedule cannot interleave.
 * The lock is held across Room suspensions on purpose.
 *
 * A repeating row stays active on Mark Done. The next due is one pattern step,
 * or one interval after completion, and the nag loop starts again from that due.
 */
class Scheduler(
    private val daoProvider: () -> ReminderDao,
    private val mirror: ReminderMirror,
    private val alarms: AlarmScheduler,
    private val notifications: ReminderNotifier,
    private val unlocked: () -> Boolean,
    private val zones: ZoneMemory,
    private val weekStart: () -> WeekStart,
) {
    private val gate = Mutex()

    suspend fun add(
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
    ): Long = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(ZoneId.systemDefault())
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "title is required" }
        val nag = NagSchedule.normalize(nagMinutes)
        val now = System.currentTimeMillis()
        val pattern = if (rule?.from == RepeatFrom.DUE_DATE) dueAt else null
        val id = dao().insert(
            Reminder(
                title = cleanTitle,
                dueAt = dueAt,
                nagMinutes = nag,
                isDone = false,
                doneAt = null,
                createdAt = now,
                alertLevel = alertLevel.name,
            ).withRepeat(rule, pattern),
        )
        val stored = dao().getById(id) ?: error("Inserted reminder $id was not readable")
        val next = NagSchedule.catchUp(stored.dueAt, now)
        mirror.upsert(stored.toMirror(nextFireAt = next))
        alarms.schedule(stored.id, next)
        id
    }

    suspend fun stopAlert(id: Long) = guarded {
        alarms.cancel(id)
        notifications.dismiss(id)
        AlertSession.attended(id)
        val now = System.currentTimeMillis()
        if (!unlocked()) {
            val entry = mirror.get(id) ?: return@guarded
            val next = nextAfterAttend(entry.nagMinutes, now)
            mirror.upsert(entry.copy(awaitingAttend = false, nextFireAt = next))
            if (next != null) alarms.schedule(id, next)
            DoAlarmLog.alertStop(id, next)
            return@guarded
        }
        alignRoomLocked(ZoneId.systemDefault())
        val reminder = dao().getById(id)
        if (reminder == null || reminder.isDone) {
            mirror.remove(id)
            DoAlarmLog.alertStop(id, null)
            return@guarded
        }
        val next = nextAfterAttend(reminder.nagMinutes, now)
        mirror.upsert(reminder.toMirror(nextFireAt = next))
        if (next != null) alarms.schedule(id, next)
        DoAlarmLog.alertStop(id, next)
    }

    suspend fun setAlertLevel(id: Long, level: AlertLevel) = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(ZoneId.systemDefault())
        val existing = dao().getById(id) ?: return@guarded
        if (existing.isDone) return@guarded
        val stored = existing.copy(alertLevel = level.name)
        if (stored.alertLevel != existing.alertLevel) {
            dao().updateDetails(stored)
            applyLevelOnly(stored)
        }
        DoAlarmLog.alert(id, level.name)
    }

    suspend fun markDone(id: Long) = guarded {
        alarms.cancel(id)
        notifications.dismiss(id)
        AlertSession.attended(id)
        if (!unlocked()) {
            val existing = mirror.get(id)
            val tappedAt = System.currentTimeMillis()
            mirror.upsert(
                (existing ?: MirrorEntry(
                    id = id,
                    title = "",
                    dueAt = 0L,
                    nagMinutes = null,
                    nextFireAt = null,
                )).copy(
                    pendingDone = true,
                    nextFireAt = null,
                    pendingDoneAt = existing?.pendingDoneAt ?: tappedAt,
                ),
            )
            return@guarded
        }
        alignRoomLocked(ZoneId.systemDefault())
        finishDoneLocked(id, System.currentTimeMillis())
    }

    /**
     * One-tap snooze. [option] is the choice baked into the action, not a fresh
     * settings read, so a lock-screen tap does not need credential storage.
     * Before unlock the new due is stored on the mirror and applied to Room
     * by [rescheduleAll]. A pending done wins over a pending snooze.
     * The repeat rule and pattern anchor are left alone.
     */
    suspend fun snooze(id: Long, optionIndex: Int, option: SnoozeOption) = guarded {
        applySnooze(id, optionIndex, option)
    }

    /** Applies [option] to every reminder notification currently showing. */
    suspend fun snoozeShowing(optionIndex: Int, option: SnoozeOption) = guarded {
        val ids = notifications.showingIds()
        for (id in ids) {
            applySnooze(id, optionIndex, option)
        }
    }

    /**
     * Edit save. [rule] null is Repeat Off. When the due time of a from-due-date
     * series changes, [dueShift] chooses this occurrence or all future ones.
     * A null [dueShift] on that path postpones this occurrence only.
     */
    suspend fun update(
        id: Long,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule?,
        dueShift: DueShiftMode? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
    ) = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(ZoneId.systemDefault())
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "title is required" }
        val nag = NagSchedule.normalize(nagMinutes)
        val existing = dao().getById(id) ?: return@guarded
        if (existing.isDone) return@guarded
        val zone = ZoneId.systemDefault()
        val dueChanged = dueAt != existing.dueAt
        val finalDue: Long
        val finalPattern: Long?
        val finalRule: RepeatRule?
        val dueDateRule = rule?.takeIf { it.from == RepeatFrom.DUE_DATE }
        if (dueDateRule != null && (dueChanged || dueShift == DueShiftMode.ALL)) {
            val placed = RepeatMath.applyDueShift(
                existingPattern = existing.patternDueAt,
                existingDue = existing.dueAt,
                draftRule = dueDateRule,
                newDue = dueAt,
                mode = dueShift ?: DueShiftMode.THIS,
                zone = zone,
            )
            finalDue = placed.dueAt
            finalPattern = placed.patternDueAt
            finalRule = placed.rule
        } else {
            finalDue = dueAt
            finalRule = rule
            val ruleChanged = rule != existing.repeatRule()
            finalPattern = when {
                rule == null -> null
                rule.from == RepeatFrom.COMPLETION -> null
                ruleChanged || existing.patternDueAt == null -> dueAt
                else -> existing.patternDueAt
            }
        }
        val stored = existing.copy(
            title = cleanTitle,
            dueAt = finalDue,
            nagMinutes = nag,
            alertLevel = alertLevel.name,
        ).withRepeat(finalRule, finalPattern)
        dao().updateDetails(stored)
        val nagChanged = nag != existing.nagMinutes
        val titleChanged = cleanTitle != existing.title
        val scheduleChanged = finalDue != existing.dueAt
        val levelChanged = stored.alertLevel != existing.alertLevel
        if (!scheduleChanged && !nagChanged && !titleChanged && !levelChanged && sameSeries(existing, stored)) {
            return@guarded
        }
        if (scheduleChanged) {
            armDue(stored, System.currentTimeMillis(), dismiss = true)
            return@guarded
        }
        if (nagChanged) {
            val now = System.currentTimeMillis()
            val next = SnoozeDue.nextFireAfterNagChange(stored.dueAt, nag, now)
            alarms.cancel(id)
            mirror.upsert(stored.toMirror(nextFireAt = next))
            if (next != null) alarms.schedule(id, next)
        } else if (levelChanged) {
            applyLevelOnly(stored)
        } else {
            val entry = mirror.get(id)
            if (entry != null) {
                mirror.upsert(
                    entry.copy(
                        title = cleanTitle,
                        nagMinutes = nag,
                        wallClock = stored.repeatRule()?.keepsWallClock() == true,
                        alertLevel = stored.alertLevel,
                    ),
                )
            }
        }
        if (titleChanged && !levelChanged) {
            notifications.refreshIfShowing(id, cleanTitle, stored.dueAt, stored.alert())
        }
    }

    /**
     * Debug SET_REPEAT. Does not move [Reminder.dueAt]. A new from-due-date
     * rule copies the current due into [Reminder.patternDueAt] when that
     * anchor is still null. Repeat Off clears the anchor.
     */
    suspend fun setRepeat(id: Long, rule: RepeatRule?) = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(ZoneId.systemDefault())
        val existing = dao().getById(id) ?: return@guarded
        if (existing.isDone) return@guarded
        val pattern = when {
            rule == null -> null
            rule.from == RepeatFrom.COMPLETION -> null
            existing.patternDueAt != null -> existing.patternDueAt
            else -> existing.dueAt
        }
        val stored = existing.withRepeat(rule, pattern)
        dao().updateDetails(stored)
        val entry = mirror.get(id)
        if (entry != null) {
            mirror.upsert(entry.copy(wallClock = rule?.keepsWallClock() == true))
        }
    }

    /** Debug SHIFT_DUE and the warning-icon apply. Returns the stored placement. */
    suspend fun shiftDue(id: Long, newDue: Long, mode: DueShiftMode): Reminder? = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(ZoneId.systemDefault())
        val existing = dao().getById(id) ?: return@guarded null
        if (existing.isDone) return@guarded null
        val rule = existing.repeatRule()
        val stored = if (rule?.from == RepeatFrom.DUE_DATE) {
            val placed = RepeatMath.applyDueShift(
                existingPattern = existing.patternDueAt,
                existingDue = existing.dueAt,
                draftRule = rule,
                newDue = newDue,
                mode = mode,
                zone = ZoneId.systemDefault(),
            )
            existing.copy(dueAt = placed.dueAt).withRepeat(placed.rule, placed.patternDueAt)
        } else {
            existing.copy(dueAt = newDue).withRepeat(rule, if (rule == null) null else existing.patternDueAt)
        }
        dao().updateDetails(stored)
        if (stored.dueAt != existing.dueAt || stored.patternDueAt != existing.patternDueAt) {
            armDue(stored, System.currentTimeMillis(), dismiss = true)
        }
        stored
    }

    suspend fun rebuildShowingNotifications() = guarded {
        notifications.rebuildShowing()
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
            val decision = notifications.show(reminder.id, reminder.title, reminder.dueAt, reminder.alert())
            logFallback(reminder.id, decision)
            if (decision.loopUntilAttended) {
                holdUntilAttended(
                    reminder.id,
                    reminder.dueAt,
                    reminder.nagMinutes,
                    reminder.title,
                    reminder.repeatRule()?.keepsWallClock() == true,
                    reminder.alertLevel,
                )
            } else {
                scheduleAfterDelivery(
                    reminder.id,
                    reminder.dueAt,
                    reminder.nagMinutes,
                    reminder.title,
                    actual,
                    reminder.repeatRule()?.keepsWallClock() == true,
                    reminder.alertLevel,
                )
            }
        } else {
            val entry = mirror.get(id)
            if (entry == null || entry.pendingDone) {
                alarms.cancel(id)
                notifications.dismiss(id)
                return@guarded
            }
            val level = AlertLevel.fromName(entry.alertLevel) ?: AlertLevel.STANDARD
            val decision = notifications.show(entry.id, entry.title, entry.dueAt, level)
            logFallback(entry.id, decision)
            if (decision.loopUntilAttended) {
                holdUntilAttended(entry.id, entry.dueAt, entry.nagMinutes, entry.title, entry.wallClock, level.name)
            } else {
                scheduleAfterDelivery(
                    entry.id,
                    entry.dueAt,
                    entry.nagMinutes,
                    entry.title,
                    actual,
                    entry.wallClock,
                    level.name,
                )
            }
        }
    }

    suspend fun rescheduleAll() = guarded {
        check(unlocked()) { "Cannot reconcile from Room before unlock" }
        alignRoomLocked(ZoneId.systemDefault())
        rescheduleAllLocked()
    }

    /**
     * Boot, clock and timezone broadcasts. Timezone changes rewrite calendar
     * repeats onto the same local wall time. TIME_SET does not.
     */
    suspend fun onExternalClock(zoneChanged: Boolean) = guarded {
        val open = unlocked()
        if (!open) {
            if (zoneChanged) alignMirrorLocked(ZoneId.systemDefault())
            rescheduleFromMirrorLocked()
            return@guarded
        }
        if (zoneChanged) alignRoomLocked(ZoneId.systemDefault())
        rescheduleAllLocked()
    }

    /** Direct-boot path. Does not open Room. */
    suspend fun rescheduleFromMirror() = guarded {
        rescheduleFromMirrorLocked()
    }

    private suspend fun rescheduleAllLocked() {
        val now = System.currentTimeMillis()
        val pending = mirror.all().filter { it.pendingDone }
        for (entry in pending) {
            finishDoneLocked(entry.id, entry.pendingDoneAt ?: now)
        }
        val pendingSnooze = mirror.all().filter { !it.pendingDone && it.pendingSnoozeDueAt != null }
        for (entry in pendingSnooze) {
            val due = entry.pendingSnoozeDueAt ?: continue
            val reminder = dao().getById(entry.id)
            if (reminder == null || reminder.isDone) continue
            dao().updateDueAt(reminder.id, due)
            val updated = reminder.copy(dueAt = due)
            mirror.upsert(
                updated.toMirror(
                    nextFireAt = entry.nextFireAt,
                    pendingSnoozeDueAt = null,
                ),
            )
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

    private fun rescheduleFromMirrorLocked() {
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

    private suspend fun finishDoneLocked(id: Long, completedAt: Long) {
        val reminder = dao().getById(id)
        if (reminder == null || reminder.isDone) {
            mirror.remove(id)
            return
        }
        val rule = reminder.repeatRule()
        if (rule == null) {
            dao().markDone(id, completedAt)
            mirror.remove(id)
            return
        }
        val placed = RepeatMath.step(
            dueAt = reminder.dueAt,
            patternDueAt = reminder.patternDueAt,
            rule = rule,
            completedAt = completedAt,
            zone = ZoneId.systemDefault(),
            weekStart = weekStart(),
        )
        dao().advanceOccurrence(id, placed.dueAt, placed.patternDueAt)
        val now = System.currentTimeMillis()
        val next = NagSchedule.catchUp(placed.dueAt, now)
        val stored = reminder.copy(
            dueAt = placed.dueAt,
            patternDueAt = placed.patternDueAt,
            isDone = false,
            doneAt = null,
        )
        mirror.upsert(stored.toMirror(nextFireAt = next))
        alarms.schedule(id, next)
        DoAlarmLog.repeatAdvance(id, placed.dueAt, placed.patternDueAt, rule.from.name)
    }

    private suspend fun applySnooze(id: Long, optionIndex: Int, option: SnoozeOption) {
        val now = System.currentTimeMillis()
        val newDue = SnoozeDue.compute(option, now, ZoneId.systemDefault())
        val nextFire = SnoozeDue.nextFire(newDue, now)
        DoAlarmLog.snooze(id, newDue, optionIndex)
        alarms.cancel(id)
        notifications.dismiss(id)
        AlertSession.attended(id)
        if (!unlocked()) {
            val existing = mirror.get(id)
            if (existing?.pendingDone == true) {
                DoAlarmLog.snoozeSkipped(id, "pending-done")
                return
            }
            val base = existing ?: MirrorEntry(
                id = id,
                title = "",
                dueAt = newDue,
                nagMinutes = null,
                nextFireAt = nextFire,
            )
            mirror.upsert(
                base.copy(
                    dueAt = newDue,
                    nextFireAt = nextFire,
                    pendingDone = false,
                    pendingSnoozeDueAt = newDue,
                ),
            )
            alarms.schedule(id, nextFire)
            return
        }
        alignRoomLocked(ZoneId.systemDefault())
        val reminder = dao().getById(id)
        if (reminder == null || reminder.isDone) {
            mirror.remove(id)
            DoAlarmLog.snoozeSkipped(id, "inactive")
            return
        }
        dao().updateDueAt(id, newDue)
        mirror.upsert(reminder.copy(dueAt = newDue).toMirror(nextFireAt = nextFire))
        alarms.schedule(id, nextFire)
    }

    private fun scheduleAfterDelivery(
        id: Long,
        dueAt: Long,
        nagMinutes: Int?,
        title: String,
        actual: Long,
        wallClock: Boolean,
        alertLevel: String,
    ) {
        val next = NagSchedule.afterDelivery(dueAt, nagMinutes, actual)
        val existing = mirror.get(id)
        mirror.upsert(
            MirrorEntry(
                id = id,
                title = title,
                dueAt = dueAt,
                nagMinutes = nagMinutes,
                nextFireAt = next,
                pendingDone = existing?.pendingDone ?: false,
                pendingSnoozeDueAt = existing?.pendingSnoozeDueAt,
                pendingDoneAt = existing?.pendingDoneAt,
                wallClock = wallClock,
                alertLevel = alertLevel,
                awaitingAttend = false,
            ),
        )
        if (next == null) {
            alarms.cancel(id)
        } else {
            alarms.schedule(id, next)
        }
    }

    private fun holdUntilAttended(
        id: Long,
        dueAt: Long,
        nagMinutes: Int?,
        title: String,
        wallClock: Boolean,
        alertLevel: String,
    ) {
        alarms.cancel(id)
        val existing = mirror.get(id)
        mirror.upsert(
            MirrorEntry(
                id = id,
                title = title,
                dueAt = dueAt,
                nagMinutes = nagMinutes,
                nextFireAt = null,
                pendingDone = existing?.pendingDone ?: false,
                pendingSnoozeDueAt = existing?.pendingSnoozeDueAt,
                pendingDoneAt = existing?.pendingDoneAt,
                wallClock = wallClock,
                alertLevel = alertLevel,
                awaitingAttend = true,
            ),
        )
    }

    private fun applyLevelOnly(stored: Reminder) {
        val now = System.currentTimeMillis()
        val decision = notifications.decide(stored.alert())
        val entry = mirror.get(stored.id)
        if (entry?.awaitingAttend == true && !decision.loopUntilAttended) {
            AlertSession.attended(stored.id)
            notifications.dismiss(stored.id)
            val next = nextAfterAttend(stored.nagMinutes, now)
            mirror.upsert(stored.toMirror(nextFireAt = next))
            alarms.cancel(stored.id)
            if (next != null) alarms.schedule(stored.id, next)
            return
        }
        if (entry != null) {
            mirror.upsert(
                entry.copy(
                    title = stored.title,
                    nagMinutes = stored.nagMinutes,
                    alertLevel = stored.alertLevel,
                    wallClock = stored.repeatRule()?.keepsWallClock() == true,
                ),
            )
        }
        notifications.refreshIfShowing(stored.id, stored.title, stored.dueAt, stored.alert())
    }

    private fun nextAfterAttend(nagMinutes: Int?, now: Long): Long? {
        val nag = NagSchedule.normalize(nagMinutes) ?: return null
        return now + NagSchedule.intervalMillis(nag)
    }

    private fun logFallback(id: Long, decision: AlertDecision) {
        decision.reasons.forEach { reason ->
            DoAlarmLog.alertFallback(id, decision.requested.name, decision.effective.name, reason.name)
        }
    }

    private fun armDue(reminder: Reminder, now: Long, dismiss: Boolean) {
        if (dismiss) {
            alarms.cancel(reminder.id)
            notifications.dismiss(reminder.id)
            AlertSession.attended(reminder.id)
        }
        val next = SnoozeDue.nextFire(reminder.dueAt, now)
        mirror.upsert(reminder.toMirror(nextFireAt = next))
        alarms.schedule(reminder.id, next)
    }

    private suspend fun alignRoomLocked(device: ZoneId) {
        val stored = zones.roomZone()
        if (stored == null || stored == device) {
            zones.setRoomZone(device)
            zones.setMirrorZone(device)
            return
        }
        val rows = dao().active()
        for (row in rows) {
            val rule = row.repeatRule() ?: continue
            if (!rule.keepsWallClock()) continue
            val newDue = RepeatMath.adjustZone(row.dueAt, rule, stored, device)
            val newPattern = row.patternDueAt?.let { RepeatMath.adjustZone(it, rule, stored, device) }
            if (newDue != row.dueAt || newPattern != row.patternDueAt) {
                dao().updateDueAndPattern(row.id, newDue, newPattern)
            }
        }
        zones.setRoomZone(device)
        zones.setMirrorZone(device)
    }

    private fun alignMirrorLocked(device: ZoneId) {
        val stored = zones.mirrorZone() ?: zones.roomZone()
        if (stored == null || stored == device) {
            zones.setMirrorZone(device)
            return
        }
        for (entry in mirror.all()) {
            if (!entry.wallClock) continue
            val newDue = RepeatMath.sameWallTime(entry.dueAt, stored, device)
            val delta = newDue - entry.dueAt
            val newNext = entry.nextFireAt?.let { it + delta }
            val newSnooze = entry.pendingSnoozeDueAt?.let { RepeatMath.sameWallTime(it, stored, device) }
            if (newDue != entry.dueAt || newNext != entry.nextFireAt || newSnooze != entry.pendingSnoozeDueAt) {
                mirror.upsert(
                    entry.copy(
                        dueAt = newDue,
                        nextFireAt = newNext,
                        pendingSnoozeDueAt = newSnooze,
                    ),
                )
            }
        }
        zones.setMirrorZone(device)
    }

    private fun sameSeries(before: Reminder, after: Reminder): Boolean {
        return before.repeatRule() == after.repeatRule() && before.patternDueAt == after.patternDueAt
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
