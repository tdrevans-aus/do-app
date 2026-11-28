package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 13 after QA: L2 (names shown plainly) and L5 (only paired Do Cloud assistants act). */
class AgentNameAndIdTest {
    // L2: control and format characters go, emoji stay.
    @Test
    fun namesLoseBidiAndFormatCharactersButKeepEmoji() {
        assertEquals("Claude", CloudAgent.displayName("Clau‮de"))
        assertEquals("evil.com", CloudAgent.displayName("‮moc.live‬".reversed().let { "evil.com⁦⁩‎‏" }))
        for (bidi in listOf('‎', '‏', '‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩', '؜', '﻿', '​')) {
            assertEquals("Ab", CloudAgent.displayName("A${bidi}b"))
        }
        assertEquals("Line one two", CloudAgent.displayName("Line\none \ttwo\u0000"))
        // Tag characters (invisible text) go; private use goes.
        assertEquals("Hi", CloudAgent.displayName("H󠁁i"))
        // Emoji, including joined sequences and skin tones, are kept whole.
        val family = "👩‍👩‍👧"
        assertEquals("Bot $family", CloudAgent.displayName("Bot $family"))
        assertEquals("👍🏽 ok", CloudAgent.displayName("👍🏽 ok"))
        assertEquals("❤️ Claude", CloudAgent.displayName("❤️ Claude"))
        assertEquals("Café Ünï 日本", CloudAgent.displayName("Café Ünï 日本"))
        assertNull(CloudAgent.displayName("‮​ ⁦"))
        assertNull(CloudAgent.displayName(null))
        assertEquals(CloudAgent.MAX_NAME, CloudAgent.displayName("x".repeat(200))!!.length)
        val screen = projectFile("src/main/java/com/evansappstudio/doapp/ui/AssistantsScreen.kt").readText()
        assertTrue(screen.contains("CloudAgent.displayName(raw)"))
    }

    // L5: a request from an assistant that is not paired with this device is answered and never applied.
    @Test
    fun anUnpairedAgentIdIsRefusedEvenInTheRightShape() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val mine = cx.fx.dao.getById(cx.fx.commands.add(Origin(Actor.AGENT, ReminderSource.AGENT, AGENT_B), "B's", cx.later, null)!!)!!
        val stranger = "ag_2222222222222222"
        val requests = listOf(cx.create(stranger, "From a stranger"), cx.complete(AGENT_B, mine.uuid))
        cx.cloud.runPull()
        for (request in requests) {
            val failed = cx.resultOf(request) as RequestResult.Failed
            assertEquals(PhoneErrorCode.NOT_FOUND, failed.code)
            assertEquals(CloudInbox.NOT_CONNECTED_MESSAGE, failed.message)
        }
        assertFalse(cx.fx.dao.getById(mine.id)!!.isDone)
        assertEquals(1, cx.fx.dao.all().size)
        // It looked the list up again before refusing (the assistant might just have paired).
        assertTrue(cx.port.calls.contains("listAgents"))
    }

    // L5: a just-paired assistant missing from the cache is found by re-reading the list.
    @Test
    fun aJustPairedAssistantIsFound() = runBlocking {
        val cx = CloudFixture().signedIn()
        assertTrue(cx.store.agents().isEmpty())
        val request = cx.create(AGENT_A, "First request")
        cx.cloud.runPull()
        assertTrue(cx.resultOf(request) is RequestResult.Done)
        assertTrue(cx.store.agents().any { it.agentId == AGENT_A })
    }

    // L5: when the list can't be read, an unknown assistant's request waits for the next pull.
    @Test
    fun anUnreadableListDefersUnknownAssistants() = runBlocking {
        val cx = CloudFixture().signedIn()
        cx.port.failList = CloudCallCode.UNAVAILABLE
        cx.create(AGENT_A, "Waits")
        val run = cx.cloud.runPull()
        assertEquals(1, run.deferred)
        assertEquals(0, run.completed)
        assertTrue(cx.fx.dao.all().isEmpty())
        // Once the list can be read, it goes ahead.
        cx.port.failList = null
        assertEquals(1, cx.cloud.runPull().applied)
    }
}
