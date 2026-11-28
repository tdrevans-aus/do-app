package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.backup.encodeJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Spec 13 AT-11: mirror sync sends only agent-created reminders, only to their own assistant. */
class MirrorSyncTest {
    private fun agentOrigin(id: String) = Origin(Actor.AGENT, ReminderSource.AGENT, id)

    private suspend fun CloudFixture.seed(): Map<String, Long> {
        val c = fx.commands
        return mapOf(
            "a1" to c.add(agentOrigin(AGENT_A), "A first", later, null)!!,
            "a2" to c.add(agentOrigin(AGENT_A), "A second", later, null)!!,
            "b1" to c.add(agentOrigin(AGENT_B), "B first", later, null)!!,
            "person" to c.add(Origin(Actor.USER), "Person private", later, null)!!,
            "catalogue" to c.add(Origin(Actor.USER, ReminderSource.CATALOGUE, "renter-smoke-alarm"), "Catalogue private", later, null)!!,
            "gemini" to c.add(agentOrigin("com.google.android.apps.bard"), "Gemini private", later, null)!!,
            // An agent id that is not connected to this device.
            "stranger" to c.add(agentOrigin("ag_1111111111111111"), "Stranger private", later, null)!!,
        )
    }

    // AT-11 (each assistant gets exactly its own reminders and their events; nobody else's)
    @Test
    fun at11EachAssistantGetsOnlyItsOwnReminders() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A, AGENT_B)
        val ids = cx.seed()
        val run = cx.cloud.runMirror()!!
        assertEquals(2, run.batches)
        val byAgent = cx.port.synced.associateBy { it.agentId }
        assertEquals(setOf(AGENT_A, AGENT_B), byAgent.keys)
        val uuid = { key: String -> runBlocking { cx.fx.dao.getById(ids.getValue(key))!!.uuid } }
        assertEquals(setOf(uuid("a1"), uuid("a2")), byAgent.getValue(AGENT_A).reminders.map { it.uuid }.toSet())
        assertEquals(setOf(uuid("b1")), byAgent.getValue(AGENT_B).reminders.map { it.uuid }.toSet())
        assertEquals(setOf(uuid("a1"), uuid("a2")), byAgent.getValue(AGENT_A).events.map { it.reminderUuid }.toSet())
        assertEquals(listOf("CREATED"), byAgent.getValue(AGENT_B).events.map { it.type })
        // Nothing private leaves the phone, in any form.
        val sent = cx.port.synced.joinToString("\n") { encodeJson(CloudJson.mirror(it)) }
        for (word in listOf("Person", "Catalogue", "Gemini", "Stranger")) assertFalse(word, sent.contains(word))
        for (key in listOf("person", "catalogue", "gemini", "stranger")) assertFalse(key, sent.contains(uuid(key)))
        // Events carry id, uuid, type and time only: no detail, actor or title.
        assertFalse(sent.contains("detail"))
        assertFalse(sent.contains("actor"))
        assertFalse(sent.contains("due="))
    }

    // AT-11 (a second sync with no change sends nothing; a Done sends just that reminder and its DONE)
    @Test
    fun at11OnlyChangesAreSentAgain() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A, AGENT_B)
        val ids = cx.seed()
        cx.cloud.runMirror()
        cx.port.synced.clear()
        assertEquals(0, cx.cloud.runMirror()!!.batches)
        cx.fx.commands.markDone(Origin(Actor.USER), ids.getValue("a2"))
        cx.fx.commands.markDone(Origin(Actor.USER), ids.getValue("person"))
        cx.cloud.runMirror()
        val batch = cx.port.synced.single()
        assertEquals(AGENT_A, batch.agentId)
        val row = cx.fx.dao.getById(ids.getValue("a2"))!!
        assertEquals(listOf(row.uuid), batch.reminders.map { it.uuid })
        assertEquals("done", batch.reminders.single().status)
        assertEquals(row.version, batch.reminders.single().version)
        assertEquals(listOf("DONE"), batch.events.map { it.type })
        // A soft delete is sent as deleted.
        cx.port.synced.clear()
        cx.fx.commands.delete(Origin(Actor.USER), ids.getValue("a1"))
        cx.cloud.runMirror()
        assertEquals("deleted", cx.port.synced.single().reminders.single().status)
        assertEquals(listOf("DELETED"), cx.port.synced.single().events.map { it.type })
    }

    // AT-11 (fires are sent too, and the listener and fire hook ask for a sync only when an assistant is connected)
    @Test
    fun at11FiresAreSentAndTheTriggerNeedsAConnectedAssistant() = runBlocking {
        val bare = CloudFixture().signedIn()
        bare.cloud.onRemindersChanged()
        assertEquals(0, bare.jobs.mirrors)
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val id = cx.fx.commands.add(agentOrigin(AGENT_A), "A fires", cx.fx.clockMillis + 1_000L, 5)!!
        cx.cloud.runMirror()
        cx.port.synced.clear()
        cx.fx.clockMillis += 2_000L
        cx.fx.scheduler.onFire(id, cx.fx.clockMillis)
        cx.cloud.runMirror()
        assertEquals(listOf("FIRED"), cx.port.synced.single().events.map { it.type })
        assertTrue(cx.port.synced.single().reminders.isEmpty())
        cx.cloud.onRemindersChanged()
        assertEquals(1, cx.jobs.mirrors)
    }

    // AT-11 (a disconnected assistant is dropped; a network failure resends everything next time)
    @Test
    fun at11GoneAssistantsAreDroppedAndFailuresResend() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A, AGENT_B)
        cx.seed()
        cx.port.failSync = mapOf(AGENT_B to CloudCallCode.NOT_FOUND)
        val run = cx.cloud.runMirror()!!
        assertEquals(listOf(AGENT_B), run.goneAgents)
        assertEquals(listOf(AGENT_A), cx.store.agents().map { it.agentId })

        val retry = CloudFixture().signedIn().connect(AGENT_A)
        retry.seed()
        retry.port.failSync = mapOf(AGENT_A to CloudCallCode.UNAVAILABLE)
        try {
            retry.cloud.runMirror()
            fail("expected unavailable")
        } catch (error: CloudException) {
            assertEquals(CloudCallCode.UNAVAILABLE, error.code)
        }
        assertEquals(0L, retry.store.mirrorCursor())
        retry.port.failSync = emptyMap()
        retry.cloud.runMirror()
        assertEquals(2, retry.port.synced.single().reminders.size)
        assertEquals(2, retry.port.synced.single().events.size)
    }

    // AT-11 (the planner on its own: chunks to the contract's limits, skips ids that are not agent ids)
    @Test
    fun at11PlannerChunksAndIgnoresNonAgentIds() = runBlocking {
        val cx = CloudFixture()
        repeat(MirrorBatch.MAX_REMINDERS + 3) { cx.fx.commands.add(agentOrigin(AGENT_A), "R$it", cx.later, null) }
        val plan = MirrorPlanner.plan(
            agentIds = listOf(AGENT_A, "com.google.android.apps.bard", "not an id"),
            reminders = cx.fx.dao.allIncludingDeleted(),
            events = cx.fx.events.all(),
            cursor = 0L,
            synced = emptyMap(),
        )
        assertEquals(2, plan.batches.size)
        assertTrue(plan.batches.all { it.agentId == AGENT_A })
        assertEquals(MirrorBatch.MAX_REMINDERS, plan.batches[0].reminders.size)
        assertEquals(3, plan.batches[1].reminders.size)
        assertTrue(plan.batches.all { it.events.size <= MirrorBatch.MAX_EVENTS })
        assertEquals(cx.fx.events.all().maxOf { it.id }, plan.cursor)
    }

    // AT-11 (a result sent with completeRequest counts as synced: the next sync does not resend it)
    @Test
    fun at11AnInboxResultCountsAsSynced() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        cx.create(AGENT_A, "From the inbox")
        cx.cloud.runPull()
        cx.cloud.runMirror()
        val batch = cx.port.synced.single()
        assertTrue(batch.reminders.isEmpty())
        assertEquals(listOf("CREATED"), batch.events.map { it.type })
    }
}
