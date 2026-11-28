package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Spec 11 section C (pre-tick) and section D (first due). */
class PicksTest {
    private val perth = ZoneId.of("Australia/Perth")
    private val cap = 5

    private fun at(date: String, time: String = "12:00"): Long =
        LocalDateTime.parse("${date}T$time").atZone(perth).toInstant().toEpochMilli()

    private fun local(millis: Long): LocalDateTime = java.time.Instant.ofEpochMilli(millis).atZone(perth).toLocalDateTime()

    private val waItems: List<LocalItem> by lazy { CatalogueFilter.forRegion(bundled(), Region("AU-WA")) }

    // AT-4 (with 1 active reminder, at most 4 pre-ticked)
    @Test
    fun at4OneActiveGivesAtMostFourPreTicked() {
        val ticked = Picks.preTicked(waItems, activeCount = 1, unlocked = false, cap = cap)
        assertEquals(4, ticked.size)
        val expected = Picks.ordered(waItems.filter { !it.item.needsDate }).take(4).map { it.id }.toSet()
        assertEquals(expected, ticked)
        assertEquals(4, Picks.freeLeft(1, 0, unlocked = false, cap = cap))
        assertEquals(0, Picks.freeLeft(1, 4, unlocked = false, cap = cap))
        assertFalse(Picks.overCap(1, 4, unlocked = false, cap = cap))
        assertTrue(Picks.overCap(1, 5, unlocked = false, cap = cap))
        // Two free slots: two pet items ticked, though five could be.
        val pets = waItems.filter { it.item.category == "pets" }
        val petTicks = Picks.preTicked(pets, activeCount = 3, unlocked = false, cap = cap)
        assertEquals(2, petTicks.size)
    }

    // AT-4 (with 5 active, none; over the cap, none)
    @Test
    fun at4FiveActiveGivesNone() {
        assertTrue(Picks.preTicked(waItems, activeCount = 5, unlocked = false, cap = cap).isEmpty())
        assertTrue(Picks.preTicked(waItems, activeCount = 7, unlocked = false, cap = cap).isEmpty())
        assertEquals(0, Picks.freeLeft(7, 0, unlocked = false, cap = cap))
        assertTrue(Picks.overCap(5, 1, unlocked = false, cap = cap))
    }

    // AT-4 (needsDate is never pre-ticked, even when it is the highest priority)
    @Test
    fun at4NeedsDateIsNeverPreTicked() {
        val items = listOf(
            testItem("car-a", category = "car", priority = 99, needsDate = true),
            testItem("car-b", category = "car", priority = 10),
        )
        assertEquals(setOf("car-b"), Picks.preTicked(items, activeCount = 0, unlocked = false, cap = cap))
        assertEquals(setOf("car-b"), Picks.preTicked(items, activeCount = 0, unlocked = true, cap = cap))
        val bundledTicks = Picks.preTicked(waItems, activeCount = 0, unlocked = true, cap = cap)
        assertTrue(bundledTicks.none { id -> waItems.first { it.id == id }.item.needsDate })
        // car-rego-renewal is the second-highest priority item but needs its date.
        assertFalse(bundledTicks.contains("car-rego-renewal"))
    }

    // AT-4 (an Unlimited user gets the top 5 pre-ticked, the same cap)
    @Test
    fun at4UnlimitedGetsTheTopFive() {
        val ticked = Picks.preTicked(waItems, activeCount = 9, unlocked = true, cap = cap)
        assertEquals(5, ticked.size)
        assertEquals(Picks.ordered(waItems.filter { !it.item.needsDate }).take(5).map { it.id }.toSet(), ticked)
        assertNull(Picks.freeLeft(9, 5, unlocked = true, cap = cap))
        assertFalse(Picks.overCap(9, 40, unlocked = true, cap = cap))
    }

