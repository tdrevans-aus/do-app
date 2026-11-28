package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.ApprovalDecision
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.backup.parseJson
import com.evansappstudio.doapp.capture.ShareText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec 13 QA: what an assistant can make the phone do, and malicious inbox payloads.
 * Tests named "QA: fails until ..." expose a bug and are expected to fail until it is fixed.
 */
class Spec13QaTest {
    private fun agent(id: String) = Origin(Actor.AGENT, ReminderSource.AGENT, id)

    // An assistant cannot set nagging, quiet hours or the level through the inbox: only
    // text, dueAt, repeat and alertLevel are read, and Loud or Alarm are held at Normal.
    @Test
    fun anAssistantCannotChangeNaggingOrBypassTheHold() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val quietBefore = cx.fx.quiet
        val json = """
            {"requests":[{"requestId":"rq_000000000000000000000001","agentId":"$AGENT_A","clientName":"Evil",
              "kind":"create_reminder","createdAt":1,"expiresAt":9999999999999,
              "args":{"text":"Wake up","dueAt":${cx.later},"repeat":null,"alertLevel":" Prominent ",
                      "nagMinutes":0,"nag":false,"quietHours":{"enabled":false},"approval":"APPROVED",
                      "source":"USER","sourceId":"person"}}],
             "extra":{"ignored":true}}
        """.trimIndent()
        cx.port.inbox += CloudJson.inbox(parseJson(json, allowFractions = true))
        cx.cloud.runPull()
        val row = cx.fx.dao.all().single()
        assertEquals(5, row.nagMinutes) // the person's default, not the payload's 0
        assertEquals("STANDARD", row.alertLevel)
        assertEquals("PROMINENT", row.requestedAlertLevel)
        assertEquals("PENDING", row.approval)
        assertEquals(ReminderSource.AGENT.name, row.source)
        assertEquals(AGENT_A, row.sourceId)
        assertEquals(quietBefore, cx.fx.quiet)
        // And the assistant cannot settle its own hold.
        assertFalse(cx.fx.commands.resolveApproval(agent(AGENT_A), row.id, ApprovalDecision.ALLOW))
        assertEquals("PENDING", cx.fx.dao.getById(row.id)!!.approval)
    }

    // The free cap holds for every assistant together, and for repeated pulls of the same request.
    @Test
    fun theFreeCapCannotBeBypassedByManyAssistantsOrReplays() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A, AGENT_B)
        cx.fx.paid = false
        val requests = (0 until 4).map { cx.create(AGENT_A, "A $it") } + (0 until 4).map { cx.create(AGENT_B, "B $it") }
        cx.cloud.runPull()
        assertEquals(5, cx.fx.dao.active().size)
        val failed = requests.map { cx.resultOf(it) }.filterIsInstance<RequestResult.Failed>()
        assertEquals(3, failed.size)
        assertTrue(failed.all { it.code == PhoneErrorCode.LIMIT_REACHED })
        // The same requests pulled again (the server lost the results) are answered from the log.
        cx.port.completed.clear()
        val again = cx.cloud.runPull()
        assertEquals(8, again.replayed)
        assertEquals(5, cx.fx.dao.active().size)
    }

    // Oversized text is cut to Do's title limit; it never reaches the database whole.
    @Test
    fun oversizedTextIsCutNotStoredWhole() = runBlocking {
        val cx = CloudFixture().signedIn()
        val huge = "Pay the gas bill " + "x".repeat(200_000)
        val multi = "First line\n" + "second line ".repeat(1000)
        val one = cx.create(AGENT_A, huge)
        val two = cx.create(AGENT_A, multi)
        cx.cloud.runPull()
        val first = (cx.resultOf(one) as RequestResult.Done).reminder
        val second = (cx.resultOf(two) as RequestResult.Done).reminder
        assertTrue(first.title.length <= ShareText.MAX_LENGTH)
        assertTrue(first.title.startsWith("Pay the gas bill"))
        assertEquals("First line", second.title)
        assertTrue(cx.fx.dao.all().all { it.title.length <= ShareText.MAX_LENGTH })
    }

    // Odd dates: beyond Long, negative, 10+ years ahead, a fraction, and a string where a number belongs.
    @Test
    fun oddDatesAreRefusedOrIgnoredNeverScheduled() = runBlocking {
        val cx = CloudFixture().signedIn()
        val later = cx.later
        val json = """
            {"requests":[
              {"requestId":"rq_a","agentId":"$AGENT_A","kind":"create_reminder","args":{"text":"Huge","dueAt":1e300,"alertLevel":"normal"}},
              {"requestId":"rq_b","agentId":"$AGENT_A","kind":"create_reminder","args":{"text":"Negative","dueAt":-5,"alertLevel":"normal"}},
              {"requestId":"rq_c","agentId":"$AGENT_A","kind":"create_reminder","args":{"text":"Far","dueAt":${cx.fx.clockMillis + 11L * 366 * 86_400_000L},"alertLevel":"normal"}},
              {"requestId":"rq_d","agentId":"$AGENT_A","kind":"create_reminder","args":{"text":"Fraction","dueAt":$later.75,"alertLevel":"normal"}},
              {"requestId":"rq_e","agentId":"$AGENT_A","kind":"create_reminder","args":{"text":"Ring the vet tomorrow 9am","dueAt":"2030-01-01","alertLevel":"normal"}},
              {"requestId":"rq_f","agentId":"$AGENT_A","kind":"create_reminder","args":"not an object"},
              {"agentId":"$AGENT_A","kind":"create_reminder","args":{"text":"No id"}},
              {"requestId":"rq_g","agentId":"$AGENT_A","kind":"wipe_phone","args":{}}
            ]}
        """.trimIndent()
        val parsed = CloudJson.inbox(parseJson(json, allowFractions = true))
        assertEquals(7, parsed.size) // the one with no id is dropped
        cx.port.inbox += parsed
        cx.cloud.runPull()
        val byId = cx.port.completed.toMap()
        for (id in listOf("rq_a", "rq_b", "rq_c", "rq_f", "rq_g")) {
            assertEquals(id, PhoneErrorCode.INVALID_ARGUMENT, (byId.getValue(id) as RequestResult.Failed).code)
        }
        // A fraction is truncated to the millisecond; a string dueAt is ignored and the text is parsed.
        assertEquals(later, (byId.getValue("rq_d") as RequestResult.Done).reminder.dueAt)
        val parsedText = (byId.getValue("rq_e") as RequestResult.Done).reminder
        assertEquals("Ring the vet", parsedText.title)
        assertEquals(2, cx.fx.dao.all().size)
        assertTrue(cx.fx.dao.all().all { it.dueAt < cx.fx.clockMillis + 11L * 365 * 86_400_000L })
    }

    // A cloud assistant may complete only an active reminder that it created (uuid lookups of anything else look the same).
    @Test
    fun completeRefusesDeletedDoneAndOtherPeoplesReminders() = runBlocking {
        val cx = CloudFixture().signedIn()
        val c = cx.fx.commands
        val deleted = cx.fx.dao.getById(c.add(agent(AGENT_A), "Deleted", cx.later, null)!!)!!
        c.delete(Origin(Actor.USER), deleted.id)
        val requests = listOf(cx.complete(AGENT_A, deleted.uuid), cx.complete(AGENT_A, " "), cx.complete(AGENT_A, "' OR 1=1 --"))
        cx.cloud.runPull()
        assertEquals(PhoneErrorCode.NOT_FOUND, (cx.resultOf(requests[0]) as RequestResult.Failed).code)
        assertEquals(PhoneErrorCode.INVALID_ARGUMENT, (cx.resultOf(requests[1]) as RequestResult.Failed).code)
        assertEquals(PhoneErrorCode.NOT_FOUND, (cx.resultOf(requests[2]) as RequestResult.Failed).code)
        assertFalse(cx.fx.dao.getById(deleted.id)!!.isDone)
    }

    // QA: fails until CloudInbox refuses requests whose agentId is not a Do Cloud agent id (ag_ plus 16 hex).
    // The phone trusts the inbox's agentId. A request naming an on-phone (AppFunctions) agent's caller id,
    // a person-shaped id or an empty one can complete that caller's reminders or create reminders under it.
    // The server only writes ag_ ids, so this is defence in depth against a bad or compromised server.
    @Test
    fun qaFailsUntilNonCloudAgentIdsAreRefused() = runBlocking {
        val cx = CloudFixture().signedIn()
        val gemini = "com.google.android.apps.bard"
        val onPhone = cx.fx.dao.getById(cx.fx.commands.add(agent(gemini), "Gemini's", cx.later, null)!!)!!
        val complete = cx.complete(gemini, onPhone.uuid)
        val create = cx.create("", "Made under no assistant")
        cx.cloud.runPull()
        assertFalse("QA: fails until CloudInbox refuses non-ag_ agent ids (an on-phone agent's reminder was completed by a cloud request)", cx.fx.dao.getById(onPhone.id)!!.isDone)
        assertTrue(cx.resultOf(complete) is RequestResult.Failed)
        assertTrue(cx.resultOf(create) is RequestResult.Failed)
    }

    // The mirror never sends a non-cloud agent's reminders, even if such an id is in the connected list.
    @Test
    fun mirrorIgnoresNonCloudAgentIds() = runBlocking {
        val cx = CloudFixture().signedIn()
        val gemini = "com.google.android.apps.bard"
        cx.fx.commands.add(agent(gemini), "Gemini private", cx.later, null)
        cx.fx.commands.add(agent(AGENT_A), "A's", cx.later, null)
        val plan = MirrorPlanner.plan(listOf(gemini, AGENT_A, "", "ag_"), cx.fx.dao.allIncludingDeleted(), cx.fx.events.after(0, 1000), 0L, emptyMap())
        assertEquals(listOf(AGENT_A), plan.batches.map { it.agentId }.distinct())
        assertFalse(plan.batches.flatMap { it.reminders }.any { it.title.contains("Gemini") })
    }

    // A cloud failure never touches reminders or their alarms.
    @Test
    fun aFailedPullLeavesRemindersAndAlarmsAlone() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val id = cx.fx.commands.add(Origin(Actor.USER), "Mine", cx.later, null)!!
        cx.fx.commands.add(agent(AGENT_A), "A's, so the mirror has something to send", cx.later, null)!!
        val before = cx.fx.dao.getById(id)!!
        val armedBefore = cx.fx.alarms.at(id)
        cx.create(AGENT_A, "Never applied")
        cx.port.failPull = CloudCallCode.UNAVAILABLE
        try {
            cx.cloud.runPull()
            fail("expected the pull failure to surface for a retry")
        } catch (error: CloudException) {
            assertEquals(CloudCallCode.UNAVAILABLE, error.code)
        }
        cx.port.failSync = mapOf(AGENT_A to CloudCallCode.INTERNAL)
        try {
            cx.cloud.runMirror()
            fail("expected the mirror failure to surface for a retry")
        } catch (error: CloudException) {
            assertEquals(CloudCallCode.INTERNAL, error.code)
        }
        assertEquals(before, cx.fx.dao.getById(id))
        assertEquals(2, cx.fx.dao.all().size)
        assertEquals(armedBefore, cx.fx.alarms.at(id))
        assertTrue(armedBefore != null)
    }

    // With the cloud off, the push service and every trigger do nothing and build no port.
    @Test
    fun cloudOffIgnoresPushesAndPurchases() = runBlocking {
        val cx = CloudFixture(config = CloudConfig.OFF)
        cx.store.setIdentityUid("uid-left-over")
        cx.cloud.onPush(mapOf("kind" to "inbox"))
        cx.cloud.onAppStart()
        cx.cloud.onRemindersChanged()
        assertEquals(VerifyOutcome.OFF, cx.cloud.onPurchase("do_unlimited", "tok", true, "com.evansappstudio.doapp"))
        assertEquals(PullRun(0, 0, 0, 0, 0), cx.cloud.runPull())
        assertNull(cx.cloud.runMirror())
        assertNull(cx.cloud.connectAssistant())
        assertTrue(cx.jobs.pulls.isEmpty())
        assertEquals(0, cx.portBuilt)
        assertNull(CloudConfig.OFF.mcpUrl())
    }

    // The applied-request log is bounded, so a flood of requests cannot grow prefs without limit.
    @Test
    fun theAppliedLogIsBounded() {
        val store = CloudStore(com.evansappstudio.doapp.billing.MemoryPrefs())
        val result = RequestResult.Failed(PhoneErrorCode.INVALID_ARGUMENT, "x")
        for (i in 0 until CloudStore.APPLIED_MAX + 100) store.recordApplied("rq_$i", result, 1_000L + i)
        assertTrue(store.appliedCount() <= CloudStore.APPLIED_MAX)
        assertNull(store.appliedResult("rq_0"))
    }
}
