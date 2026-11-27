package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.ReminderEvent
import com.evansappstudio.doapp.data.ReminderEventDao
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.WeekStart
import com.evansappstudio.doapp.data.updateDetails
import kotlinx.coroutines.sync.Mutex
import java.time.ZoneId
import java.util.UUID

/** What an editor save did. */
enum class EditResult {
    SAVED,
    UNCHANGED,
}

/**
 * Arms, cancels and rebuilds the single alarm for every active reminder.
 * [rescheduleAll] is idempotent: it cancels, then sets, for each active reminder.
 * Callers share one mutex so a fire, a Done action and a reschedule cannot interleave.
 * The lock is held across Room suspensions on purpose.
 *
 * A repeating row stays active on Mark Done. The next due is one pattern step,
 * or one interval after completion, and the nag loop starts again from that due.
 *
 * The mutators are internal: callers go through [ReminderCommands]. Each one writes
 * the reminder change (updatedAt, version + 1) and its [ReminderEvent] inside the
 * same guarded block and the same [inTransaction] call, before any alarm or mirror
 * side effect. Fires write an event but never bump version: a fire changes nothing
 * in Room. Before unlock Room is closed, so mirror-only paths write no event; a
 * pending Done or snooze is written with [Actor.SYSTEM] when [rescheduleAll] applies it.
 */
