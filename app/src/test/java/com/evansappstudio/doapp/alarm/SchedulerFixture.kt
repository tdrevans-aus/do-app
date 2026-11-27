package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.ReminderEvent
import com.evansappstudio.doapp.data.ReminderEventDao
import com.evansappstudio.doapp.data.WeekStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.time.ZoneId

/**
 * The in-memory Scheduler fixture first written for QuietSchedulerTest, shared so
 * the Spec 9 command tests use the same one. [Fixture.transaction] stands in for
 * Room's withTransaction: it snapshots both tables and puts them back if the
 * body throws.
 */
internal fun schedulerFixture(): Fixture {
    val dir = File.createTempFile("quiet", "dir")
    dir.delete()
    dir.mkdirs()
    val dao = MemoryReminderDao()
    val events = MemoryReminderEventDao()
    val alarms = FakeAlarms()
    val alerts = FakeAlerts()
    val fx = Fixture(dao, events, alarms, alerts, ReminderMirror(File(dir, "reminder-mirror.json")), dir)
    fx.scheduler = Scheduler(
        daoProvider = { dao },
        eventsProvider = { events },
        mirror = fx.mirror,
        alarms = alarms,
        notifications = alerts,
        unlocked = { fx.unlocked },
        zones = ZoneMemory(File(dir, "zone-memory.txt")),
        weekStart = { WeekStart.MONDAY },
        paidUnlocked = { fx.paid },
        inTransaction = { body -> fx.transaction(body) },
        onCommitted = {},
        quietConfig = { fx.quiet },
        dndActive = { fx.dnd },
        zone = { fx.zone },
        clock = { fx.clockMillis },
    )
    fx.commands = ReminderCommands(fx.scheduler)
    return fx
}

internal class Fixture(
    val dao: MemoryReminderDao,
    val events: MemoryReminderEventDao,
    val alarms: FakeAlarms,
    val alerts: FakeAlerts,
    val mirror: ReminderMirror,
    val dir: File,
) {
    var unlocked = true
    var paid = true
    var dnd = false
    var quiet = QuietConfig.DEFAULT
    var zone: ZoneId = ZoneId.of("Australia/Perth")
    var clockMillis: Long = 1_700_000_000_000L
    var transactions = 0
    lateinit var scheduler: Scheduler
    lateinit var commands: ReminderCommands

    suspend fun transaction(body: suspend () -> Unit) {
        transactions++
        val reminders = dao.snapshot()
        val log = events.snapshot()
        try {
            body()
        } catch (error: Throwable) {
            dao.restore(reminders)
            events.restore(log)
            throw error
        }
    }
}

internal class FakeAlarms : AlarmArm {
    private val armed = HashMap<Long, Long>()
    override fun schedule(id: Long, triggerAtMillis: Long) {
        armed[id] = triggerAtMillis
    }

    override fun cancel(id: Long) {
        armed.remove(id)
    }

    fun at(id: Long): Long? = armed[id]
}

internal class FakeAlerts : ReminderAlerts {
    val shown = ArrayList<Long>()
    val showing = ArrayList<Long>()
    override fun show(id: Long, title: String, dueAt: Long, requested: AlertLevel): AlertDecision {
        shown += id
        return decide(requested)
    }

    override fun dismiss(id: Long) = Unit
    override fun showingIds(): List<Long> = showing.toList()
    override fun rebuildShowing() = Unit
    override fun refreshIfShowing(id: Long, title: String, dueAt: Long, requested: AlertLevel) = Unit
    override fun decide(requested: AlertLevel): AlertDecision = AlertDecision(
        requested = requested,
        effective = requested,
        fullScreen = false,
        bypassDnd = requested != AlertLevel.STANDARD,
        loopUntilAttended = false,
        reasons = emptyList(),
    )
}

/** Mirrors the SQL in ReminderDao, including the deletedAt filters and the version bumps. */
internal class MemoryReminderDao : ReminderDao {
    private val rows = ArrayList<Reminder>()
    private var nextId = 1L

    fun snapshot(): Pair<List<Reminder>, Long> = rows.toList() to nextId

    fun restore(state: Pair<List<Reminder>, Long>) {
        rows.clear()
        rows += state.first
        nextId = state.second
    }

    private fun live(): List<Reminder> = rows.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<Reminder>> =
        flowOf(live().sortedWith(compareBy<Reminder>({ it.isDone }, { it.dueAt }, { it.id })))

    override suspend fun active(): List<Reminder> =
        live().filter { !it.isDone }.sortedWith(compareBy<Reminder>({ it.dueAt }, { it.id }))

    override suspend fun activeCount(): Int = active().size
    override suspend fun all(): List<Reminder> = live()
    override suspend fun allIncludingDeleted(): List<Reminder> = rows.toList()
    override suspend fun getById(id: Long): Reminder? = rows.firstOrNull { it.id == id }
    override suspend fun getByUuid(uuid: String): Reminder? = rows.firstOrNull { it.uuid == uuid }

