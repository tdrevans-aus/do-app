package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.backup.BackupCodec
import com.evansappstudio.doapp.backup.BackupDecode
import com.evansappstudio.doapp.backup.BackupDocument
import com.evansappstudio.doapp.backup.BackupNames
import com.evansappstudio.doapp.backup.PortableSettings
import com.evansappstudio.doapp.backup.toPortable
import com.evansappstudio.doapp.backup.toReminder
import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * QA (Spec 9), independent of the builder's ReminderCommandsTest and BackupMetaTest.
 * Tests marked "QA: fails until ..." expose a bug and are expected to fail on bf9d41e.
 */
class Spec9QaTest {
    private val user = Origin(Actor.USER)
    private val tap = Origin(Actor.NOTIFICATION)
    private val import = Origin(Actor.IMPORT)
    private val daily = RepeatRule.of(1, RepeatUnit.DAY, RepeatFrom.DUE_DATE)!!
    private val day = SoftDelete.DAY_MS

    // QA: fails until merge import stops lowering version (and updatedAt) on an existing row.
    // Spec section C: every write through ReminderCommands sets updatedAt = clock() and
    // version = version + 1 on the rows it changes. mergeAll writes the file's version over
    // the local one, so merging an older backup moves version backwards (5 -> 1) and
    // updatedAt into the past. version is the change counter later sync and the agent API
    // will compare, so it must never go down.
    @Test
    fun qaMergeOntoALiveRowNeverLowersVersionOrUpdatedAt() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + day, 5)!!
        val exported = fx.dao.getById(id)!!.toPortable()
        repeat(4) { index ->
            fx.clockMillis += 1_000L
            fx.commands.setTitle(user, id, "Bins $index")
        }
        val local = fx.dao.getById(id)!!
        assertEquals(5, local.version)
        fx.clockMillis += 60_000L
        fx.commands.mergeAll(import, listOf(exported.toReminder(0)))
        val merged = fx.dao.getById(id)!!
        assertEquals("incoming values win", "Bins", merged.title)
        assertTrue("version went from ${local.version} to ${merged.version}", merged.version > local.version)
        assertTrue("updatedAt went backwards", merged.updatedAt >= local.updatedAt)
    }

    // QA: fails until the first fire after a restore (or any add whose due is already past)
    // is logged as FIRED. restore() arms catchUp(due, now) = now, which is after dueAt, so
    // MirrorEntry.pendingNag() is true and onFire records NAGGED for what is the first alert.
    // AT-19's expected sequence relies on the first fire being FIRED. Side effect worth
    // checking at the same time: the same flag makes holdNagIfNeeded treat this first fire
    // as a nag, so with "follow Do Not Disturb" on it is held instead of shown.
    @Test
    fun qaTheCatchUpFireAfterRestoringAPastDueRowIsLoggedAsFired() = runBlocking {
        val fx = schedulerFixture()
        val due = fx.clockMillis + 60_000L
        val id = fx.commands.add(user, "Bins", due, 5)!!
        val uuid = fx.dao.getById(id)!!.uuid
        fx.commands.delete(user, id)
        fx.clockMillis = due + day
        assertTrue(fx.commands.restore(user, id))
        val armed = fx.alarms.at(id)!!
        fx.clockMillis = armed
        fx.scheduler.onFire(id, armed, armed)
        assertTrue(fx.alerts.shown.contains(id))
        assertEquals(EventType.FIRED.name, fx.events.forUuid(uuid).last().type)
    }

    // QA: fails until the first fire after a restore of a past-due row is treated as a first
    // fire. Same cause as the test above: the catch-up marker is now, not dueAt, so with quiet
    // hours following Do Not Disturb the first alert is held as if it were a nag. Spec 8 says
    // a first fire always alerts. (add() with a due already in the past behaved this way in
    // Spec 8 too, but the UI does not offer a past due; restore makes it reachable.)
    @Test
    fun qaTheCatchUpFireAfterRestoringAPastDueRowStillAlertsUnderDnd() = runBlocking {
        val fx = schedulerFixture()
        val due = fx.clockMillis + 60_000L
        val id = fx.commands.add(user, "Bins", due, 5)!!
        fx.commands.delete(user, id)
        fx.clockMillis = due + day
        assertTrue(fx.commands.restore(user, id))
        fx.quiet = fx.quiet.copy(followDnd = true)
        fx.dnd = true
        val armed = fx.alarms.at(id)!!
        fx.clockMillis = armed
        fx.scheduler.onFire(id, armed, armed)
        assertTrue("the first fire after restore was held", fx.alerts.shown.contains(id))
    }

    // QA: fails until the 200-character cut stops splitting a surrogate pair. substring(0, 200)
    // can leave a lone high surrogate at the end of sourceId (and templateId), which is not
    // valid UTF-16 and is stored and exported as a broken character.
    @Test
    fun qaTheSourceIdCutDoesNotSplitASurrogatePair() {
        val raw = "a".repeat(Origin.MAX_SOURCE_ID - 1) + "😀" + "tail"
        val cut = Origin.clipSourceId(raw)!!
        assertTrue(cut.length <= Origin.MAX_SOURCE_ID)
        assertFalse("ends with a lone high surrogate", Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun qaAOneMinuteNagForADayKeeps200EventsAndNeverBumpsVersion() = runBlocking {
        val fx = schedulerFixture()
        val due = fx.clockMillis + 60_000L
        val id = fx.commands.add(user, "Meds", due, 1)!!
        val uuid = fx.dao.getById(id)!!.uuid
        var fireAt = due
        repeat(1_440) {
            fx.clockMillis = fireAt
            fx.scheduler.onFire(id, fireAt, fireAt)
            fireAt = fx.alarms.at(id)!!
        }
        assertEquals(fx.clockMillis + 60_000L, fireAt)
        val events = fx.events.forUuid(uuid)
        assertEquals(EventLog.MAX_PER_REMINDER, events.size)
        assertTrue(events.all { it.type == EventType.NAGGED.name && it.actor == Actor.SYSTEM.name })
        assertEquals(1, fx.dao.getById(id)!!.version)
        assertEquals(1_440, fx.alerts.shown.size)
    }

    @Test
    fun qaCommandsOnASoftDeletedRowChangeNothingAndWriteNoEvent() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + day, 5)!!
        fx.commands.delete(user, id)
        val deleted = fx.dao.getById(id)!!
        val seen = fx.events.forUuid(deleted.uuid).size
        fx.clockMillis += 1_000L
        assertEquals(EditResult.UNCHANGED, fx.commands.update(user, id, "Other", deleted.dueAt + 5, 10, null))
        fx.commands.snooze(tap, id, 0, SnoozeDue.DEFAULTS[0])
        fx.commands.stopAlert(tap, id)
        fx.commands.setAlertLevel(user, id, AlertLevel.CRITICAL)
        fx.commands.setRepeat(user, id, daily)
        assertNull(fx.commands.shiftDue(user, id, deleted.dueAt + 60_000L, DueShiftMode.THIS))
        assertNull(fx.commands.setTitle(user, id, "Renamed"))
        fx.commands.markDone(tap, id)
        fx.commands.delete(user, id)
        assertEquals(deleted, fx.dao.getById(id))
        assertEquals(seen, fx.events.forUuid(deleted.uuid).size)
        assertNull(fx.alarms.at(id))
        assertNull(fx.mirror.get(id))
    }

    @Test
    fun qaReplaceImportClearsSoftDeletedRowsAndAllTheirEvents() = runBlocking {
        val fx = schedulerFixture()
        val kept = fx.commands.add(user, "Kept", fx.clockMillis + day, 5)!!
        val gone = fx.commands.add(user, "Gone", fx.clockMillis + day, 5)!!
        val goneUuid = fx.dao.getById(gone)!!.uuid
        fx.commands.delete(user, gone)
        val file = fx.dao.all().map { it.toPortable() }
        fx.commands.replaceAll(import, file.map { it.toReminder(0) })
        assertEquals(1, fx.dao.allIncludingDeleted().size)
        assertNull(fx.dao.getByUuid(goneUuid))
        assertTrue(fx.events.forUuid(goneUuid).isEmpty())
        val keptUuid = file.single().uuid
        assertEquals(listOf(EventType.IMPORTED.name), fx.events.forUuid(keptUuid).map { it.type })
        assertNotNull(fx.alarms.at(fx.dao.getByUuid(keptUuid)!!.id))
        assertNotNull(kept)
    }

    @Test
    fun qaNoEventEverCarriesTheTitle() = runBlocking {
        val fx = schedulerFixture()
        val secret = "Zebra-Secret-Title"
        val id = fx.commands.add(Origin(Actor.AGENT, ReminderSource.AGENT, "agent-9"), secret, fx.clockMillis + 60_000L, 1, daily)!!
        val row = fx.dao.getById(id)!!
        fx.commands.update(user, id, "$secret 2", row.dueAt + 60_000L, 2, daily, DueShiftMode.THIS, AlertLevel.PROMINENT)
        fx.commands.setTitle(user, id, "$secret 3")
        fx.commands.setAlertLevel(user, id, AlertLevel.CRITICAL)
        fx.commands.setRepeat(user, id, null)
        fx.commands.shiftDue(user, id, row.dueAt + 600_000L, DueShiftMode.THIS)
        fx.clockMillis = fx.alarms.at(id)!!
        fx.scheduler.onFire(id, fx.clockMillis, fx.clockMillis)
        fx.commands.snooze(tap, id, 0, SnoozeDue.DEFAULTS[0])
        fx.commands.stopAlert(tap, id)
        fx.commands.markDone(tap, id)
        fx.commands.delete(user, id)
        fx.commands.restore(user, id)
        val events = fx.events.forUuid(row.uuid)
        assertTrue(events.size >= 10)
        for (event in events) {
            assertFalse("$event", event.detail.orEmpty().contains("Zebra"))
            assertFalse("$event", event.sourceId.orEmpty().contains("Zebra"))
            assertTrue("detail too long: $event", (event.detail?.length ?: 0) <= EventLog.MAX_DETAIL)
        }
    }

    @Test
    fun qaASnoozeTappedBeforeUnlockIsRecordedOnceWhenRoomOpens() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + 60_000L, 5)!!
        val uuid = fx.dao.getById(id)!!.uuid
        fx.unlocked = false
        fx.commands.snooze(tap, id, 0, SnoozeDue.DEFAULTS[0])
        fx.commands.stopAlert(tap, id)
        assertEquals(1, fx.events.forUuid(uuid).size)
        fx.unlocked = true
        fx.scheduler.rescheduleAll()
        fx.scheduler.rescheduleAll()
        val fresh = fx.events.forUuid(uuid).drop(1)
        assertEquals(listOf(EventType.SNOOZED.name), fresh.map { it.type })
        assertEquals(Actor.SYSTEM.name, fresh.single().actor)
        assertTrue(fresh.single().detail!!.contains("deferred=1"))
        assertEquals(2, fx.dao.getById(id)!!.version)
    }

    @Test
    fun qaPurgeAndRestoreAgreeOnTheThirtyDayBoundary() = runBlocking {
        val fx = schedulerFixture()
        val start = fx.clockMillis
        val id = fx.commands.add(user, "Bins", start + 60_000L, null)!!
        fx.commands.delete(user, id)
        fx.clockMillis = start + 30 * day
        fx.scheduler.rescheduleAll()
        assertNotNull("exactly 30 days: kept", fx.dao.getById(id))
        assertTrue("exactly 30 days: restorable", fx.commands.restore(user, id))
        fx.commands.delete(user, id)
        fx.clockMillis += 30 * day + 1
        assertFalse(fx.commands.restore(user, id))
        fx.scheduler.rescheduleAll()
        assertNull(fx.dao.getById(id))
    }

    @Test
    fun qaDeletingADoneRowKeepsItDoneAndRestoreDoesNotArmIt() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Rego", fx.clockMillis + 60_000L, 5)!!
        fx.commands.markDone(tap, id)
        fx.commands.delete(user, id)
        assertNotNull(fx.dao.getById(id)!!.deletedAt)
        assertTrue(fx.commands.restore(user, id))
        val row = fx.dao.getById(id)!!
        assertTrue(row.isDone)
        assertNull(row.deletedAt)
        assertNull(fx.alarms.at(id))
        assertNull(fx.mirror.get(id))
        assertEquals(4, row.version)
    }

    @Test
    fun qaAZoneChangeBumpsAWallClockRepeatOnceWithASystemEdit() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + day, 5, daily)!!
        val plain = fx.commands.add(user, "Milk", fx.clockMillis + day, 5)!!
        val before = fx.dao.getById(id)!!
        fx.zone = ZoneId.of("Australia/Sydney")
        fx.clockMillis += 1_000L
        fx.scheduler.rescheduleAll()
        val after = fx.dao.getById(id)!!
        assertTrue(after.dueAt != before.dueAt)
        assertEquals(2, after.version)
        val edit = fx.events.forUuid(before.uuid).last()
        assertEquals(EventType.EDITED.name, edit.type)
        assertEquals(Actor.SYSTEM.name, edit.actor)
        assertTrue(edit.detail!!.contains("zone=Australia/Sydney"))
        assertEquals("a one-off reminder is not moved", 1, fx.dao.getById(plain)!!.version)
        fx.scheduler.rescheduleAll()
        assertEquals("no second bump", 2, fx.dao.getById(id)!!.version)
    }

    @Test
    fun qaARealSchemaOneFileImportsAndMergeRestoresTheSoftDeletedMatch() = runBlocking {
        val fx = schedulerFixture()
        val id = fx.commands.add(user, "Bins", fx.clockMillis + day, 5)!!
        val uuid = fx.dao.getById(id)!!.uuid
        fx.commands.delete(user, id)
        val decoded = BackupCodec.decode(schemaOneFile(uuid))
        assertTrue("schema 1 decodes: $decoded", decoded is BackupDecode.Ok)
        val document = (decoded as BackupDecode.Ok).document
        assertEquals(1, document.schemaVersion)
        fx.commands.mergeAll(import, document.reminders.map { it.toReminder(0) })
        val restored = fx.dao.getByUuid(uuid)!!
        assertEquals(id, restored.id)
        assertNull(restored.deletedAt)
        assertEquals("Bins (from file)", restored.title)
        assertEquals("CRITICAL", restored.alertLevel)
        assertEquals("USER", restored.source)
        assertEquals("NONE", restored.approval)
        assertEquals(restored.createdAt, restored.updatedAt)
        assertNotNull(fx.alarms.at(id))
        val other = fx.dao.getByUuid("aaaaaaaa-0000-4000-8000-000000000002")!!
        assertEquals("WEEK", other.repeatUnit)
        assertEquals(1, other.version)
        assertTrue(fx.events.forUuid(uuid).last().detail!!.contains("restored=1"))
    }

    /** A backup as Spec 8 wrote it: schema 1, no Spec 9 keys, quiet-hours keys present. */
    private fun schemaOneFile(uuid: String): String {
        val settings = BackupCodec.encode(
            BackupDocument(
                schemaVersion = 1,
                appId = BackupNames.APP_ID,
                appVersionName = "0.8",
                exportedAt = 1_700_000_000_000L,
                settings = PortableSettings(
                    defaultNagMinutes = 5,
                    snooze = SnoozeDue.DEFAULTS,
                    quickTimes = QuickTimesSettings(QuickTimes.FACTORY, PostponeMode.AUTO),
                    weekStart = WeekStart.MONDAY,
                    parseDates = true,
                    defaultAlertLevel = AlertLevel.STANDARD,
                    repeatPromptMode = RepeatPromptMode.PROMPT,
                    quietEnabled = true,
                    quietStartMinute = 22 * 60,
                    quietEndMinute = 7 * 60,
                    quietFollowDnd = true,
                    quietCriticalPauses = false,
                ),
                reminders = emptyList(),
            ),
        )
        val head = settings.substringBefore("\"reminders\"")
        return head + """
            "reminders": [
              {"uuid": "$uuid", "title": "Bins (from file)", "dueAt": 1800000000000, "nagMinutes": 5,
               "isDone": false, "doneAt": null, "createdAt": 1699000000000, "repeatInterval": null,
               "repeatUnit": null, "repeatFrom": null, "repeatWeekDays": null, "repeatMonthOn": null,
               "patternDueAt": null, "alertLevel": "CRITICAL"},
              {"uuid": "aaaaaaaa-0000-4000-8000-000000000002", "title": "Pills", "dueAt": 1800000600000,
               "nagMinutes": 1, "isDone": false, "doneAt": null, "createdAt": 1699000060000,
               "repeatInterval": 1, "repeatUnit": "WEEK", "repeatFrom": "DUE_DATE", "repeatWeekDays": null,
               "repeatMonthOn": null, "patternDueAt": 1800000600000, "alertLevel": "STANDARD"}
            ]
          }
        """.trimIndent()
    }
}
