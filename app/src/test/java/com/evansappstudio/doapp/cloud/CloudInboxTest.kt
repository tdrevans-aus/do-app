package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.capture.AgentError
import com.evansappstudio.doapp.capture.perth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Spec 13 AT-10: inbox requests go through AgentFunctions and ReminderCommands with the agent origin. */
class CloudInboxTest {
    // AT-10 (a create runs as Origin(AGENT, AGENT, agentId) and comes back done with the reminder)
    @Test
    fun at10CreateRunsWithTheAgentOriginAndReportsTheReminder() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val request = cx.create(AGENT_A, "Take the bins out", dueAt = cx.later)
        val run = cx.cloud.runPull()
        assertEquals(PullRun(pulled = 1, applied = 1, replayed = 0, completed = 1, deferred = 0), run)
        val row = cx.fx.dao.all().single()
        assertEquals(ReminderSource.AGENT.name, row.source)
        assertEquals(AGENT_A, row.sourceId)
        assertEquals("Take the bins out", row.title)
        assertEquals(5, row.nagMinutes)
        val created = cx.fx.events.forUuid(row.uuid).single()
        assertEquals(EventType.CREATED.name, created.type)
        assertEquals(Actor.AGENT.name, created.actor)
        assertEquals(AGENT_A, created.sourceId)
        val result = cx.resultOf(request) as RequestResult.Done
        assertEquals(
            WireReminder(
                uuid = row.uuid,
                title = "Take the bins out",
                dueAt = cx.later,
                status = "active",
                alertLevel = "STANDARD",
                requestedAlertLevel = "STANDARD",
                approval = "NONE",
                repeat = null,
                version = 1,
                updatedAt = cx.fx.clockMillis,
            ),
            result.reminder,
        )
        // The applied result is remembered and a mirror sync follows.
        assertEquals(1, cx.store.appliedCount())
        assertTrue(cx.jobs.mirrors >= 1)
    }

    // AT-10 (text with no dueAt is parsed with Do's own parser, repeat with DateParser)
    @Test
    fun at10TextIsParsedOnThePhone() = runBlocking {
        val cx = CloudFixture().signedIn()
        val request = cx.create(AGENT_A, "call Mum tomorrow 6pm", dueAt = null, repeat = "every day")
        cx.cloud.runPull()
        val wire = (cx.resultOf(request) as RequestResult.Done).reminder
        assertEquals("call Mum", wire.title)
        assertEquals(perth(2023, 11, 16, 18, 0), wire.dueAt)
        assertTrue(wire.repeat!!.isNotEmpty())
    }

    // AT-10 (hold rule: Loud and Alarm are made at Normal, PENDING; the server reads that as held_for_approval)
    @Test
    fun at10LoudAndAlarmAreHeldAtNormal() = runBlocking {
        val cx = CloudFixture().signedIn()
        val loud = cx.create(AGENT_A, "Loud one", alertLevel = "loud")
        val alarm = cx.create(AGENT_A, "Alarm one", alertLevel = "alarm")
        cx.cloud.runPull()
        for ((request, asked) in listOf(loud to "CRITICAL", alarm to "PROMINENT")) {
            val wire = (cx.resultOf(request) as RequestResult.Done).reminder
            assertEquals("STANDARD", wire.alertLevel)
            assertEquals(asked, wire.requestedAlertLevel)
            assertEquals("PENDING", wire.approval)
            assertEquals("STANDARD", cx.fx.dao.getByUuid(wire.uuid)!!.alertLevel)
        }
    }

    // AT-10 ("Ask me before it can ring through" off: the person's rule allows it at once)
    @Test
    fun at10AskBeforeOffAllowsRingThrough() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.cloud.setAskBeforeRingThrough(false)
        val request = cx.create(AGENT_A, "Alarm one", alertLevel = "alarm")
        cx.cloud.runPull()
        val wire = (cx.resultOf(request) as RequestResult.Done).reminder
        assertEquals("PROMINENT", wire.alertLevel)
        assertEquals("APPROVED", wire.approval)
        val row = cx.fx.dao.getByUuid(wire.uuid)!!
        assertEquals("PROMINENT", row.alertLevel)
        val events = cx.fx.events.forUuid(row.uuid).map { it.type to it.actor }
        assertTrue(events.contains(EventType.LEVEL_CHANGED.name to Actor.SYSTEM.name))
    }

    // AT-10 (the free cap: a sixth active reminder is limit_reached with the adapter's own message)
    @Test
    fun at10FreeCapIsLimitReached() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.fx.paid = false
        repeat(5) { cx.fx.commands.add(Origin(Actor.USER), "Mine $it", cx.later, null) }
        val request = cx.create(AGENT_A, "One too many")
        cx.cloud.runPull()
        val failed = cx.resultOf(request) as RequestResult.Failed
        assertEquals(PhoneErrorCode.LIMIT_REACHED, failed.code)
        assertEquals(AgentError.LimitReached(5).message, failed.message)
        assertTrue(failed.message!!.contains("5"))
        assertEquals(5, cx.fx.dao.active().size)
    }

    // AT-10 (invalid_argument: blank text, an unknown level, a bad repeat, an unknown kind)
    @Test
    fun at10BadArgumentsAreInvalidArgument() = runBlocking {
        val cx = CloudFixture().signedIn()
        val requests = listOf(
            cx.create(AGENT_A, "   "),
            cx.create(AGENT_A, "Thing", alertLevel = "deafening"),
            cx.create(AGENT_A, "Thing", repeat = "whenever the moon is full"),
            cx.create(AGENT_A, "Thing", dueAt = cx.fx.clockMillis - 86_400_000L),
            cx.request(AGENT_A, "delete_everything", InboxArgs.Unknown),
            cx.complete(AGENT_A, null),
        )
        cx.cloud.runPull()
        for (request in requests) {
            val failed = cx.resultOf(request) as RequestResult.Failed
            assertEquals(request.kind, PhoneErrorCode.INVALID_ARGUMENT, failed.code)
            assertTrue(failed.message!!.length <= PhoneErrorCode.MAX_MESSAGE)
        }
        assertTrue(cx.fx.dao.all().isEmpty())
    }

    // AT-10 (the assistants switch off on the phone answers disabled and writes nothing)
    @Test
    fun at10AssistantsOffIsDisabled() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.cloud.setAssistantsEnabled(false)
        assertEquals(listOf(false), cx.port.enabledCalls)
        val request = cx.create(AGENT_A, "Thing")
        cx.cloud.runPull()
        assertEquals(PhoneErrorCode.DISABLED, (cx.resultOf(request) as RequestResult.Failed).code)
        assertTrue(cx.fx.dao.all().isEmpty())
    }

    // AT-10 (complete_reminder by uuid: only this assistant's own active reminder; everything else is not_found)
    @Test
    fun at10CompleteOnlyTouchesThisAssistantsReminders() = runBlocking {
        val cx = CloudFixture().signedIn()
        val mine = cx.fx.dao.getById(cx.fx.commands.add(Origin(Actor.AGENT, ReminderSource.AGENT, AGENT_A), "A's", cx.later, null)!!)!!
        val other = cx.fx.dao.getById(cx.fx.commands.add(Origin(Actor.AGENT, ReminderSource.AGENT, AGENT_B), "B's", cx.later, null)!!)!!
        val person = cx.fx.dao.getById(cx.fx.commands.add(Origin(Actor.USER), "Person's", cx.later, null)!!)!!
        val onPhone = cx.fx.dao.getById(cx.fx.commands.add(Origin(Actor.AGENT, ReminderSource.AGENT, "com.google.gemini"), "Gemini's", cx.later, null)!!)!!
        val ok = cx.complete(AGENT_A, mine.uuid)
        val refused = listOf(
            cx.complete(AGENT_A, other.uuid),
            cx.complete(AGENT_A, person.uuid),
            cx.complete(AGENT_A, onPhone.uuid),
            cx.complete(AGENT_A, "no-such-uuid"),
        )
        cx.cloud.runPull()
        val done = (cx.resultOf(ok) as RequestResult.Done).reminder
        assertEquals("done", done.status)
        assertEquals(mine.version + 1, done.version)
        assertTrue(cx.fx.dao.getById(mine.id)!!.isDone)
        val doneEvent = cx.fx.events.forUuid(mine.uuid).last()
        assertEquals(EventType.DONE.name to Actor.AGENT.name, doneEvent.type to doneEvent.actor)
        for (request in refused) {
            val failed = cx.resultOf(request) as RequestResult.Failed
            assertEquals(PhoneErrorCode.NOT_FOUND, failed.code)
            assertEquals(CloudInbox.NOT_FOUND_MESSAGE, failed.message)
        }
        for (row in listOf(other, person, onPhone)) assertFalse(cx.fx.dao.getById(row.id)!!.isDone)
        // A second complete of the same (now done) reminder is not_found.
        val again = cx.complete(AGENT_A, mine.uuid)
        cx.cloud.runPull()
        assertEquals(PhoneErrorCode.NOT_FOUND, (cx.resultOf(again) as RequestResult.Failed).code)
    }

    // AT-10 (a request pulled twice is applied once: completeRequest failed the first time)
    @Test
    fun at10ARetriedRequestIsNeverAppliedTwice() = runBlocking {
        val cx = CloudFixture().signedIn()
        val request = cx.create(AGENT_A, "Only once")
        cx.port.failComplete = CloudCallCode.UNAVAILABLE
        try {
            cx.cloud.runPull()
            fail("expected the unavailable completeRequest to surface for a retry")
        } catch (error: CloudException) {
            assertEquals(CloudCallCode.UNAVAILABLE, error.code)
        }
        assertEquals(1, cx.fx.dao.all().size)
        cx.port.failComplete = null
        val run = cx.cloud.runPull()
        assertEquals(1, run.replayed)
        assertEquals(0, run.applied)
        assertEquals(1, cx.fx.dao.all().size)
        assertEquals(cx.fx.dao.all().single().uuid, (cx.resultOf(request) as RequestResult.Done).reminder.uuid)
    }

    // AT-10 (failure messages never carry a reminder title)
    @Test
    fun at10NoTitleInAnyFailureMessage() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.fx.paid = false
        repeat(5) { cx.fx.commands.add(Origin(Actor.AGENT, ReminderSource.AGENT, AGENT_A), "Secret title $it", cx.later, null) }
        val requests = listOf(
            cx.create(AGENT_A, "Secret title 9"),
            cx.create(AGENT_A, "Secret title 9", alertLevel = "boom"),
            cx.complete(AGENT_B, cx.fx.dao.all().first().uuid),
        )
        cx.cloud.runPull()
        for (request in requests) {
            val failed = cx.resultOf(request) as RequestResult.Failed
            assertFalse(failed.message.orEmpty(), failed.message.orEmpty().contains("Secret"))
        }
    }

    // AT-10 (the push enqueues an expedited pull; nothing else in a push means anything)
    @Test
    fun at10ThePushEnqueuesAnExpeditedPull() {
        val cx = CloudFixture().signedIn()
        cx.cloud.onPush(mapOf("kind" to "inbox"))
        cx.cloud.onPush(mapOf("kind" to "something else"))
        cx.cloud.onPush(emptyMap())
        assertEquals(listOf(true), cx.jobs.pulls)
        cx.cloud.onAppStart()
        cx.cloud.onNetworkAvailable()
        assertEquals(listOf(true, false, false), cx.jobs.pulls)
    }

    // AT-10 (no identity yet: no pull, no sign-in from the background)
    @Test
    fun at10NoIdentityMeansNoBackgroundSignIn() = runBlocking {
        val cx = CloudFixture()
        cx.cloud.onPush(mapOf("kind" to "inbox"))
        cx.cloud.onAppStart()
        assertTrue(cx.jobs.pulls.isEmpty())
        assertEquals(PullRun(0, 0, 0, 0, 0), cx.cloud.runPull())
        assertTrue(cx.port.calls.isEmpty())
    }

    // AT-10 (first connect signs in, registers the FCM token once, then makes a pairing code)
    @Test
    fun at10ConnectSignsInRegistersAndPairs() = runBlocking {
        val cx = CloudFixture()
        val code = cx.cloud.connectAssistant()!!
        assertEquals("ABCD 2345", code.display())
        assertEquals("uid-1", cx.store.identityUid())
        assertEquals(listOf(RegisterDevice("fcm-token-1", "android", "1.0")), cx.port.registered)
        assertEquals(code, cx.cloud.ui.value.pairing)
        assertEquals("http://10.0.2.2:5002/mcp", cx.cloud.ui.value.mcpUrl)
        cx.cloud.connectAssistant()
        assertEquals(1, cx.port.registered.size)
        assertEquals(1, cx.port.calls.count { it == "signIn" })
        // A token refresh registers again.
        cx.cloud.onNewToken("fcm-token-2")
        assertEquals("fcm-token-2", cx.port.registered.last().fcmToken)
    }

    // AT-10 (no FCM token, as on the emulator project: pairing and pulls still work)
    @Test
    fun at10NoMessagingTokenStillPairsAndPulls() = runBlocking {
        val cx = CloudFixture()
        cx.port.token = null
        assertTrue(cx.cloud.connectAssistant() != null)
        assertTrue(cx.port.registered.isEmpty())
        cx.create(AGENT_A, "Thing")
        assertEquals(1, cx.cloud.runPull().completed)
        assertNull(cx.store.registeredToken())
    }

    // AT-10 (a request whose assistant was disconnected meanwhile is not a pull failure)
    @Test
    fun at10ARejectedCompletionForAGoneAssistantIsSkipped() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.create(AGENT_A, "Thing")
        cx.port.failComplete = CloudCallCode.NOT_FOUND
        val run = cx.cloud.runPull()
        assertEquals(0, run.completed)
        assertEquals(AlertLevel.STANDARD.name, cx.fx.dao.all().single().alertLevel)
    }
}
