package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.billing.FreeTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CaptureCommandsTest {
    // AT-2 (each surface's entry maps to its Origin)
    @Test
    fun at2EntriesMapToTheirOrigins() {
        assertEquals(Origin(Actor.USER, ReminderSource.SHARE, "com.whatsapp"), CaptureEntry.SHARE.origin("com.whatsapp"))
        assertEquals(Origin(Actor.USER, ReminderSource.WIDGET), CaptureEntry.WIDGET.origin())
        for (entry in listOf(CaptureEntry.APP, CaptureEntry.TILE, CaptureEntry.SHORTCUT, CaptureEntry.DEEP_LINK)) {
            assertEquals(Origin(Actor.USER, ReminderSource.USER), entry.origin())
        }
    }

    // AT-2 (Share saves through the commands with Origin(USER, SHARE, caller))
    @Test
    fun at2ShareSavesWithTheShareOrigin() = runBlocking {
        val cx = CaptureFixture()
        val draft = CaptureDrafts.fromText("Grab the dry cleaning Friday after work", cx.fx.clockMillis, PERTH)!!
        val result = cx.capture.saveShare(draft, draft.title, null, 5, AlertLevel.STANDARD, "com.whatsapp")
        val id = (result as CaptureResult.Saved).id
        val row = cx.fx.dao.getById(id)!!
        assertEquals("SHARE", row.source)
        assertEquals("com.whatsapp", row.sourceId)
        assertEquals("Grab the dry cleaning", row.title)
        assertEquals(perth(2023, 11, 17, 17, 30), row.dueAt)
        val event = cx.fx.events.forUuid(row.uuid).single()
        assertEquals(EventType.CREATED.name, event.type)
        assertEquals(Actor.USER.name, event.actor)
        assertEquals("com.whatsapp", event.sourceId)
    }

    // AT-2 (a quick choice replaces the parsed date and drops a parsed repeat; no caller is stored as null)
    @Test
    fun at2ShareQuickChoiceAndNoCaller() = runBlocking {
        val cx = CaptureFixture()
        val draft = CaptureDrafts.fromText("Bins every day", cx.fx.clockMillis, PERTH)!!
        val tomorrow = CaptureTimes.tomorrow(cx.fx.clockMillis, PERTH)
        val id = (cx.capture.saveShare(draft, "  ", tomorrow, null, AlertLevel.STANDARD, null) as CaptureResult.Saved).id
        val row = cx.fx.dao.getById(id)!!
        assertEquals("Bins", row.title)
        assertEquals(tomorrow, row.dueAt)
        assertNull(row.repeatRule())
        assertNull(row.sourceId)
        assertEquals("SHARE", row.source)
    }

    // AT-2 (the widget's add row and its tick use the widget Origin)
    @Test
    fun at2WidgetAddAndDoneUseTheWidgetOrigin() = runBlocking {
        val cx = CaptureFixture()
        val id = (cx.capture.add(CaptureEntry.WIDGET, "Water plants", cx.later, 5) as CaptureResult.Saved).id
        val row = cx.fx.dao.getById(id)!!
        assertEquals("WIDGET", row.source)
        cx.capture.widgetDone(id)
        assertTrue(cx.fx.dao.getById(id)!!.isDone)
        val events = cx.fx.events.forUuid(row.uuid)
        assertEquals(listOf(EventType.CREATED.name, EventType.DONE.name), events.map { it.type })
        assertTrue(events.all { it.actor == Actor.USER.name })
    }

    // AT-2 (the tile, shortcuts and links open Add, which saves as the person)
    @Test
    fun at2TileShortcutAndLinkSaveAsUser() = runBlocking {
        val cx = CaptureFixture()
        for (entry in listOf(CaptureEntry.TILE, CaptureEntry.SHORTCUT, CaptureEntry.DEEP_LINK, CaptureEntry.APP)) {
            val id = (cx.capture.add(entry, "From $entry", cx.later, null) as CaptureResult.Saved).id
            assertEquals("USER", cx.fx.dao.getById(id)!!.source)
        }
    }

    // AT-2 (AppFunctions write with Origin(AGENT, AGENT, caller))
    @Test
    fun at2AgentUsesTheAgentOrigin() = runBlocking {
        val cx = CaptureFixture()
        val made = cx.agent.createReminder("com.google.android.apps.bard", AgentCreateRequest(text = "Call the vet tomorrow at 10am"))
        val row = cx.fx.dao.getById(made.id)!!
        assertEquals("AGENT", row.source)
        assertEquals("com.google.android.apps.bard", row.sourceId)
        assertEquals("Call the vet", row.title)
        assertEquals(perth(2023, 11, 16, 10, 0), row.dueAt)
        assertEquals(5, row.nagMinutes)
        cx.agent.completeReminder("com.google.android.apps.bard", made.id)
        val events = cx.fx.events.forUuid(row.uuid)
        assertEquals(listOf(EventType.CREATED.name, EventType.DONE.name), events.map { it.type })
        assertTrue(events.all { it.actor == Actor.AGENT.name && it.sourceId == "com.google.android.apps.bard" })
    }

    // AT-3 (a free user at 5 is sent to the paywall from Share and from the widget's add row)
    @Test
    fun at3CapSendsShareAndWidgetToThePaywall() = runBlocking {
        val cx = CaptureFixture()
        cx.fx.paid = false
        val cap = FreeTier.ACTIVE_REMINDER_CAP!!
        repeat(cap) { index -> cx.capture.add(CaptureEntry.APP, "R$index", cx.later, null) }
        val draft = CaptureDrafts.fromText("One more", cx.fx.clockMillis, PERTH)!!
        assertEquals(CaptureResult.NeedsUnlock, cx.capture.saveShare(draft, draft.title, null, null, AlertLevel.STANDARD, "com.whatsapp"))
        assertEquals(CaptureResult.NeedsUnlock, cx.capture.add(CaptureEntry.WIDGET, "Widget one", cx.later, null))
        assertEquals(cap, cx.fx.dao.activeCount())
        cx.fx.paid = true
        assertTrue(cx.capture.add(CaptureEntry.WIDGET, "Widget one", cx.later, null) is CaptureResult.Saved)
    }

    // AT-3 (AppFunctions get a clear "limit reached" error instead)
    @Test
    fun at3CapGivesTheAgentALimitReachedError() = runBlocking {
        val cx = CaptureFixture()
        cx.fx.paid = false
        repeat(FreeTier.ACTIVE_REMINDER_CAP!!) { index -> cx.capture.add(CaptureEntry.APP, "R$index", cx.later, null) }
        try {
            cx.agent.createReminder("agent", AgentCreateRequest(text = "Over the cap"))
            fail("expected LimitReached")
        } catch (error: AgentError.LimitReached) {
            assertTrue(error.message!!.startsWith("Limit reached"))
            assertTrue(error.message!!.contains("5 active reminders"))
        }
        assertEquals(5, cx.fx.dao.activeCount())
    }

    @Test
    fun commandListenersRunAfterEachCommandForTheWidgets() = runBlocking {
        val cx = CaptureFixture()
        var calls = 0
        val listener = { calls++ ; Unit }
        cx.fx.commands.addListener(listener)
        cx.fx.commands.addListener { error("a broken listener is skipped") }
        val id = (cx.capture.add(CaptureEntry.APP, "Milk", cx.later, null) as CaptureResult.Saved).id
        cx.capture.widgetDone(id)
        cx.fx.commands.delete(Origin(Actor.USER), id)
        assertEquals(3, calls)
        cx.fx.commands.removeListener(listener)
        cx.fx.commands.restore(Origin(Actor.USER), id)
        assertEquals(3, calls)
    }
}
