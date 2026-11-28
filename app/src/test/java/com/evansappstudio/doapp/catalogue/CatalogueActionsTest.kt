package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.RestoreResult
import com.evansappstudio.doapp.alarm.schedulerFixture
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Spec 11 section D: adding and removing catalogue items, on the shared in-memory Scheduler fixture. */
class CatalogueActionsTest {
    private val perth = ZoneId.of("Australia/Perth")

    private fun setup() = schedulerFixture().let { fx ->
        fx to CatalogueActions(commands = { fx.commands }, dao = { fx.dao }, events = { fx.events })
    }

    private fun nswItem(id: String): LocalItem {
        val item = bundled().item(id)!!
        return CatalogueFilter.localise(item, Region("AU-NSW"))
    }

    // AT-6 (a reminder added from an item carries the template and the item's repeat)
    @Test
    fun at6AddWritesTheTemplate() = runBlocking {
        val (fx, actions) = setup()
        val safety = nswItem("home-safety-switch-test")
        val rego = nswItem("car-rego-renewal")
        val plans = FirstDue.plan(
            listOf(safety, rego),
            fx.clockMillis,
            perth,
            mapOf("car-rego-renewal" to java.time.LocalDate.parse("2024-02-01")),
        )
        val result = actions.add(plans, nagMinutes = 5)
        assertFalse(result.blocked)
        assertEquals(2, result.ids.size)
        val row = fx.dao.getById(result.ids[0])!!
        assertEquals("CATALOGUE", row.source)
        assertEquals("home-safety-switch-test", row.sourceId)
        assertEquals("home", row.category)
        assertEquals("home-safety-switch-test", row.templateId)
        assertEquals(1, row.templateVersion)
        assertEquals("Test the safety switches", row.title)
        assertEquals(5, row.nagMinutes)
        assertEquals("STANDARD", row.alertLevel)
        // NSW rule: every 6 months, from completion.
        assertEquals(RepeatRule.of(6, RepeatUnit.MONTH, RepeatFrom.COMPLETION), row.repeatRule())
        assertEquals(plans[0].dueAt, row.dueAt)
        val created = fx.events.forUuid(row.uuid).single()
        assertEquals(EventType.CREATED.name, created.type)
        assertEquals(Actor.USER.name, created.actor)
        assertEquals("home-safety-switch-test", created.sourceId)

        val regoRow = fx.dao.getById(result.ids[1])!!
        assertEquals("car", regoRow.category)
        assertEquals("CRITICAL", regoRow.alertLevel)
        assertEquals(RepeatRule.of(1, RepeatUnit.YEAR, RepeatFrom.DUE_DATE), regoRow.repeatRule())
        assertEquals(regoRow.dueAt, regoRow.patternDueAt)
        assertEquals(setOf("home-safety-switch-test", "car-rego-renewal"), actions.addedIds())
    }

    // AT-6 (the free cap stops a batch part way; the paywall path then keeps what fits)
    @Test
    fun at6FreeCapStopsTheBatchAtTheLimit() = runBlocking {
        val (fx, actions) = setup()
        fx.paid = false
        fx.commands.add(Origin(Actor.USER), "First reminder", fx.clockMillis + 60_000L, 5)
        val items = Picks.ordered(CatalogueFilter.forRegion(bundled(), Region("AU-WA")).filter { !it.item.needsDate }).take(6)
        val result = actions.add(FirstDue.plan(items, fx.clockMillis, perth), nagMinutes = 5)
        assertTrue(result.blocked)
        assertEquals(4, result.ids.size)
        assertEquals(5, fx.dao.activeCount())
    }

    // AT-7 (remove soft-deletes the template's reminder; it no longer counts as added)
    @Test
    fun at7RemoveSoftDeletesAndTheItemIsNoLongerAdded() = runBlocking {
        val (fx, actions) = setup()
        val smoke = nswItem("home-smoke-alarm-test")
        val id = actions.add(FirstDue.plan(listOf(smoke), fx.clockMillis, perth), 5).ids.single()
        assertTrue("home-smoke-alarm-test" in actions.addedIds())
        assertEquals(1, actions.remove("home-smoke-alarm-test"))
        val row = fx.dao.getById(id)!!
        assertNotNull(row.deletedAt)
        assertFalse("home-smoke-alarm-test" in actions.addedIds())
        assertEquals(EventType.DELETED.name, fx.events.forUuid(row.uuid).last().type)
        assertEquals(0, actions.remove("home-smoke-alarm-test"))
        // Soft delete: it can be restored, and then it counts as added again.
        assertEquals(RestoreResult.RESTORED, fx.commands.restore(Origin(Actor.USER), id))
        assertTrue("home-smoke-alarm-test" in actions.addedIds())
    }

    // AT-8 input (Done on a catalogue reminder is seen, oldest first, for the related card)
    @Test
    fun at8DoneCatalogueRemindersAreSeenOldestFirst() = runBlocking {
        val (fx, actions) = setup()
        val ids = actions.add(
            FirstDue.plan(listOf(nswItem("car-service"), nswItem("pets-vet-check")), fx.clockMillis, perth),
            5,
        ).ids
        assertTrue(actions.doneItemIds().isEmpty())
        fx.clockMillis += 1_000L
        fx.commands.markDone(Origin(Actor.USER), ids[1])
        fx.clockMillis += 1_000L
        fx.commands.markDone(Origin(Actor.NOTIFICATION), ids[0])
        assertEquals(listOf("pets-vet-check", "car-service"), actions.doneItemIds())
    }
}