    // AT-4 (grouping: chosen areas in order, highest priority first, added items left out)
    @Test
    fun at4GroupedByAreaHighestPriorityFirst() {
        val groups = Picks.grouped(waItems, setOf("car", "home"), setOf("home-smoke-alarm-test"), ReminderCategory.ALL)
        assertEquals(listOf("home", "car"), groups.map { it.first })
        val home = groups.first().second
        assertFalse(home.any { it.id == "home-smoke-alarm-test" })
        assertEquals("home-safety-switch-test", home.first().id)
        assertEquals(home.map { it.item.priority }.sortedDescending(), home.map { it.item.priority })
        assertEquals(8, Picks.grouped(waItems, emptySet(), emptySet(), ReminderCategory.ALL).size)
    }

    // AT-4 (Tim, 28 Nov 2026: one smoke alarm test under Home and Renting, never listed or ticked twice)
    @Test
    fun at4AnItemInTwoAreasIsListedAndTickedOnce() {
        val both = Picks.grouped(waItems, setOf("home", "renter"), emptySet(), ReminderCategory.ALL)
        val listed = both.flatMap { g -> g.second.map { it.id } }
        assertEquals(listed.size, listed.toSet().size)
        assertEquals("home", both.first { g -> g.second.any { it.id == "home-smoke-alarm-test" } }.first)
        val renterOnly = Picks.grouped(waItems, setOf("renter"), emptySet(), ReminderCategory.ALL)
        assertEquals(listOf("renter"), renterOnly.map { it.first })
        assertEquals("home-smoke-alarm-test", renterOnly.single().second.first().id)
        for (areas in listOf(setOf("renter"), setOf("home", "renter"))) {
            val items = Picks.grouped(waItems, areas, emptySet(), ReminderCategory.ALL).flatMap { it.second }
            val ticked = Picks.preTicked(items, activeCount = 0, unlocked = true, cap = cap, areas = areas)
            assertTrue(areas.toString(), "home-smoke-alarm-test" in ticked)
            assertEquals(items.filter { it.id in ticked }.size, ticked.size)
        }
        // Seasonal and head-start cards read every area of an item.
        val renterCard = SuggestionPolicy.decide(
            SuggestionInput(
                LocalDate.parse("2026-11-28"), setOf("renter"), emptySet(), emptyList(), waItems,
                SuggestionPolicy.skipped(SuggestionState(), LocalDate.parse("2026-11-27")),
            ),
        ).card!!
        assertEquals(1, renterCard.items.count { it.id == "home-smoke-alarm-test" })
    }

    // AT-4 (Tim, 28 Nov 2026: a low-confidence item is never pre-ticked, but can be ticked)
    @Test
    fun at4LowConfidenceIsNeverPreTicked() {
        for (area in ReminderCategory.ALL) {
            val items = Picks.grouped(waItems, setOf(area), emptySet(), ReminderCategory.ALL).flatMap { it.second }
            val ticked = Picks.preTicked(items, activeCount = 0, unlocked = true, cap = cap, areas = setOf(area))
            assertTrue(area, ticked.none { id -> !items.first { it.id == id }.item.preTick })
        }
        // Kids: only kids-dentist is not low confidence.
        val kids = Picks.grouped(waItems, setOf("kids"), emptySet(), ReminderCategory.ALL).flatMap { it.second }
        assertEquals(setOf("kids-dentist"), Picks.preTicked(kids, 1, unlocked = false, cap = cap, areas = setOf("kids")))
        // Still listed, so it can be ticked by hand.
        assertTrue(kids.any { it.id == "kids-car-seat-check" })
    }

    // AT-5 (from-completion items spread one day apart from tomorrow, across a month and year rollover)
    @Test
    fun at5FromCompletionSpreadsAcrossAMonthRollover() {
        val items = listOf(
            testItem("home-a", minute = 9 * 60),
            testItem("home-b", minute = 19 * 60 + 30),
            testItem("home-c", minute = 9 * 60),
            testItem("home-d", minute = 10 * 60),
        )
        val plans = FirstDue.plan(items, at("2026-11-29", "21:00"), perth)
        assertEquals(
            listOf(
                LocalDateTime.parse("2026-11-30T09:00"),
                LocalDateTime.parse("2026-12-01T19:30"),
                LocalDateTime.parse("2026-12-02T09:00"),
                LocalDateTime.parse("2026-12-03T10:00"),
            ),
            plans.map { local(it.dueAt) },
        )
        val yearEnd = FirstDue.plan(items.take(3), at("2026-12-30", "08:00"), perth)
        assertEquals(
            listOf("2026-12-31", "2027-01-01", "2027-01-02").map { LocalDate.parse(it) },
            yearEnd.map { local(it.dueAt).toLocalDate() },
        )
    }