class Scheduler(
    private val daoProvider: () -> ReminderDao,
    private val eventsProvider: () -> ReminderEventDao,
    private val mirror: ReminderMirror,
    private val alarms: AlarmArm,
    private val notifications: ReminderAlerts,
    private val unlocked: () -> Boolean,
    private val zones: ZoneMemory,
    private val weekStart: () -> WeekStart,
    private val paidUnlocked: () -> Boolean,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val onCommitted: () -> Unit,
    private val quietConfig: () -> QuietConfig,
    private val dndActive: () -> Boolean,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val gate = Mutex()

    private companion object {
        /**
         * Fires, zone alignment, and a Done or snooze tapped before unlock and applied
         * from the mirror later (who tapped is not recorded; detail says deferred=1).
         */
        val SYSTEM = Origin(Actor.SYSTEM)
    }

    internal suspend fun add(
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
        origin: Origin = Origin(Actor.USER),
        category: String? = null,
        templateId: String? = null,
        templateVersion: Int? = null,
    ): Long? = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "title is required" }
        val active = dao().activeCount()
        if (FreeTier.blocksAdd(active, paidUnlocked())) {
            DoAlarmLog.unlockBlocked(active, FreeTier.capLabel())
            return@guarded null
        }
        val nag = NagSchedule.normalize(nagMinutes)
        val now = clock()
        val pattern = if (rule?.from == RepeatFrom.DUE_DATE) dueAt else null
        val row = Reminder(
            uuid = UUID.randomUUID().toString(),
            title = cleanTitle,
            dueAt = dueAt,
            nagMinutes = nag,
            isDone = false,
            doneAt = null,
            createdAt = now,
            alertLevel = alertLevel.name,
            source = origin.source.name,
            sourceId = origin.cleanSourceId(),
            category = ReminderCategory.normalize(category),
            templateId = Origin.clipSourceId(templateId),
            templateVersion = templateVersion,
            updatedAt = now,
            version = 1,
        ).withRepeat(rule, pattern)
        val id = tx {
            val inserted = dao().insert(row)
            record(row.uuid, EventType.CREATED, origin, describe(row))
            inserted
        }
        val stored = dao().getById(id) ?: error("Inserted reminder $id was not readable")
        val next = NagSchedule.catchUp(stored.dueAt, now)
        mirror.upsert(stored.toMirror(nextFireAt = next))
        alarms.schedule(stored.id, next)
        changed()
        id
    }

    internal suspend fun stopAlert(id: Long, origin: Origin = Origin(Actor.USER)) = guarded {
        alarms.cancel(id)
        notifications.dismiss(id)
        AlertSession.attended(id)
        val now = clock()
        if (!unlocked()) {
            val entry = mirror.get(id) ?: return@guarded
            val next = deferIfNag(id, entry.dueAt, nextAfterAttend(entry.nagMinutes, now), entry.alertLevel)
            mirror.upsert(entry.copy(awaitingAttend = false, nextFireAt = next, dndHeld = false))
            if (next != null) alarms.schedule(id, next)
            DoAlarmLog.alertStop(id, next)
            return@guarded
        }
        alignRoomLocked(zone())
        val reminder = liveById(id)
        if (reminder == null || reminder.isDone) {
            mirror.remove(id)
            DoAlarmLog.alertStop(id, null)
            return@guarded
        }
        val next = deferIfNag(id, reminder.dueAt, nextAfterAttend(reminder.nagMinutes, now), reminder.alertLevel)
        tx {
            if (dao().touch(id, now) > 0) {
                record(reminder.uuid, EventType.ALERT_STOPPED, origin, EventLog.detail("next" to next))
            }
        }
        mirror.upsert(reminder.toMirror(nextFireAt = next))
        if (next != null) alarms.schedule(id, next)
        DoAlarmLog.alertStop(id, next)
    }

    internal suspend fun setAlertLevel(id: Long, level: AlertLevel, origin: Origin = Origin(Actor.USER)) = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        val existing = liveById(id) ?: return@guarded
        if (existing.isDone) return@guarded
        val stored = existing.copy(alertLevel = level.name)
        if (stored.alertLevel != existing.alertLevel) {
            tx {
                if (dao().updateDetails(stored, clock()) > 0) {
                    record(
                        existing.uuid,
                        EventType.LEVEL_CHANGED,
                        origin,
                        EventLog.detail("from" to existing.alertLevel, "to" to stored.alertLevel),
                    )
                }
            }
            applyLevelOnly(stored)
            changed()
        }
        DoAlarmLog.alert(id, level.name)
    }

    internal suspend fun markDone(id: Long, origin: Origin = Origin(Actor.USER)) = guarded {
        alarms.cancel(id)
        notifications.dismiss(id)
        AlertSession.attended(id)
        if (!unlocked()) {
            val existing = mirror.get(id)
            val tappedAt = clock()
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
                    dndHeld = false,
                ),
            )
            changed()
            return@guarded
        }
        alignRoomLocked(zone())
        finishDoneLocked(id, clock(), origin)
        changed()
    }

    /**
     * One-tap snooze. [option] is the choice baked into the action, not a fresh
     * settings read, so a lock-screen tap does not need credential storage.
     * Before unlock the new due is stored on the mirror and applied to Room
     * by [rescheduleAll]. A pending done wins over a pending snooze.
     * The repeat rule and pattern anchor are left alone.
     */
    internal suspend fun snooze(
        id: Long,
        optionIndex: Int,
        option: SnoozeOption,
        origin: Origin = Origin(Actor.USER),
    ) = guarded {
        applySnooze(id, optionIndex, option, origin)
        changed()
    }

    /** Applies [option] to every reminder notification currently showing. One event per reminder. */
    internal suspend fun snoozeShowing(
        optionIndex: Int,
        option: SnoozeOption,
        origin: Origin = Origin(Actor.USER),
    ) = guarded {
        val ids = notifications.showingIds()
        for (id in ids) {
            applySnooze(id, optionIndex, option, origin)
        }
        changed()
    }

    /**
     * Edit save. [rule] null is Repeat Off. When the due time of a from-due-date
     * series changes, [dueShift] chooses this occurrence or all future ones.
     * A null [dueShift] on that path postpones this occurrence only.
     */
    internal suspend fun update(
        id: Long,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule?,
        dueShift: DueShiftMode? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
        origin: Origin = Origin(Actor.USER),
    ): EditResult = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "title is required" }
        val nag = NagSchedule.normalize(nagMinutes)
        val existing = liveById(id) ?: return@guarded EditResult.UNCHANGED
        if (existing.isDone) return@guarded EditResult.UNCHANGED
        val deviceZone = zone()
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
                zone = deviceZone,
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
        val nagChanged = nag != existing.nagMinutes
        val titleChanged = cleanTitle != existing.title
        val scheduleChanged = finalDue != existing.dueAt
        val levelChanged = stored.alertLevel != existing.alertLevel
        if (!scheduleChanged && !nagChanged && !titleChanged && !levelChanged && sameSeries(existing, stored)) {
            return@guarded EditResult.UNCHANGED
        }
        tx {
            if (dao().updateDetails(stored, clock()) > 0) {
                record(existing.uuid, EventType.EDITED, origin, describe(stored))
            }
        }
        if (scheduleChanged) {
            armDue(stored, clock(), dismiss = true)
            return@guarded EditResult.SAVED
        }
        if (nagChanged) {
            val now = clock()
            val next = deferIfNag(
                id,
                stored.dueAt,
                SnoozeDue.nextFireAfterNagChange(stored.dueAt, nag, now),
                stored.alertLevel,
            )
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
        changed()
        EditResult.SAVED
    }

    /**
     * Debug SET_REPEAT. Does not move [Reminder.dueAt]. A new from-due-date
     * rule copies the current due into [Reminder.patternDueAt] when that
     * anchor is still null. Repeat Off clears the anchor.
     */
    internal suspend fun setRepeat(id: Long, rule: RepeatRule?, origin: Origin = Origin(Actor.USER)) = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        val existing = liveById(id) ?: return@guarded
        if (existing.isDone) return@guarded
        val pattern = when {
            rule == null -> null
            rule.from == RepeatFrom.COMPLETION -> null
            existing.patternDueAt != null -> existing.patternDueAt
            else -> existing.dueAt
        }
        val stored = existing.withRepeat(rule, pattern)
        if (!sameSeries(existing, stored)) {
            tx {
                if (dao().updateDetails(stored, clock()) > 0) {
                    record(
                        existing.uuid,
                        EventType.REPEAT_CHANGED,
                        origin,
                        EventLog.detail("repeat" to (rule?.token() ?: "OFF"), "pattern" to stored.patternDueAt),
                    )
                }
            }
        }
        val entry = mirror.get(id)
        if (entry != null) {
            mirror.upsert(entry.copy(wallClock = rule?.keepsWallClock() == true))
        }
        changed()
    }

    /** Debug SHIFT_DUE and the warning-icon apply. Returns the stored placement. */
    internal suspend fun shiftDue(
        id: Long,
        newDue: Long,
        mode: DueShiftMode,
        origin: Origin = Origin(Actor.USER),
    ): Reminder? = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        val existing = liveById(id) ?: return@guarded null
        if (existing.isDone) return@guarded null
        val rule = existing.repeatRule()
        val stored = if (rule?.from == RepeatFrom.DUE_DATE) {
            val placed = RepeatMath.applyDueShift(
                existingPattern = existing.patternDueAt,
                existingDue = existing.dueAt,
                draftRule = rule,
                newDue = newDue,
                mode = mode,
                zone = zone(),
            )
            existing.copy(dueAt = placed.dueAt).withRepeat(placed.rule, placed.patternDueAt)
        } else {
            existing.copy(dueAt = newDue).withRepeat(rule, if (rule == null) null else existing.patternDueAt)
        }
        val moved = stored.dueAt != existing.dueAt || stored.patternDueAt != existing.patternDueAt
        if (moved || !sameSeries(existing, stored)) {
            tx {
                if (dao().updateDetails(stored, clock()) > 0) {
                    record(
                        existing.uuid,
                        EventType.EDITED,
                        origin,
                        EventLog.detail("shift" to mode.name, "due" to stored.dueAt, "pattern" to stored.patternDueAt),
                    )
                }
            }
        }
        if (moved) {
            armDue(stored, clock(), dismiss = true)
        }
        changed()
        liveById(id) ?: stored
    }

    suspend fun rebuildShowingNotifications() = guarded {
        notifications.rebuildShowing()
    }

    /**
     * Soft delete: sets deletedAt and keeps the row for [SoftDelete.PURGE_AFTER_DAYS]
     * days. The alarm, the notification and the mirror entry go, as hard delete did.
     */
    internal suspend fun delete(id: Long, origin: Origin = Origin(Actor.USER)) = guarded {
        if (unlocked()) {
            val existing = liveById(id)
            if (existing != null) {
                tx {
                    if (dao().softDelete(id, clock()) > 0) {
                        record(existing.uuid, EventType.DELETED, origin)
                    }
                }
            }
        }
        alarms.cancel(id)
        notifications.dismiss(id)
        mirror.remove(id)
        changed()
    }

    /**
     * Clears deletedAt and re-arms a not-done row through the add path (a past due
     * catches up). False when the row is missing, not deleted, or past the purge window.
     */
    internal suspend fun restore(id: Long, origin: Origin = Origin(Actor.USER)): Boolean = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        val existing = dao().getById(id) ?: return@guarded false
        val deletedAt = existing.deletedAt ?: return@guarded false
        val now = clock()
        if (!SoftDelete.canRestore(deletedAt, now)) return@guarded false
        val restored = tx {
            val count = dao().restore(id, now)
            if (count > 0) record(existing.uuid, EventType.RESTORED, origin)
            count > 0
        }
        if (!restored) return@guarded false
        val stored = dao().getById(id) ?: return@guarded false
        if (!stored.isDone) {
            val next = NagSchedule.catchUp(stored.dueAt, now)
            alarms.cancel(id)
            mirror.upsert(stored.toMirror(nextFireAt = next))
            alarms.schedule(id, next)
        }
        changed()
        true
    }

    /**
     * Removes rows soft-deleted more than [days] days ago, with their events.
     * Runs on every [rescheduleAll]. Returns how many rows went.
     */
    suspend fun purgeDeleted(days: Int = SoftDelete.PURGE_AFTER_DAYS): Int = guarded {
        check(unlocked()) { "Cannot purge before unlock" }
        purgeLocked(days)
    }

    /** Debug title edit. Keeps the uuid and the local id. */
    internal suspend fun setTitle(id: Long, title: String, origin: Origin = Origin(Actor.USER)): Reminder? = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        val clean = title.trim()
        if (clean.isEmpty()) return@guarded null
        val existing = liveById(id) ?: return@guarded null
        if (existing.isDone) return@guarded null
        val stored = existing.copy(title = clean)
        if (clean != existing.title) {
            tx {
                if (dao().updateDetails(stored, clock()) > 0) {
                    record(existing.uuid, EventType.EDITED, origin, EventLog.detail("field" to "title"))
                }
            }
        }
        val entry = mirror.get(id)
        if (entry != null) mirror.upsert(entry.copy(title = clean))
        notifications.refreshIfShowing(id, clean, stored.dueAt, stored.alert())
        changed()
        liveById(id) ?: stored
    }

    /**
     * Replace every reminder. Clears every row, soft-deleted ones included, and
     * every event, then writes one IMPORTED event per row. Imported rows keep
     * their exported version and updatedAt. Reschedules afterwards.
     */
    internal suspend fun replaceAll(
        reminders: List<Reminder>,
        origin: Origin = Origin(Actor.IMPORT),
    ): List<Long> = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        for (row in dao().allIncludingDeleted()) {
            alarms.cancel(row.id)
            notifications.dismiss(row.id)
            mirror.remove(row.id)
        }
        tx {
            events().deleteAll()
            dao().deleteAll()
            for (row in reminders) {
                val incoming = imported(row)
                dao().insert(incoming)
                record(incoming.uuid, EventType.IMPORTED, origin, EventLog.detail("mode" to "replace"))
            }
        }
        rescheduleAllLocked()
        reconcileQuietLocked()
        changed()
        dao().all().map { it.id }
    }

    /**
     * Upsert by uuid. Local-only rows stay. Imported fields win. A match on a
     * soft-deleted local row restores it with the incoming values.
     */
    internal suspend fun mergeAll(
        reminders: List<Reminder>,
        origin: Origin = Origin(Actor.IMPORT),
    ): List<Long> = guarded {
        check(unlocked()) { "Cannot save a reminder before unlock" }
        alignRoomLocked(zone())
        val ids = ArrayList<Long>()
        tx {
            for (row in reminders) {
                val incoming = imported(row)
                val local = dao().getByUuid(incoming.uuid)
                if (local == null) {
                    ids += dao().insert(incoming)
                    record(incoming.uuid, EventType.IMPORTED, origin, EventLog.detail("mode" to "merge"))
                } else {
                    dao().update(incoming.copy(id = local.id))
                    ids += local.id
                    val restored = if (local.deletedAt != null) 1 else null
                    record(
                        incoming.uuid,
                        EventType.IMPORTED,
                        origin,
                        EventLog.detail("mode" to "merge", "restored" to restored),
                    )
                }
            }
        }
        rescheduleAllLocked()
        reconcileQuietLocked()
        changed()
        ids
    }

    suspend fun onFire(id: Long, actual: Long, scheduled: Long = actual) = guarded {
        if (unlocked()) {
            val reminder = liveById(id)
            if (reminder == null || reminder.isDone) {
                alarms.cancel(id)
                notifications.dismiss(id)
                mirror.remove(id)
                return@guarded
            }
            val wasNag = mirror.get(id)?.pendingNag() == true
            if (holdNagIfNeeded(
                    id = reminder.id,
                    dueAt = reminder.dueAt,
                    nagMinutes = reminder.nagMinutes,
                    title = reminder.title,
                    wallClock = reminder.repeatRule()?.keepsWallClock() == true,
                    alertLevel = reminder.alertLevel,
                    scheduled = scheduled,
                    actual = actual,
                )
            ) {
                recordFire(reminder, EventType.HELD, EventLog.detail("reason" to "dnd", "next" to mirror.get(id)?.nextFireAt))
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
            recordFire(
                reminder,
                if (wasNag) EventType.NAGGED else EventType.FIRED,
                EventLog.detail("scheduled" to scheduled, "level" to decision.effective.name),
            )
        } else {
            val entry = mirror.get(id)
            if (entry == null || entry.pendingDone) {
                alarms.cancel(id)
                notifications.dismiss(id)
                return@guarded
            }
            if (holdNagIfNeeded(
                    id = entry.id,
                    dueAt = entry.dueAt,
                    nagMinutes = entry.nagMinutes,
                    title = entry.title,
                    wallClock = entry.wallClock,
                    alertLevel = entry.alertLevel,
                    scheduled = scheduled,
                    actual = actual,
                )
            ) {
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

    /** Re-places pending nags after a quiet-hours or Do Not Disturb change. */
    suspend fun reconcileQuietHours() = guarded {
        reconcileQuietLocked()
    }

    suspend fun rescheduleAll() = guarded {
        check(unlocked()) { "Cannot reconcile from Room before unlock" }
        alignRoomLocked(zone())
        rescheduleAllLocked()
    }

    /**
     * Boot, clock and timezone broadcasts. Timezone changes rewrite calendar
     * repeats onto the same local wall time. TIME_SET does not.
     */
    suspend fun onExternalClock(zoneChanged: Boolean) = guarded {
        val open = unlocked()
        if (!open) {
            if (zoneChanged) alignMirrorLocked(zone())
            rescheduleFromMirrorLocked()
        } else {
            if (zoneChanged) alignRoomLocked(zone())
            rescheduleAllLocked()
        }
        reconcileQuietLocked()
    }

    /** Direct-boot path. Does not open Room. */
    suspend fun rescheduleFromMirror() = guarded {
        rescheduleFromMirrorLocked()
    }

    private suspend fun rescheduleAllLocked() {
        val now = clock()
        purgeLocked(SoftDelete.PURGE_AFTER_DAYS)
        val pending = mirror.all().filter { it.pendingDone }
        for (entry in pending) {
            finishDoneLocked(entry.id, entry.pendingDoneAt ?: now, SYSTEM, deferred = true)
        }
        val pendingSnooze = mirror.all().filter { !it.pendingDone && it.pendingSnoozeDueAt != null }
        for (entry in pendingSnooze) {
            val due = entry.pendingSnoozeDueAt ?: continue
            val reminder = liveById(entry.id)
            if (reminder == null || reminder.isDone) continue
            tx {
                if (dao().updateDueAt(reminder.id, due, now) > 0) {
                    record(reminder.uuid, EventType.SNOOZED, SYSTEM, EventLog.detail("until" to due, "deferred" to 1))
                }
            }
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
            val resolved = ScheduleResolver.resolve(
                dueAt = reminder.dueAt,
                now = now,
                hasMirror = entry != null,
                mirroredNext = entry?.nextFireAt,
            )
            val isNag = entry?.pendingNag() == true
            val next = if (isNag) {
                deferIfNag(reminder.id, reminder.dueAt, resolved, reminder.alertLevel)
            } else {
                resolved
            }
            alarms.cancel(reminder.id)
            val stillHeld = entry != null && isNag && entry.dndHeld
            mirror.upsert(reminder.toMirror(nextFireAt = next, dndHeld = stillHeld))
            if (next != null) alarms.schedule(reminder.id, next)
        }
    }

    private fun rescheduleFromMirrorLocked() {
        val now = clock()
        for (entry in mirror.all()) {
            val nextFireAt = entry.nextFireAt
            if (entry.pendingDone || nextFireAt == null) {
                alarms.cancel(entry.id)
                continue
            }
            val isNag = entry.pendingNag()
            val caughtUp = if (nextFireAt > now) nextFireAt else now
            val whenAt = if (isNag) {
                deferIfNag(entry.id, entry.dueAt, caughtUp, entry.alertLevel) ?: caughtUp
            } else {
                caughtUp
            }
            // A missed first fire stays nextFireAt == dueAt so the catch-up still alerts.
            if (isNag && whenAt != nextFireAt) {
                mirror.upsert(entry.copy(nextFireAt = whenAt))
            }
            alarms.cancel(entry.id)
            alarms.schedule(entry.id, whenAt)
        }
    }

    private suspend fun finishDoneLocked(id: Long, completedAt: Long, origin: Origin, deferred: Boolean = false) {
        val reminder = liveById(id)
        if (reminder == null || reminder.isDone) {
            mirror.remove(id)
            return
        }
        val flag = if (deferred) 1 else null
        val rule = reminder.repeatRule()
        if (rule == null) {
            tx {
                if (dao().markDone(id, completedAt, clock()) > 0) {
                    record(reminder.uuid, EventType.DONE, origin, EventLog.detail("at" to completedAt, "deferred" to flag))
                }
            }
            mirror.remove(id)
            return
        }
        val placed = RepeatMath.step(
            dueAt = reminder.dueAt,
            patternDueAt = reminder.patternDueAt,
            rule = rule,
            completedAt = completedAt,
            zone = zone(),
            weekStart = weekStart(),
        )
        tx {
            if (dao().advanceOccurrence(id, placed.dueAt, placed.patternDueAt, clock()) > 0) {
                record(reminder.uuid, EventType.DONE, origin, EventLog.detail("at" to completedAt, "deferred" to flag))
                record(
                    reminder.uuid,
                    EventType.ADVANCED,
                    origin,
                    EventLog.detail("due" to placed.dueAt, "pattern" to placed.patternDueAt, "from" to rule.from.name),
                )
            }
        }
        val now = clock()
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

    private suspend fun applySnooze(id: Long, optionIndex: Int, option: SnoozeOption, origin: Origin) {
        val now = clock()
        val newDue = SnoozeDue.compute(option, now, zone())
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
                    dndHeld = false,
                ),
            )
            alarms.schedule(id, nextFire)
            return
        }
        alignRoomLocked(zone())
        val reminder = liveById(id)
        if (reminder == null || reminder.isDone) {
            mirror.remove(id)
            DoAlarmLog.snoozeSkipped(id, "inactive")
            return
        }
        tx {
            if (dao().updateDueAt(id, newDue, now) > 0) {
                record(reminder.uuid, EventType.SNOOZED, origin, EventLog.detail("option" to optionIndex, "until" to newDue))
            }
        }
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
        val next = deferIfNag(id, dueAt, NagSchedule.afterDelivery(dueAt, nagMinutes, actual), alertLevel)
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
        val now = clock()
        val decision = notifications.decide(stored.alert())
        val entry = mirror.get(stored.id)
        if (entry?.awaitingAttend == true && !decision.loopUntilAttended) {
            AlertSession.attended(stored.id)
            notifications.dismiss(stored.id)
            val next = deferIfNag(stored.id, stored.dueAt, nextAfterAttend(stored.nagMinutes, now), stored.alertLevel)
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

    private fun reconcileQuietLocked() {
        val config = quietConfig()
        val active = dndActive()
        val deviceZone = zone()
        val now = clock()
        for (entry in mirror.all()) {
            if (entry.pendingDone || entry.awaitingAttend || !entry.pendingNag()) continue
            val minutes = NagSchedule.normalize(entry.nagMinutes) ?: continue
            val level = AlertLevel.fromName(entry.alertLevel) ?: AlertLevel.STANDARD
            val stored = entry.nextFireAt ?: continue
            if (entry.dndHeld && QuietHours.holdForDnd(level, config, active)) continue
            val raw: Long
            val held = false
            if (entry.dndHeld) {
                raw = now
                DoAlarmLog.dndRelease(entry.id, now)
            } else {
                raw = NagSchedule.nextAnchorStrictlyAfter(entry.dueAt, minutes, now)
            }
            val placed = if (raw <= entry.dueAt) raw else deferNag(entry.id, raw, level, stored)
            if (placed == stored && held == entry.dndHeld) continue
            if (placed != stored) {
                alarms.cancel(entry.id)
                alarms.schedule(entry.id, placed)
            }
            mirror.upsert(entry.copy(nextFireAt = placed, dndHeld = held))
        }
    }

    /**
     * A nag whose stored next fire is after the due. First fires (next == due,
     * including a catch-up that kept that marker) always alert.
     */
    private fun holdNagIfNeeded(
        id: Long,
        dueAt: Long,
        nagMinutes: Int?,
        title: String,
        wallClock: Boolean,
        alertLevel: String,
        scheduled: Long,
        actual: Long,
    ): Boolean {
        val entry = mirror.get(id) ?: return false
        if (!entry.pendingNag()) return false
        val level = AlertLevel.fromName(alertLevel) ?: AlertLevel.STANDARD
        if (!QuietHours.holdForDnd(level, quietConfig(), dndActive())) return false
        val minutes = NagSchedule.normalize(nagMinutes) ?: return false
        val raw = NagSchedule.nextAnchorStrictlyAfter(dueAt, minutes, actual)
        val next = deferNag(id, raw, level)
        alarms.cancel(id)
        mirror.upsert(
            (mirror.get(id) ?: entry).copy(
                title = title,
                dueAt = dueAt,
                nagMinutes = nagMinutes,
                nextFireAt = next,
                wallClock = wallClock,
                alertLevel = alertLevel,
                awaitingAttend = false,
                dndHeld = true,
            ),
        )
        alarms.schedule(id, next)
        DoAlarmLog.dndHold(id, scheduled, next)
        return true
    }

    private fun deferIfNag(id: Long, dueAt: Long, planned: Long?, levelName: String): Long? {
        if (planned == null || planned <= dueAt) return planned
        val level = AlertLevel.fromName(levelName) ?: AlertLevel.STANDARD
        return deferNag(id, planned, level)
    }

    private fun deferNag(id: Long, planned: Long, level: AlertLevel, previous: Long? = null): Long {
        val placed = QuietHours.placeNag(planned, level, quietConfig(), zone())
        if (placed != planned && placed != previous) DoAlarmLog.quietDefer(id, planned, placed)
        return placed
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
                tx {
                    if (dao().updateDueAndPattern(row.id, newDue, newPattern, clock()) > 0) {
                        record(
                            row.uuid,
                            EventType.EDITED,
                            SYSTEM,
                            EventLog.detail("zone" to device.id, "due" to newDue, "pattern" to newPattern),
                        )
                    }
                }
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

    private fun events(): ReminderEventDao = eventsProvider()

    /** A row that has not been soft-deleted. A deleted row is treated as gone. */
    private suspend fun liveById(id: Long): Reminder? = dao().getById(id)?.takeIf { it.deletedAt == null }

    /** Runs [block] in one Room transaction and returns its result. */
    private suspend fun <T> tx(block: suspend () -> T): T {
        var result: Any? = null
        inTransaction { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Appends one event and trims the reminder's log. Call inside [tx], beside the reminder write. */
    private suspend fun record(uuid: String, type: EventType, origin: Origin, detail: String? = null) {
        events().insert(
            ReminderEvent(
                reminderUuid = uuid,
                at = clock(),
                type = type.name,
                actor = origin.actor.name,
                sourceId = origin.cleanSourceId(),
                detail = EventLog.clipDetail(detail),
            ),
        )
        events().trim(uuid, EventLog.MAX_PER_REMINDER)
    }

    /**
     * A fire changes nothing in Room, so it does not bump version. It is written
     * after the alarm work so a failed event write cannot stop the nag loop.
     */
    private suspend fun recordFire(reminder: Reminder, type: EventType, detail: String?) {
        try {
            tx { record(reminder.uuid, type, SYSTEM, detail) }
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(reminder.id, error)
        }
    }

    private suspend fun purgeLocked(days: Int): Int {
        val cutoff = SoftDelete.cutoff(clock(), days)
        val doomed = dao().deletedBefore(cutoff)
        if (doomed.isEmpty()) return 0
        val removed = tx {
            events().deleteForReminders(doomed.map { it.uuid })
            dao().purgeDeletedBefore(cutoff)
        }
        for (row in doomed) {
            alarms.cancel(row.id)
            mirror.remove(row.id)
        }
        return removed
    }

    private fun describe(reminder: Reminder): String? = EventLog.detail(
        "due" to reminder.dueAt,
        "nag" to (reminder.nagMinutes ?: 0),
        "level" to reminder.alertLevel,
        "repeat" to (reminder.repeatRule()?.token() ?: "OFF"),
    )

    /** An incoming import row: new local id, a uuid, never deleted, updatedAt never before createdAt. */
    private fun imported(row: Reminder): Reminder {
        val uuid = row.uuid.ifBlank { UUID.randomUUID().toString() }
        val updatedAt = if (row.updatedAt <= 0L) row.createdAt else row.updatedAt
        return row.copy(id = 0, uuid = uuid, deletedAt = null, updatedAt = updatedAt)
    }

    private fun changed() {
        try {
            onCommitted()
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
        }
    }
}
