package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.billing.EntitlementPolicy
import com.evansappstudio.doapp.billing.EntitlementRecord
import com.evansappstudio.doapp.billing.SubscriptionState
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 10 AT-5: a lapsed subscription only blocks adding past the cap. */
class SubscriptionCapTest {
    private val user = Origin(Actor.USER)
    private val minute = 60_000L

    private fun record(active: Boolean, everSubscribed: Boolean) = EntitlementRecord(
        active = active,
        pending = false,
        lastQueryAt = 1L,
        grantSource = EntitlementPolicy.SOURCE_PLAY,
        lastQueryOk = true,
        everSubscribed = everSubscribed,
    )

    // AT-5
    @Test
    fun at5ExpiredKeepsEveryReminderWorkingAndOnlyBlocksAddingPastFive() = runBlocking {
        val fx = schedulerFixture()
        var entitlement = record(active = true, everSubscribed = true)
        fx.paid = SubscriptionState.of(entitlement).liftsCap
        val day = RepeatRule.of(1, RepeatUnit.DAY, RepeatFrom.DUE_DATE)!!
        val ids = (1..7).map { n ->
            fx.commands.add(user, "T_s10_$n", fx.clockMillis + n * minute, 5, if (n == 7) day else null)!!
        }
        assertEquals(7, fx.dao.activeCount())

        // The subscription lapses: Play no longer returns it.
        entitlement = EntitlementPolicy.apply(entitlement, true, emptyList(), 2L, EntitlementPolicy.SOURCE_PLAY).record
        assertEquals(SubscriptionState.EXPIRED, SubscriptionState.of(entitlement))
        fx.paid = SubscriptionState.of(entitlement).liftsCap
        assertFalse(fx.paid)

        // Adding an eighth is blocked and creates nothing.
        assertNull(fx.commands.add(user, "T_s10_8", fx.clockMillis + 8 * minute, 5))
        assertEquals(7, fx.dao.activeCount())

        // Every reminder, including the two above the cap, keeps its alarm, through a reschedule too.
        for (id in ids) assertNotNull(fx.alarms.at(id))
        fx.scheduler.rescheduleAll()
        for (id in ids) assertNotNull(fx.alarms.at(id))

        // The reminders above the cap still fire and nag.
        val sixth = ids[5]
        val due = fx.dao.getById(sixth)!!.dueAt
        fx.clockMillis = due
        fx.scheduler.onFire(sixth, due, due)
        assertTrue(fx.alerts.shown.contains(sixth))
        assertEquals(due + 5 * minute, fx.mirror.get(sixth)!!.nextFireAt)
        assertEquals(due + 5 * minute, fx.alarms.at(sixth))

        // Edit, snooze, Done and delete all still work.
        val seventh = ids[6]
        val row = fx.dao.getById(seventh)!!
        assertEquals(
            EditResult.SAVED,
            fx.commands.update(user, seventh, "T_s10_7 renamed", row.dueAt, 10, day),
        )
        assertEquals("T_s10_7 renamed", fx.dao.getById(seventh)!!.title)
        fx.commands.snooze(user, sixth, 0, SnoozeDue.DEFAULTS[0])
        assertEquals(fx.clockMillis + 60 * minute, fx.alarms.at(sixth))

        fx.commands.markDone(user, ids[0])
        assertTrue(fx.dao.getById(ids[0])!!.isDone)
        assertEquals(6, fx.dao.activeCount())
        assertNull(fx.commands.add(user, "T_s10_8", fx.clockMillis + 8 * minute, 5))

        fx.commands.delete(user, ids[1])
        assertEquals(5, fx.dao.activeCount())
        assertNull(fx.commands.add(user, "T_s10_8", fx.clockMillis + 8 * minute, 5))

        // A repeating reminder's Done advances it and keeps it active, so it still counts.
        fx.commands.markDone(user, seventh)
        assertFalse(fx.dao.getById(seventh)!!.isDone)
        assertNotNull(fx.alarms.at(seventh))

        fx.commands.markDone(user, ids[2])
        assertEquals(4, fx.dao.activeCount())
        assertNotNull(fx.commands.add(user, "T_s10_8", fx.clockMillis + 8 * minute, 5))
        assertNull(fx.commands.add(user, "T_s10_9", fx.clockMillis + 9 * minute, 5))

        // Renewing lifts the cap again.
        fx.paid = SubscriptionState.of(record(active = true, everSubscribed = true)).liftsCap
        assertNotNull(fx.commands.add(user, "T_s10_9", fx.clockMillis + 9 * minute, 5))
    }

    // AT-5
    @Test
    fun at5FreeAndExpiredHaveTheSameCapAndPendingDoesNotLiftIt() = runBlocking {
        for (state in listOf(
            record(active = false, everSubscribed = false),
            record(active = false, everSubscribed = true),
            record(active = false, everSubscribed = true).copy(pending = true),
        )) {
            val fx = schedulerFixture()
            fx.paid = SubscriptionState.of(state).liftsCap
            repeat(5) { n -> assertNotNull(fx.commands.add(user, "R$n", fx.clockMillis + (n + 1) * minute, null)) }
            assertNull(fx.commands.add(user, "Six", fx.clockMillis + 6 * minute, null))
            assertEquals(5, fx.dao.activeCount())
        }
    }
}
