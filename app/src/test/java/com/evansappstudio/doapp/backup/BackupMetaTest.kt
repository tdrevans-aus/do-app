package com.evansappstudio.doapp.backup

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.alarm.schedulerFixture
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.WeekStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 9 backup schema 2: the new reminder keys, soft-deleted rows and old files. */
class BackupMetaTest {
    // AT-11
    @Test
    fun at11ExportThenReplaceKeepsEveryNewFieldButDeletedAt() = runBlocking {
        val fx = schedulerFixture()
        val agentId = fx.commands.add(
            Origin(Actor.AGENT, ReminderSource.AGENT, "agent-1"),
            "Rego",
            fx.clockMillis + 60_000L,
            5,
            category = "car",
            templateId = "car-rego",
            templateVersion = 2,
        )!!
        val plainId = fx.commands.add(Origin(Actor.USER), "Milk", fx.clockMillis + 120_000L, null)!!
        val goneId = fx.commands.add(Origin(Actor.USER), "Gone", fx.clockMillis + 180_000L, null)!!
        fx.clockMillis += 5_000L
        fx.commands.setTitle(Origin(Actor.USER), plainId, "Milk and bread")
        val agentRow = fx.dao.getById(agentId)!!
        fx.dao.update(agentRow.copy(approval = "PENDING", requestedAlertLevel = AlertLevel.CRITICAL.name))
        fx.commands.delete(Origin(Actor.USER), goneId)
        val goneUuid = fx.dao.getById(goneId)!!.uuid
        val before = fx.dao.all().sortedBy { it.uuid }

        val text = BackupCodec.encode(document(fx.dao.all().exportable()))
        assertFalse(text.contains(goneUuid))
        assertFalse(text.contains("deletedAt"))
        assertTrue(text.contains("\"schemaVersion\": ${BackupNames.SCHEMA_VERSION}"))
        val decoded = (BackupCodec.decode(text) as BackupDecode.Ok).document
        assertEquals(2, decoded.reminders.size)

        fx.clockMillis += 60_000L
        fx.commands.replaceAll(Origin(Actor.IMPORT), decoded.reminders.map { it.toReminder(0) })
        val after = fx.dao.allIncludingDeleted().sortedBy { it.uuid }
        assertEquals(before.map { it.copy(id = 0) }, after.map { it.copy(id = 0) })
        assertNull(fx.dao.getByUuid(goneUuid))
        val agent = after.first { it.uuid == agentRow.uuid }
        assertEquals("AGENT", agent.source)
        assertEquals("agent-1", agent.sourceId)
        assertEquals("car", agent.category)
        assertEquals("car-rego", agent.templateId)
        assertEquals(2, agent.templateVersion)
        assertEquals("PENDING", agent.approval)
        assertEquals("CRITICAL", agent.requestedAlertLevel)
        assertEquals(2, after.first { it.title == "Milk and bread" }.version)

        val events = fx.events.all()
        assertEquals(2, events.size)
        assertTrue(events.all { it.type == EventType.IMPORTED.name && it.actor == Actor.IMPORT.name })
        assertEquals(after.map { it.uuid }.toSet(), events.map { it.reminderUuid }.toSet())
    }

    // AT-12
    @Test
    fun at12AnOldBackupWithoutTheNewKeysImportsWithTheDefaults() = runBlocking {
        val current = BackupCodec.encode(document(listOf(sample("old-1"), sample("old-2"))))
        val root = parseJson(current).obj()
        val newKeys = setOf(
            "source", "sourceId", "category", "templateId", "templateVersion",
            "updatedAt", "version", "approval", "requestedAlertLevel",
        )
        val reminders = (root.fields["reminders"] as JsonValue.Arr).items.mapIndexed { index, item ->
            val kept = (item as JsonValue.Obj).fields.filterKeys { it !in newKeys }.toMutableMap()
            if (index == 1) {
                kept["source"] = JsonValue.Str("SPACESHIP")
                kept["category"] = JsonValue.Str("boats")
                kept["approval"] = JsonValue.Str("MAYBE")
                kept["version"] = JsonValue.Str("seven")
            }
            JsonValue.Obj(kept)
        }
        val fields = LinkedHashMap(root.fields)
        fields["schemaVersion"] = JsonValue.Num((BackupNames.SCHEMA_VERSION - 1).toLong())
        fields["reminders"] = JsonValue.Arr(reminders)
        val old = encodeJson(JsonValue.Obj(fields))
        assertFalse(old.contains("\"approval\": \"NONE\""))

        val decoded = (BackupCodec.decode(old) as BackupDecode.Ok).document
        assertEquals(BackupNames.SCHEMA_VERSION - 1, decoded.schemaVersion)
        for (row in decoded.reminders) {
            assertEquals("USER", row.source)
            assertNull(row.sourceId)
            assertNull(row.category)
            assertNull(row.templateId)
            assertNull(row.templateVersion)
            assertNull(row.updatedAt)
            assertEquals(1, row.version)
            assertEquals("NONE", row.approval)
            assertNull(row.requestedAlertLevel)
        }

        val fx = schedulerFixture()
        fx.commands.replaceAll(Origin(Actor.IMPORT), decoded.reminders.map { it.toReminder(0) })
        val rows = fx.dao.all()
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertEquals("USER", row.source)
            assertEquals(1, row.version)
            assertEquals(row.createdAt, row.updatedAt)
            assertEquals("NONE", row.approval)
            assertNull(row.deletedAt)
        }
        assertEquals(2, fx.events.count())
    }

    private fun document(reminders: List<PortableReminder>) = BackupDocument(
        schemaVersion = BackupNames.SCHEMA_VERSION,
        appId = BackupNames.APP_ID,
        appVersionName = "1.0",
        exportedAt = 50L,
        settings = PortableSettings(
            defaultNagMinutes = 5,
            snooze = SnoozeDue.DEFAULTS,
            quickTimes = QuickTimesSettings(QuickTimes.FACTORY, PostponeMode.AUTO),
            weekStart = WeekStart.MONDAY,
            parseDates = true,
            defaultAlertLevel = AlertLevel.STANDARD,
            repeatPromptMode = RepeatPromptMode.PROMPT,
        ),
        reminders = reminders,
    )

    private fun sample(uuid: String) = PortableReminder(
        uuid = uuid,
        title = "Bins",
        dueAt = 1_800_000_000_000L,
        nagMinutes = 5,
        isDone = false,
        doneAt = null,
        createdAt = 1_699_000_000_000L,
        repeatInterval = null,
        repeatUnit = null,
        repeatFrom = null,
        repeatWeekDays = null,
        repeatMonthOn = null,
        patternDueAt = null,
        alertLevel = AlertLevel.STANDARD.name,
        source = "CATALOGUE",
        sourceId = "x",
        version = 4,
        updatedAt = 1_699_500_000_000L,
    )
}
