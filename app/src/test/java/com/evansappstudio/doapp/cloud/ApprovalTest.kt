package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.ApprovalDecision
import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 13: the approval card's Allow, As Normal and Decline, through ReminderCommands. */
class ApprovalTest {
    private suspend fun CloudFixture.held(level: String = "alarm"): Long {
        val request = create(AGENT_A, "Held one", alertLevel = level)
        cloud.runPull()
        val uuid = (resultOf(request) as RequestResult.Done).reminder.uuid
        return fx.dao.getByUuid(uuid)!!.id
    }

    @Test
    fun allowRingsAtTheRequestedLevel() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val id = cx.held("alarm")
        val before = cx.fx.dao.getById(id)!!
        assertTrue(cx.cloud.resolveApproval(id, ApprovalDecision.ALLOW))
        val row = cx.fx.dao.getById(id)!!
        assertEquals("PROMINENT", row.alertLevel)
        assertEquals("PROMINENT", row.requestedAlertLevel)
        assertEquals("APPROVED", row.approval)
        assertEquals(before.version + 1, row.version)
        val event = cx.fx.events.forUuid(row.uuid).last()
        assertEquals(EventType.LEVEL_CHANGED.name, event.type)
        assertEquals(Actor.USER.name, event.actor)
        assertTrue(event.detail!!.contains("approval=APPROVED"))
        // Settled once: a second tap changes nothing.
        assertFalse(cx.cloud.resolveApproval(id, ApprovalDecision.AS_NORMAL))
        assertEquals("PROMINENT", cx.fx.dao.getById(id)!!.alertLevel)
    }

    @Test
    fun asNormalKeepsNormalAndRemembersTheRequest() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val id = cx.held("loud")
        assertTrue(cx.cloud.resolveApproval(id, ApprovalDecision.AS_NORMAL))
        val row = cx.fx.dao.getById(id)!!
        assertEquals("STANDARD", row.alertLevel)
        assertEquals("CRITICAL", row.requestedAlertLevel)
        assertEquals("DECLINED", row.approval)
        assertNull(row.deletedAt)
    }

    @Test
    fun declineDeletesTheReminder() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val id = cx.held("alarm")
        assertTrue(cx.cloud.resolveApproval(id, ApprovalDecision.DECLINE))
        val row = cx.fx.dao.getById(id)!!
        assertEquals("STANDARD", row.alertLevel)
        assertEquals("DECLINED", row.approval)
        assertNotNull(row.deletedAt)
        // The assistant hears about it through the mirror: deleted.
        cx.cloud.runMirror()
        val sent = cx.port.synced.flatMap { it.reminders }.last { it.uuid == row.uuid }
        assertEquals("deleted", sent.status)
        assertEquals("DECLINED", sent.approval)
    }

    @Test
    fun onlyAPendingRequestCanBeSettledAndNeverByAnAgent() = runBlocking {
        val cx = CloudFixture().signedIn().connect(AGENT_A)
        val plain = cx.fx.commands.add(Origin(Actor.USER), "Mine", cx.later, null)!!
        assertFalse(cx.cloud.resolveApproval(plain, ApprovalDecision.ALLOW))
        assertFalse(cx.cloud.resolveApproval(9_999L, ApprovalDecision.ALLOW))
        val id = cx.held("alarm")
        val agent = Origin(Actor.AGENT, ReminderSource.AGENT, AGENT_A)
        assertFalse(cx.fx.commands.resolveApproval(agent, id, ApprovalDecision.ALLOW))
        assertEquals("PENDING", cx.fx.dao.getById(id)!!.approval)
        assertEquals("STANDARD", cx.fx.dao.getById(id)!!.alertLevel)
    }
}
