package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.Approval
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.data.RepeatUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AgentFunctionsTest {
    // AT-4 (an Alarm request is held at Normal, pending approval)
    @Test
    fun at4AlarmRequestIsHeldAtNormal() = runBlocking {
        val cx = CaptureFixture()
        val made = cx.agent.createReminder("gemini", AgentCreateRequest(text = "Take the bins out", dueAt = cx.later, alertLevel = "Alarm"))
        val row = cx.fx.dao.getById(made.id)!!
        assertEquals(AlertLevel.PROMINENT.name, row.requestedAlertLevel)
        assertEquals(AlertLevel.STANDARD.name, row.alertLevel)
        assertEquals(Approval.PENDING.name, row.approval)
        assertEquals("PROMINENT", made.requestedAlertLevel)
        assertEquals("STANDARD", made.alertLevel)
        assertEquals("PENDING", made.approval)
    }

    // AT-4 (Loud is held too; Normal needs no approval; a person's own Alarm is untouched)
    @Test
    fun at4OnlyAgentRequestsAboveNormalAreHeld() = runBlocking {
        val cx = CaptureFixture()
        val loud = cx.fx.dao.getById(cx.agent.createReminder("g", AgentCreateRequest("Loud one", cx.later, alertLevel = "loud")).id)!!
        assertEquals("CRITICAL", loud.requestedAlertLevel)
        assertEquals("STANDARD", loud.alertLevel)
        assertEquals("PENDING", loud.approval)
        val normal = cx.fx.dao.getById(cx.agent.createReminder("g", AgentCreateRequest("Quiet one", cx.later)).id)!!
        assertEquals("STANDARD", normal.requestedAlertLevel)
        assertEquals("STANDARD", normal.alertLevel)
        assertEquals("NONE", normal.approval)
        val mine = cx.fx.dao.getById(cx.fx.commands.add(Origin(Actor.USER), "Mine", cx.later, null, alertLevel = AlertLevel.PROMINENT)!!)!!
        assertEquals("PROMINENT", mine.alertLevel)
        assertNull(mine.requestedAlertLevel)
        assertEquals("NONE", mine.approval)
        // The hold is in the command layer, so any agent path gets it.
        val direct = cx.fx.dao.getById(
            cx.fx.commands.add(cx.agent.origin("other"), "Direct", cx.later, null, alertLevel = AlertLevel.PROMINENT)!!,
        )!!
        assertEquals("STANDARD", direct.alertLevel)
        assertEquals("PENDING", direct.approval)
    }

    @Test
    fun agentRequestsAreChecked() = runBlocking {
        val cx = CaptureFixture()
        for (bad in listOf(
            AgentCreateRequest(text = "  "),
            AgentCreateRequest(text = "x", alertLevel = "siren"),
            AgentCreateRequest(text = "x", dueAt = cx.fx.clockMillis - 3_600_000L),
            AgentCreateRequest(text = "x", repeat = "whenever"),
        )) {
            try {
                cx.agent.createReminder("g", bad)
                fail("expected InvalidArgument for $bad")
            } catch (expected: AgentError.InvalidArgument) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
        assertEquals(0, cx.fx.dao.activeCount())
        val repeating = cx.agent.createReminder("g", AgentCreateRequest(text = "Water plants", dueAt = 0L, repeat = "every 2 hours"))
        val row = cx.fx.dao.getById(repeating.id)!!
        assertEquals(RepeatUnit.HOUR, row.repeatRule()!!.unit)
        assertEquals(2, row.repeatRule()!!.interval)
        assertEquals(perth(2023, 11, 16, 9, 0), row.dueAt)
    }

    @Test
    fun listAndCompleteFollowTheirLimits() = runBlocking {
        val cx = CaptureFixture()
        val ids = (1..12).map { cx.agent.createReminder("g", AgentCreateRequest("R$it", cx.later + it * 60_000L)).id }
        assertEquals(AgentRequests.DEFAULT_LIMIT, cx.agent.listReminders(0).size)
        assertEquals(3, cx.agent.listReminders(3).size)
        assertEquals(12, cx.agent.listReminders(500).size)
        assertEquals(listOf("R1", "R2"), cx.agent.listReminders(2).map { it.title })
        assertFalse(cx.agent.listReminders(1).single().overdue)
        cx.agent.completeReminder("g", ids[0])
        assertEquals("R2", cx.agent.listReminders(1).single().title)
        for (missing in listOf(ids[0], 999L, 0L)) {
            try {
                cx.agent.completeReminder("g", missing)
                fail("expected NotFound for $missing")
            } catch (expected: AgentError.NotFound) {
                assertTrue(expected.message!!.contains("$missing"))
            }
        }
    }

    // AT-6 (with the flag off, every function refuses and nothing is read or written)
    @Test
    fun at6FlagOffRefusesEveryFunction() = runBlocking {
        val cx = CaptureFixture(enabled = false)
        val id = cx.capture.add(CaptureEntry.APP, "Mine", cx.later, null).let { (it as CaptureResult.Saved).id }
        val calls: List<suspend () -> Unit> = listOf(
            { cx.agent.createReminder("g", AgentCreateRequest("Agent", cx.later)) },
            { cx.agent.completeReminder("g", id) },
            { cx.agent.listReminders(5) },
        )
        for (call in calls) {
            try {
                call()
                fail("expected Disabled")
            } catch (expected: AgentError.Disabled) {
                assertTrue(expected.message!!.contains("switched off"))
            }
        }
        assertEquals(1, cx.fx.dao.activeCount())
        assertFalse(cx.fx.dao.getById(id)!!.isDone)
    }

    // AT-6 (release defaults the flag off, and no main or release manifest declares an AppFunctions service)
    @Test
    fun at6ReleaseHasAppFunctionsOff() {
        val build = projectFile("build.gradle.kts").readText()
        assertTrue(build.contains("val appFunctionsRelease = appFunctionsOverride ?: false"))
        assertTrue(build.contains("buildConfigField(\"boolean\", \"APP_FUNCTIONS_ENABLED\", \"\$appFunctionsRelease\")"))
        assertTrue(build.contains("Release manifest contains \$needle while appFunctionsEnabled is off"))
        for (path in listOf("src/main/AndroidManifest.xml", "src/release/AndroidManifest.xml")) {
            val file = projectFile(path)
            if (!file.isFile) continue
            val text = file.readText()
            assertFalse(path, text.contains("android.app.appfunctions"))
            assertFalse(path, text.contains("BIND_APP_FUNCTION_SERVICE"))
        }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.exists() } ?: File(path)
}
