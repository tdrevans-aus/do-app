package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.backup.exportable
import com.evansappstudio.doapp.backup.toPortable
import com.evansappstudio.doapp.backup.toReminder
import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Spec 9 command layer, soft delete and the event log, on the shared in-memory fixture. */
class ReminderCommandsTest {
    private val user = Origin(Actor.USER)
    private val tap = Origin(Actor.NOTIFICATION)
    private val day = RepeatRule.of(1, RepeatUnit.DAY, RepeatFrom.DUE_DATE)!!

    // AT-3
    @Test
    fun at3AddStoresTheSourceAndWritesOneCreatedEvent() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(
            Origin(Actor.AGENT, ReminderSource.AGENT, "agent-1"),
            "Bins",
            fx.clockMillis + 60_000L,
            5,
            category = "home",
            templateId = "bins-weekly",
            templateVersion = 3,
        )!!
        val row = fx.dao.getById(id)!!
        assertEquals("AGENT", row.source)
        assertEquals("agent-1", row.sourceId)
        assertEquals("home", row.category)
        assertEquals("bins-weekly", row.templateId)
        assertEquals(3, row.templateVersion)
        assertEquals(1, row.version)
        assertEquals(fx.clockMillis, row.updatedAt)
        assertEquals(fx.clockMillis, row.createdAt)
        assertEquals("NONE", row.approval)
        assertNull(row.deletedAt)
        val events = fx.events.forUuid(row.uuid)
        assertEquals(1, events.size)
        assertEquals(EventType.CREATED.name, events[0].type)
        assertEquals(Actor.AGENT.name, events[0].actor)
        assertEquals("agent-1", events[0].sourceId)
        assertEquals(fx.clockMillis, events[0].at)
        assertFalse(events[0].detail!!.contains("Bins"))
    }

    // AT-3 (sourceId is cut, not refused; an unknown category is stored as null)
    @Test
    fun at3LongSourceIdIsCutAndAnUnknownCategoryIsNull() = runBlocking {
        val fx = schedulerFixture()
        val long = "a".repeat(250)
        val id = fx.commands.add(
            Origin(Actor.AGENT, ReminderSource.AGENT, long),
            "Bins",
            fx.clockMillis + 60_000L,
            null,
            category = "spaceship",
        )!!
        val row = fx.dao.getById(id)!!
        assertEquals(Origin.MAX_SOURCE_ID, row.sourceId!!.length)
        assertNull(row.category)
        assertEquals("USER", fx.dao.getById(fx.commands.add(user, "Milk", 5_000L, null)!!)!!.source)
    }

    // AT-4
    @Test
    fun at4EveryCommandBumpsVersionOnceAndWritesOneEvent() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + 3_600_000L, 5)!!

        expectOne(fx, id, EventType.EDITED, user) {
            val row = fx.dao.getById(id)!!
            val result = fx.commands.update(user, id, "Bins out", row.dueAt + 60_000L, 5, null, null, AlertLevel.STANDARD)
            assertEquals(EditResult.SAVED, result)
        }
        expectOne(fx, id, EventType.SNOOZED, tap) {
            fx.commands.snooze(tap, id, 0, SnoozeDue.DEFAULTS[0])
        }
        fx.alerts.showing += id
        expectOne(fx, id, EventType.SNOOZED, tap) {
            fx.commands.snoozeShowing(tap, 1, SnoozeDue.DEFAULTS[1])
        }
        fx.alerts.showing.clear()
        expectOne(fx, id, EventType.ALERT_STOPPED, tap) {
            fx.commands.stopAlert(tap, id)
        }
        expectOne(fx, id, EventType.LEVEL_CHANGED, user) {
            fx.commands.setAlertLevel(user, id, AlertLevel.CRITICAL)
        }
        expectOne(fx, id, EventType.REPEAT_CHANGED, Origin(Actor.DEBUG)) {
            fx.commands.setRepeat(Origin(Actor.DEBUG), id, day)
        }
        expectOne(fx, id, EventType.EDITED, Origin(Actor.DEBUG)) {
            val row = fx.dao.getById(id)!!
            assertNotNull(fx.commands.shiftDue(Origin(Actor.DEBUG), id, row.dueAt + 120_000L, DueShiftMode.THIS))
        }
        expectOne(fx, id, EventType.EDITED, Origin(Actor.DEBUG)) {
            assertNotNull(fx.commands.setTitle(Origin(Actor.DEBUG), id, "Bins tonight"))
        }
        expectOne(fx, id, EventType.DELETED, user) {
            fx.commands.delete(user, id)
        }
        expectOne(fx, id, EventType.RESTORED, user) {
            assertTrue(fx.commands.restore(user, id))
        }

        val plain = fx.commands.add(user, "Milk", fx.clockMillis + 3_600_000L, null)!!
        expectOne(fx, plain, EventType.DONE, tap) {
            fx.commands.markDone(tap, plain)
        }
        assertTrue(fx.dao.getById(plain)!!.isDone)
    }

    // AT-4 (repeating Done: DONE then ADVANCED, one version bump)
    @Test
    fun at4RepeatingDoneWritesDoneThenAdvancedWithOneBump() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + 60_000L, null, day)!!
        val before = fx.dao.getById(id)!!
        val seen = fx.events.forUuid(before.uuid).size
        fx.clockMillis += 120_000L
        fx.commands.markDone(tap, id)
        val after = fx.dao.getById(id)!!
        assertFalse(after.isDone)
        assertTrue(after.dueAt > before.dueAt)
        assertEquals(before.version + 1, after.version)
        assertEquals(fx.clockMillis, after.updatedAt)
        val fresh = fx.events.forUuid(before.uuid).drop(seen)
        assertEquals(listOf(EventType.DONE.name, EventType.ADVANCED.name), fresh.map { it.type })
        assertTrue(fresh.all { it.actor == Actor.NOTIFICATION.name })
    }

    // AT-4 (an edit that changes nothing writes nothing)
    @Test
    fun at4AnUnchangedEditDoesNotBumpOrWriteAnEvent() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + 60_000L, 5)!!
        val row = fx.dao.getById(id)!!
        val result = fx.commands.update(user, id, "Bins", row.dueAt, 5, null, null, AlertLevel.STANDARD)
        assertEquals(EditResult.UNCHANGED, result)
        assertEquals(1, fx.dao.getById(id)!!.version)
        assertEquals(1, fx.events.forUuid(row.uuid).size)
    }

    // AT-5
    @Test
    fun at5FiresWriteEventsButDoNotBumpVersion() = runBlocking {
        val fx = schedulerFixture()
        val due = fx.clockMillis + 20_000L
        val id = fx.commands.add(user, "Bins", due, 1)!!
        val uuid = fx.dao.getById(id)!!.uuid
        fx.clockMillis = due
        fx.scheduler.onFire(id, due, due)
        val nag = fx.mirror.get(id)!!.nextFireAt!!
        fx.clockMillis = nag
        fx.scheduler.onFire(id, nag, nag)
        fx.quiet = fx.quiet.copy(followDnd = true)
        fx.dnd = true
        val held = fx.mirror.get(id)!!.nextFireAt!!
        fx.clockMillis = held
        fx.scheduler.onFire(id, held, held)
        assertTrue(fx.mirror.get(id)!!.dndHeld)

        val types = fx.events.forUuid(uuid).map { it.type }
        assertEquals(
            listOf(EventType.CREATED, EventType.FIRED, EventType.NAGGED, EventType.HELD).map { it.name },
            types,
        )
        assertTrue(fx.events.forUuid(uuid).drop(1).all { it.actor == Actor.SYSTEM.name })
        val row = fx.dao.getById(id)!!
        assertEquals(1, row.version)
        assertEquals(row.createdAt, row.updatedAt)
    }

    // AT-6
    @Test
    fun at6SoftDeleteHidesTheRowEverywhere() = runBlocking {
        val fx = schedulerFixture()
        fx.paid = false
        val ids = (1..5).map { fx.commands.add(user, "R$it", fx.clockMillis + it * 60_000L, null)!! }
        assertNull(fx.commands.add(user, "Six", fx.clockMillis + 600_000L, null))
        val gone = ids[2]
        assertNotNull(fx.alarms.at(gone))
        fx.commands.delete(user, gone)

        assertFalse(fx.dao.observeAll().first().any { it.id == gone })
        assertFalse(fx.dao.active().any { it.id == gone })
        assertEquals(4, fx.dao.activeCount())
        assertFalse(fx.dao.all().exportable().any { it.uuid == fx.dao.getById(gone)!!.uuid })
        assertNull(fx.alarms.at(gone))
        assertNull(fx.mirror.get(gone))
        assertNotNull(fx.commands.add(user, "Six", fx.clockMillis + 600_000L, null))
        val row = fx.dao.getById(gone)!!
        assertEquals(fx.clockMillis, row.deletedAt)

        // A deleted row is gone for every command and for a fire.
        fx.commands.markDone(user, gone)
        assertFalse(fx.dao.getById(gone)!!.isDone)
        fx.scheduler.onFire(gone, fx.clockMillis, fx.clockMillis)
        assertFalse(fx.alerts.shown.contains(gone))
        fx.scheduler.rescheduleAll()
        assertNull(fx.alarms.at(gone))
        assertEquals(row.version, fx.dao.getById(gone)!!.version)
    }

    // AT-7
    @Test
    fun at7RestoreWithinTheWindowReArmsAndCatchesUp() = runBlocking {
        val fx = schedulerFixture()
        val due = fx.clockMillis + 60_000L
        val id = fx.commands.add(user, "Bins", due, 5)!!
        fx.commands.delete(user, id)
        val deleted = fx.dao.getById(id)!!
        fx.clockMillis += 2 * SoftDelete.DAY_MS
        assertTrue(fx.commands.restore(user, id))
        val row = fx.dao.getById(id)!!
        assertNull(row.deletedAt)
        assertEquals(deleted.version + 1, row.version)
        assertEquals(fx.clockMillis, row.updatedAt)
        assertEquals(fx.clockMillis, fx.alarms.at(id))
        assertNotNull(fx.mirror.get(id))
        assertEquals(EventType.RESTORED.name, fx.events.forUuid(row.uuid).last().type)
        assertEquals(1, fx.dao.activeCount())

        // Not deleted, or past the window: refused.
        assertFalse(fx.commands.restore(user, id))
        fx.commands.delete(user, id)
        fx.clockMillis += 31 * SoftDelete.DAY_MS
        assertFalse(fx.commands.restore(user, id))
        assertNotNull(fx.dao.getById(id)!!.deletedAt)
    }

    // AT-8
    @Test
    fun at8PurgeRemovesRowsDeletedMoreThan30DaysAgoWithTheirEvents() = runBlocking {
        val fx = schedulerFixture()
        val start = fx.clockMillis
        val old = fx.commands.add(user, "Old", start + 60_000L, null)!!
        val recent = fx.commands.add(user, "Recent", start + 60_000L, null)!!
        val keep = fx.commands.add(user, "Keep", start + 60_000L, null)!!
        val oldUuid = fx.dao.getById(old)!!.uuid
        val recentUuid = fx.dao.getById(recent)!!.uuid
        fx.commands.delete(user, old)
        fx.clockMillis = start + 2 * SoftDelete.DAY_MS
        fx.commands.delete(user, recent)
        fx.clockMillis = start + 31 * SoftDelete.DAY_MS
        fx.scheduler.rescheduleAll()
        assertNull(fx.dao.getById(old))
        assertTrue(fx.events.forUuid(oldUuid).isEmpty())
        assertNotNull(fx.dao.getById(recent)!!.deletedAt)
        assertEquals(2, fx.events.forUuid(recentUuid).size)
        assertNotNull(fx.dao.getById(keep))
        assertEquals(0, fx.scheduler.purgeDeleted())
        assertEquals(1, fx.scheduler.purgeDeleted(days = 0))
        assertNull(fx.dao.getById(recent))
    }

    // AT-9
    @Test
    fun at9RetentionKeepsTheNewest200EventsPerReminder() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "T0", fx.clockMillis + 3_600_000L, null)!!
        val other = fx.commands.add(user, "Other", fx.clockMillis + 3_600_000L, null)!!
        val uuid = fx.dao.getById(id)!!.uuid
        repeat(204) { index ->
            fx.clockMillis += 1_000L
            fx.commands.setTitle(user, id, "T${index + 1}")
        }
        val kept = fx.events.forReminder(uuid, 1_000)
        assertEquals(EventLog.MAX_PER_REMINDER, kept.size)
        assertFalse(kept.any { it.type == EventType.CREATED.name })
        assertEquals(fx.clockMillis, kept.first().at)
        assertEquals(fx.clockMillis - 199_000L, kept.last().at)
        assertEquals(1, fx.events.forUuid(fx.dao.getById(other)!!.uuid).size)
        assertEquals(205, fx.dao.getById(id)!!.version)
    }

    // AT-10
    @Test
    fun at10AFailedEventInsertRollsBackTheReminderChange() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + 60_000L, 5)!!
        val before = fx.dao.getById(id)!!
        val eventsBefore = fx.events.count()
        fx.events.failInserts = true
        fx.clockMillis += 1_000L
        expectFailure { fx.commands.update(user, id, "Changed", before.dueAt + 60_000L, 5, null) }
        expectFailure { fx.commands.markDone(user, id) }
        expectFailure { fx.commands.delete(user, id) }
        expectFailure { fx.commands.add(user, "New", fx.clockMillis + 60_000L, null) }
        assertEquals(before, fx.dao.getById(id))
        assertEquals(1, fx.dao.allIncludingDeleted().size)
        assertEquals(eventsBefore, fx.events.count())
        assertTrue(fx.transactions > 0)
        fx.events.failInserts = false
        fx.commands.markDone(user, id)
        assertTrue(fx.dao.getById(id)!!.isDone)
    }

    // AT-13
    @Test
    fun at13MergeRestoresASoftDeletedMatchWithTheIncomingValues() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + 60_000L, 5)!!
        fx.commands.delete(user, id)
        val local = fx.dao.getById(id)!!
        val incoming = local.toPortable().copy(title = "Bins from backup", nagMinutes = 10, version = 7)
        val ids = fx.commands.mergeAll(Origin(Actor.IMPORT), listOf(incoming.toReminder(0)))
        assertEquals(listOf(id), ids)
        val row = fx.dao.getByUuid(local.uuid)!!
        assertEquals(id, row.id)
        assertNull(row.deletedAt)
        assertEquals("Bins from backup", row.title)
        assertEquals(10, row.nagMinutes)
        // Merge onto a local row never lowers version: max(local 2, incoming 7) + 1 (QA bug 2).
        assertEquals(8, row.version)
        assertEquals(fx.clockMillis, row.updatedAt)
        assertTrue(fx.dao.active().any { it.id == id })
        assertNotNull(fx.alarms.at(id))
        val last = fx.events.forUuid(local.uuid).last()
        assertEquals(EventType.IMPORTED.name, last.type)
        assertEquals(Actor.IMPORT.name, last.actor)
    }

    // Events written before unlock are deferred: a pending Done is recorded by rescheduleAll.
    @Test
    fun aDoneTappedBeforeUnlockIsRecordedWhenRoomOpens() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + 60_000L, null)!!
        val uuid = fx.dao.getById(id)!!.uuid
        fx.unlocked = false
        fx.commands.markDone(tap, id)
        assertEquals(1, fx.events.forUuid(uuid).size)
        fx.unlocked = true
        fx.scheduler.rescheduleAll()
        val done = fx.events.forUuid(uuid).last()
        assertEquals(EventType.DONE.name, done.type)
        assertEquals(Actor.SYSTEM.name, done.actor)
        assertTrue(done.detail!!.contains("deferred=1"))
        assertEquals(2, fx.dao.getById(id)!!.version)
    }

    private suspend fun expectOne(
        fx: Fixture,
        id: Long,
        type: EventType,
        origin: Origin,
        block: suspend () -> Unit,
    ) {
        val before: Reminder = fx.dao.getById(id)!!
        val seen = fx.events.forUuid(before.uuid).size
        fx.clockMillis += 1_000L
        block()
        val after = fx.dao.getById(id)!!
        assertEquals("version after $type", before.version + 1, after.version)
        assertEquals("updatedAt after $type", fx.clockMillis, after.updatedAt)
        val fresh = fx.events.forUuid(before.uuid).drop(seen)
        assertEquals("events after $type", listOf(type.name), fresh.map { it.type })
        assertEquals(origin.actor.name, fresh.single().actor)
        assertEquals(fx.clockMillis, fresh.single().at)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("expected the event insert to fail")
        } catch (expected: IllegalStateException) {
            assertEquals("event insert failed", expected.message)
        }
    }
}