    override suspend fun deletedBefore(cutoff: Long): List<Reminder> =
        rows.filter { it.deletedAt != null && it.deletedAt < cutoff }

    override suspend fun insert(reminder: Reminder): Long {
        val id = if (reminder.id == 0L) nextId++ else reminder.id
        rows += reminder.copy(id = id)
        return id
    }

    override suspend fun update(reminder: Reminder) {
        val index = rows.indexOfFirst { it.id == reminder.id }
        if (index >= 0) rows[index] = reminder
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun purgeDeletedBefore(cutoff: Long): Int {
        val before = rows.size
        rows.removeAll { it.deletedAt != null && it.deletedAt < cutoff }
        return before - rows.size
    }

    private fun change(id: Long, liveOnly: Boolean, block: (Reminder) -> Reminder?): Int {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return 0
        val row = rows[index]
        if (liveOnly && (row.isDone || row.deletedAt != null)) return 0
        val next = block(row) ?: return 0
        rows[index] = next
        return 1
    }

    private fun Reminder.bumped(now: Long) = copy(updatedAt = now, version = version + 1)

    override suspend fun markDone(id: Long, doneAt: Long, now: Long): Int =
        change(id, liveOnly = true) { it.copy(isDone = true, doneAt = doneAt).bumped(now) }

    override suspend fun updateDueAt(id: Long, dueAt: Long, now: Long): Int =
        change(id, liveOnly = true) { it.copy(dueAt = dueAt).bumped(now) }

    override suspend fun updateDueAndPattern(id: Long, dueAt: Long, patternDueAt: Long?, now: Long): Int =
        change(id, liveOnly = true) { it.copy(dueAt = dueAt, patternDueAt = patternDueAt).bumped(now) }

    override suspend fun advanceOccurrence(id: Long, dueAt: Long, patternDueAt: Long?, now: Long): Int =
        change(id, liveOnly = true) {
            it.copy(dueAt = dueAt, patternDueAt = patternDueAt, isDone = false, doneAt = null).bumped(now)
        }

    override suspend fun updateDetails(
        id: Long,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        repeatInterval: Int?,
        repeatUnit: String?,
        repeatFrom: String?,
        repeatWeekDays: String?,
        repeatMonthOn: String?,
        patternDueAt: Long?,
        alertLevel: String,
        now: Long,
    ): Int = change(id, liveOnly = true) {
        it.copy(
            title = title,
            dueAt = dueAt,
            nagMinutes = nagMinutes,
            repeatInterval = repeatInterval,
            repeatUnit = repeatUnit,
            repeatFrom = repeatFrom,
            repeatWeekDays = repeatWeekDays,
            repeatMonthOn = repeatMonthOn,
            patternDueAt = patternDueAt,
            alertLevel = alertLevel,
        ).bumped(now)
    }

    override suspend fun touch(id: Long, now: Long): Int = change(id, liveOnly = true) { it.bumped(now) }

    override suspend fun softDelete(id: Long, now: Long): Int = change(id, liveOnly = false) {
        if (it.deletedAt != null) null else it.copy(deletedAt = now).bumped(now)
    }

    override suspend fun restore(id: Long, now: Long): Int = change(id, liveOnly = false) {
        if (it.deletedAt == null) null else it.copy(deletedAt = null).bumped(now)
    }
}

/** Mirrors the SQL in ReminderEventDao. [failInserts] makes the next inserts throw. */
internal class MemoryReminderEventDao : ReminderEventDao {
    private val rows = ArrayList<ReminderEvent>()
    private var nextId = 1L
    var failInserts = false

    fun snapshot(): Pair<List<ReminderEvent>, Long> = rows.toList() to nextId

    fun restore(state: Pair<List<ReminderEvent>, Long>) {
        rows.clear()
        rows += state.first
        nextId = state.second
    }

    /** Oldest first, in insert order. */
    fun all(): List<ReminderEvent> = rows.toList()

    fun forUuid(uuid: String): List<ReminderEvent> = rows.filter { it.reminderUuid == uuid }

    override suspend fun insert(event: ReminderEvent): Long {
        if (failInserts) throw IllegalStateException("event insert failed")
        val id = nextId++
        rows += event.copy(id = id)
        return id
    }

    override suspend fun forReminder(uuid: String, limit: Int): List<ReminderEvent> =
        forUuid(uuid).sortedWith(compareByDescending<ReminderEvent> { it.at }.thenByDescending { it.id }).take(limit)

    override suspend fun trim(uuid: String, keep: Int) {
        val kept = forReminder(uuid, keep).map { it.id }.toSet()
        rows.removeAll { it.reminderUuid == uuid && it.id !in kept }
    }

    override suspend fun deleteForReminders(uuids: List<String>) {
        rows.removeAll { it.reminderUuid in uuids }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun count(): Int = rows.size
}
