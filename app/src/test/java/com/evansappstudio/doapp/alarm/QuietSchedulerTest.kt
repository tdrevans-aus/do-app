package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class QuietSchedulerTest {
    @Test
    fun onFireHoldsANagWhileDndIsOnAndAFirstFireStillShows() = runBlocking {
        val fx = fixture()
        val due = fx.clockMillis + 20_000L
        fx.clockMillis = due - 20_000L
        fx.quiet = fx.quiet.copy(followDnd = true)
        fx.dnd = true
        val id = fx.scheduler.add("Bins", due, 1)!!
        fx.clockMillis = due
        fx.scheduler.onFire(id, due, due)
        assertEquals(listOf(id), fx.alerts.shown)
        assertFalse(fx.mirror.get(id)!!.dndHeld)
        val nag = fx.mirror.get(id)!!.nextFireAt!!
        assertTrue(nag > due)
        fx.clockMillis = nag
        fx.scheduler.onFire(id, nag, nag)
        assertEquals(listOf(id), fx.alerts.shown)
        assertTrue(fx.mirror.get(id)!!.dndHeld)
        assertTrue(fx.mirror.get(id)!!.pendingNag())
        val armed = fx.alarms.at(id)
        assertEquals(NagSchedule.nextAnchorStrictlyAfter(due, 1, nag), armed)
    }

    @Test
    fun onFireFromTheMirrorHoldsANagAndStillShowsAFirstFire() = runBlocking {
        val fx = fixture()
        val due = fx.clockMillis + 20_000L
        fx.clockMillis = due - 20_000L
        val id = fx.scheduler.add("Bins", due, 1)!!
        fx.unlocked = false
        fx.quiet = fx.quiet.copy(followDnd = true)
        fx.dnd = true
        fx.clockMillis = due
        fx.scheduler.onFire(id, due, due)
        assertEquals(1, fx.alerts.shown.size)
        val nag = fx.mirror.get(id)!!.nextFireAt!!
        fx.clockMillis = nag
        fx.scheduler.onFire(id, nag, nag)
        assertEquals(1, fx.alerts.shown.size)
        assertTrue(fx.mirror.get(id)!!.dndHeld)
    }

    @Test
    fun reconcileReleasesAHeldNagAtNowWhenDndClearsOrTheSettingTurnsOff() = runBlocking {
        val fx = fixture()
        val id = holdStandard(fx)
        val now = fx.clockMillis
        fx.dnd = false
        fx.scheduler.reconcileQuietHours()
        assertFalse(fx.mirror.get(id)!!.dndHeld)
        assertEquals(now, fx.mirror.get(id)!!.nextFireAt)
        assertEquals(now, fx.alarms.at(id))

        val again = holdStandard(fx)
        fx.quiet = fx.quiet.copy(followDnd = false)
        fx.scheduler.reconcileQuietHours()
        assertFalse(fx.mirror.get(again)!!.dndHeld)
        assertEquals(fx.clockMillis, fx.alarms.at(again))

        fx.quiet = QuietConfig.DEFAULT.copy(followDnd = true, criticalPauses = true)
        fx.dnd = true
        val due = fx.clockMillis + 20_000L
        fx.clockMillis = due - 20_000L
        val critical = fx.scheduler.add("Crit", due, 1, alertLevel = AlertLevel.CRITICAL)!!
        fx.clockMillis = due
        fx.scheduler.onFire(critical, due, due)
        val nag = fx.mirror.get(critical)!!.nextFireAt!!
        fx.clockMillis = nag
        fx.scheduler.onFire(critical, nag, nag)
        assertTrue(fx.mirror.get(critical)!!.dndHeld)
        fx.quiet = fx.quiet.copy(criticalPauses = false)
        val releaseAt = fx.clockMillis
        fx.scheduler.reconcileQuietHours()
        assertFalse(fx.mirror.get(critical)!!.dndHeld)
        assertEquals(releaseAt, fx.alarms.at(critical))
        assertTrue(fx.dnd)
    }

    @Test
    fun aReleaseInsideTheWindowGoesToTheWindowEnd() = runBlocking {
        val fx = fixture()
        val id = holdStandard(fx)
        val zone = ZoneId.of("Australia/Perth")
        fx.zone = zone
        val local = java.time.Instant.ofEpochMilli(fx.clockMillis).atZone(zone)
        val start = local.minusMinutes(1)
        val end = local.plusMinutes(30)
        fx.quiet = QuietConfig(
            enabled = true,
            startMinute = start.hour * 60 + start.minute,
            endMinute = end.hour * 60 + end.minute,
            followDnd = true,
            criticalPauses = false,
        )
        fx.dnd = false
        fx.scheduler.reconcileQuietHours()
        val expected = QuietHours.windowEnd(fx.clockMillis, fx.quiet, zone)
        assertEquals(expected, fx.mirror.get(id)!!.nextFireAt)
        assertNotEquals(fx.clockMillis, fx.mirror.get(id)!!.nextFireAt)
        assertFalse(fx.mirror.get(id)!!.dndHeld)
    }

    @Test
    fun anOldMirrorWithoutDndHeldLoadsAsNotHeld() {
        val file = File.createTempFile("mirror", ".json")
        file.writeText("""[{"id":7,"title":"Bins","dueAt":1000,"nagMinutes":1,"nextFireAt":2000}]""")
        val loaded = ReminderMirror(file).get(7)!!
        assertFalse(loaded.dndHeld)
        assertTrue(loaded.pendingNag())
        file.delete()
    }

    @Test
    fun enablingDisablingAndChangingTheWindowReplacesANag() = runBlocking {
        val fx = fixture()
        val zone = ZoneId.of("Australia/Perth")
        fx.zone = zone
        val dueZ = ZonedDateTime.of(2026, 6, 15, 21, 59, 0, 0, zone)
        val due = dueZ.toInstant().toEpochMilli()
        fx.clockMillis = due - 5_000L
        val id = fx.scheduler.add("Bins", due, 1)!!
        fx.clockMillis = due
        fx.scheduler.onFire(id, due, due)
        val anchor = fx.mirror.get(id)!!.nextFireAt!!
        assertEquals(due + 60_000L, anchor)
        fx.quiet = QuietConfig.DEFAULT.copy(enabled = true)
        fx.clockMillis = anchor - 1_000L
        fx.scheduler.reconcileQuietHours()
        val end = QuietHours.windowEnd(anchor, fx.quiet, zone)
        assertEquals(end, fx.mirror.get(id)!!.nextFireAt)
        fx.quiet = fx.quiet.copy(enabled = false)
        fx.scheduler.reconcileQuietHours()
        assertEquals(NagSchedule.nextAnchorStrictlyAfter(due, 1, fx.clockMillis), fx.mirror.get(id)!!.nextFireAt)
        fx.quiet = QuietConfig(
            enabled = true,
            startMinute = 13 * 60,
            endMinute = 14 * 60,
            followDnd = false,
            criticalPauses = false,
        )
        fx.clockMillis = ZonedDateTime.of(2026, 6, 16, 13, 10, 0, 0, zone).toInstant().toEpochMilli()
        fx.scheduler.reconcileQuietHours()
        assertEquals(
            QuietHours.windowEnd(fx.clockMillis, fx.quiet, zone),
            fx.mirror.get(id)!!.nextFireAt,
        )
    }

    @Test
    fun aZoneChangeReplacesAHeldNag() = runBlocking {
        val fx = fixture()
        val sydney = ZoneId.of("Australia/Sydney")
        fx.zone = sydney
        val due = ZonedDateTime.of(2026, 7, 15, 22, 10, 0, 0, sydney).toInstant().toEpochMilli()
        fx.clockMillis = due - 5_000L
        fx.quiet = QuietConfig.DEFAULT.copy(enabled = true)
        val id = fx.scheduler.add("Bins", due, 1)!!
        fx.clockMillis = due
        fx.scheduler.onFire(id, due, due)
        val held = fx.mirror.get(id)!!.nextFireAt!!
        assertEquals(
            ZonedDateTime.of(2026, 7, 16, 7, 0, 0, 0, sydney).toInstant().toEpochMilli(),
            held,
        )
        fx.zone = ZoneId.of("Australia/Perth")
        fx.scheduler.onExternalClock(zoneChanged = true)
        val replanned = NagSchedule.nextAnchorStrictlyAfter(due, 1, fx.clockMillis)
        val expected = QuietHours.placeNag(replanned, AlertLevel.STANDARD, fx.quiet, fx.zone)
        assertEquals(expected, fx.mirror.get(id)!!.nextFireAt)
        assertNotEquals(held, fx.mirror.get(id)!!.nextFireAt)
    }

    @Test
    fun mirrorCatchUpDefersAMissedNagAndNotAMissedFirstFire() = runBlocking {
        val fx = fixture()
        val zone = ZoneId.of("Australia/Perth")
        fx.zone = zone
        fx.quiet = QuietConfig.DEFAULT.copy(enabled = true)
        val due = ZonedDateTime.of(2026, 6, 15, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val missedNag = due + 60_000L
        fx.clockMillis = missedNag + 5_000L
        fx.mirror.upsert(
            com.evansappstudio.doapp.alarm.MirrorEntry(
                id = 4,
                title = "Bins",
                dueAt = due,
                nagMinutes = 1,
                nextFireAt = missedNag,
            ),
        )
        fx.scheduler.rescheduleFromMirror()
        val end = QuietHours.windowEnd(fx.clockMillis, fx.quiet, zone)
        assertEquals(end, fx.mirror.get(4)!!.nextFireAt)
        assertEquals(end, fx.alarms.at(4))

        val firstDue = ZonedDateTime.of(2026, 6, 15, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        fx.clockMillis = firstDue + 5_000L
        fx.mirror.upsert(
            com.evansappstudio.doapp.alarm.MirrorEntry(
                id = 5,
                title = "Milk",
                dueAt = firstDue,
                nagMinutes = 1,
                nextFireAt = firstDue,
            ),
        )
        fx.scheduler.rescheduleFromMirror()
        assertEquals(firstDue, fx.mirror.get(5)!!.nextFireAt)
        assertEquals(fx.clockMillis, fx.alarms.at(5))
    }

    @Test
    fun aFirstFireInsideTheWindowIsNotMovedAndTheNextNagResumesAtTheEnd() = runBlocking {
        val fx = fixture()
        val zone = ZoneId.of("Australia/Perth")
        fx.zone = zone
        fx.quiet = QuietConfig.DEFAULT.copy(enabled = true)
        val due = ZonedDateTime.of(2026, 6, 15, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        fx.clockMillis = due - 5_000L
        val id = fx.scheduler.add("Bins", due, 1)!!
        assertEquals(due, fx.mirror.get(id)!!.nextFireAt)
        fx.clockMillis = due
        fx.scheduler.onFire(id, due, due)
        assertEquals(1, fx.alerts.shown.size)
        val end = ZonedDateTime.of(2026, 6, 16, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(end, fx.mirror.get(id)!!.nextFireAt)
        fx.clockMillis = end
        fx.scheduler.onFire(id, end, end)
        assertEquals(end + 60_000L, fx.mirror.get(id)!!.nextFireAt)
        assertEquals(due + ((end + 60_000L - due) / 60_000L) * 60_000L, fx.mirror.get(id)!!.nextFireAt)
    }

    @Test
    fun repeatsAreNotCappedSeparatelyAndOnlyTheActiveCapApplies() = runBlocking {
        val fx = fixture()
        fx.paid = false
        val day = RepeatRule.of(1, RepeatUnit.DAY, RepeatFrom.DUE_DATE)!!
        val week = RepeatRule.of(1, RepeatUnit.WEEK, RepeatFrom.COMPLETION)!!
        val first = fx.scheduler.add("One", 1_000L, null, day)!!
        fx.scheduler.add("Two", 2_000L, null, day)!!
        fx.scheduler.add("Three", 3_000L, null, week)!!
        val plain = fx.scheduler.add("Plain", 4_000L, null)!!
        assertEquals(4, fx.dao.activeCount())

        val edited = fx.dao.getById(plain)!!
        assertEquals(
            EditResult.SAVED,
            fx.scheduler.update(plain, "Renamed", edited.dueAt, null, day, null, AlertLevel.STANDARD),
        )
        assertEquals("Renamed", fx.dao.getById(plain)!!.title)
        assertEquals(RepeatUnit.DAY, fx.dao.getById(plain)!!.repeatRule()!!.unit)

        val fifth = fx.scheduler.add("Five", 5_000L, null)!!
        fx.scheduler.setRepeat(fifth, week)
        assertEquals(RepeatUnit.WEEK, fx.dao.getById(fifth)!!.repeatRule()!!.unit)
        assertEquals(5, fx.dao.active().count { it.repeatRule() != null })

        assertNull(fx.scheduler.add("Six", 6_000L, null, day))
        assertNull(fx.scheduler.add("Six plain", 6_000L, null))
        assertEquals(5, fx.dao.activeCount())

        fx.scheduler.setRepeat(first, null)
        assertNull(fx.dao.getById(first)!!.repeatRule())
        fx.paid = true
        assertNotNull(fx.scheduler.add("Six", 6_000L, null, day))
    }

    private suspend fun holdStandard(fx: Fixture): Long {
        fx.quiet = QuietConfig.DEFAULT.copy(followDnd = true)
        fx.dnd = true
        val due = fx.clockMillis + 20_000L
        fx.clockMillis = due - 20_000L
        val id = fx.scheduler.add("Hold", due, 1)!!
        fx.clockMillis = due
        fx.scheduler.onFire(id, due, due)
        val nag = fx.mirror.get(id)!!.nextFireAt!!
        fx.clockMillis = nag
        fx.scheduler.onFire(id, nag, nag)
        assertTrue(fx.mirror.get(id)!!.dndHeld)
        return id
    }

    private fun fixture(): Fixture {
        val dir = File.createTempFile("quiet", "dir")
        dir.delete()
        dir.mkdirs()
        val dao = MemoryReminderDao()
        val alarms = FakeAlarms()
        val alerts = FakeAlerts()
        val fx = Fixture(dao, alarms, alerts, ReminderMirror(File(dir, "reminder-mirror.json")), dir)
        fx.scheduler = Scheduler(
            daoProvider = { dao },
            mirror = fx.mirror,
            alarms = alarms,
            notifications = alerts,
            unlocked = { fx.unlocked },
            zones = ZoneMemory(File(dir, "zone-memory.txt")),
            weekStart = { WeekStart.MONDAY },
            paidUnlocked = { fx.paid },
            inTransaction = { body -> body() },
            onCommitted = {},
            quietConfig = { fx.quiet },
            dndActive = { fx.dnd },
            zone = { fx.zone },
            clock = { fx.clockMillis },
        )
        return fx
    }
}

private class Fixture(
    val dao: MemoryReminderDao,
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
    lateinit var scheduler: Scheduler
}

private class FakeAlarms : AlarmArm {
    private val armed = HashMap<Long, Long>()
    override fun schedule(id: Long, triggerAtMillis: Long) {
        armed[id] = triggerAtMillis
    }

    override fun cancel(id: Long) {
        armed.remove(id)
    }

    fun at(id: Long): Long? = armed[id]
}

private class FakeAlerts : ReminderAlerts {
    val shown = ArrayList<Long>()
    override fun show(id: Long, title: String, dueAt: Long, requested: AlertLevel): AlertDecision {
        shown += id
        return decide(requested)
    }

    override fun dismiss(id: Long) = Unit
    override fun showingIds(): List<Long> = emptyList()
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

private class MemoryReminderDao : ReminderDao {
    private val rows = ArrayList<Reminder>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<Reminder>> = flowOf(rows.toList())
    override suspend fun active(): List<Reminder> = rows.filter { !it.isDone }
    override suspend fun activeCount(): Int = active().size
    override suspend fun all(): List<Reminder> = rows.toList()
    override suspend fun getById(id: Long): Reminder? = rows.firstOrNull { it.id == id }
    override suspend fun getByUuid(uuid: String): Reminder? = rows.firstOrNull { it.uuid == uuid }

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

    override suspend fun markDone(id: Long, doneAt: Long) {
        val index = rows.indexOfFirst { it.id == id && !it.isDone }
        if (index >= 0) rows[index] = rows[index].copy(isDone = true, doneAt = doneAt)
    }

    override suspend fun updateDueAt(id: Long, dueAt: Long) {
        val index = rows.indexOfFirst { it.id == id && !it.isDone }
        if (index >= 0) rows[index] = rows[index].copy(dueAt = dueAt)
    }

    override suspend fun updateDueAndPattern(id: Long, dueAt: Long, patternDueAt: Long?) {
        val index = rows.indexOfFirst { it.id == id && !it.isDone }
        if (index >= 0) rows[index] = rows[index].copy(dueAt = dueAt, patternDueAt = patternDueAt)
    }

    override suspend fun advanceOccurrence(id: Long, dueAt: Long, patternDueAt: Long?) {
        updateDueAndPattern(id, dueAt, patternDueAt)
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
    ) {
        val index = rows.indexOfFirst { it.id == id && !it.isDone }
        if (index < 0) return
        rows[index] = rows[index].copy(
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
        )
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }
}