    // AT-5 (a suggested month: the 1st of the next such month, next year once it has passed)
    @Test
    fun at5SuggestedMonthAlreadyPassedGoesToNextYear() {
        val tax = testItem("money-tax", category = "money", from = RepeatFrom.DUE_DATE, every = 1, unit = RepeatUnit.YEAR, month = 7, minute = 19 * 60)
        val flu = testItem("health-flu", category = "health", from = RepeatFrom.DUE_DATE, every = 1, unit = RepeatUnit.YEAR, month = 12, minute = 10 * 60)
        val chore = testItem("home-a")
        // 27 Nov 2026: July has passed, December has not.
        val plans = FirstDue.plan(listOf(tax, chore, flu), at("2026-11-27"), perth)
        assertEquals(LocalDateTime.parse("2027-07-01T19:00"), local(plans[0].dueAt))
        // The month item does not use a spread day: the chore is still tomorrow.
        assertEquals(LocalDateTime.parse("2026-11-28T09:00"), local(plans[1].dueAt))
        assertEquals(LocalDateTime.parse("2026-12-01T10:00"), local(plans[2].dueAt))
        // Added during its own month (after QA, a call for Gareth): tomorrow at the suggested time.
        assertEquals(LocalDateTime.parse("2026-07-02T19:00"), local(FirstDue.plan(listOf(tax), at("2026-07-01", "08:00"), perth)[0].dueAt))
        assertEquals(LocalDateTime.parse("2026-07-03T19:00"), local(FirstDue.plan(listOf(tax), at("2026-07-02", "20:00"), perth)[0].dueAt))
        assertEquals(LocalDateTime.parse("2026-08-01T19:00"), local(FirstDue.plan(listOf(tax), at("2026-07-31", "20:00"), perth)[0].dueAt))
        // The month after it: next year.
        assertEquals(LocalDateTime.parse("2027-07-01T19:00"), local(FirstDue.plan(listOf(tax), at("2026-08-01", "08:00"), perth)[0].dueAt))
        // The repeat comes from the item.
        assertEquals(RepeatRule.of(1, RepeatUnit.YEAR, RepeatFrom.DUE_DATE), plans[0].rule)
        assertEquals(RepeatRule.of(1, RepeatUnit.MONTH, RepeatFrom.COMPLETION), plans[1].rule)
    }

    // AT-5 (needsDate uses the picked date at the suggested time; no date, no add)
    @Test
    fun at5NeedsDateUsesThePickedDate() {
        val rego = testItem("car-rego", category = "car", from = RepeatFrom.DUE_DATE, every = 1, unit = RepeatUnit.YEAR, minute = 19 * 60, needsDate = true)
        val chore = testItem("car-a", category = "car")
        val plans = FirstDue.plan(listOf(rego, chore), at("2026-11-27"), perth, mapOf("car-rego" to LocalDate.parse("2027-03-14")))
        assertEquals(LocalDateTime.parse("2027-03-14T19:00"), local(plans[0].dueAt))
        assertEquals(LocalDateTime.parse("2026-11-28T09:00"), local(plans[1].dueAt))
        assertEquals(listOf("car-a"), FirstDue.plan(listOf(rego, chore), at("2026-11-27"), perth).map { it.item.id })
        // A monthly from-due-date repeat keeps the first due's day of the month.
        val monthly = testItem("money-m", from = RepeatFrom.DUE_DATE, unit = RepeatUnit.MONTH, needsDate = true)
        val plan = FirstDue.plan(listOf(monthly), at("2026-11-27"), perth, mapOf("money-m" to LocalDate.parse("2026-12-09")))
        assertEquals(MonthOn.DayOfMonth(9), plan.single().rule!!.monthOn)
    }
}
