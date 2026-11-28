package com.evansappstudio.doapp.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Spec 11 section F: when Stuff You Should Do is offered again. */
class SuggestionPolicyTest {
    private val wa: List<LocalItem> by lazy { CatalogueFilter.forRegion(bundled(), Region("AU-WA")) }
    private val skipDay = LocalDate.parse("2026-11-27")

    private fun input(
        today: LocalDate,
        state: SuggestionState = SuggestionState(),
        areas: Set<String> = setOf("home", "car"),
        added: Set<String> = emptySet(),
        done: List<String> = emptyList(),
    ) = SuggestionInput(today, areas, added, done, wa, state)

    private fun decide(input: SuggestionInput) = SuggestionPolicy.decide(input)

    // AT-8 rule 1 (skipped in first run: the head-start card the next day, not the same day)
    @Test
    fun at8HeadStartTheDayAfterSkipping() {
        val state = SuggestionPolicy.skipped(SuggestionState(), skipDay)
        assertNull(decide(input(skipDay, state)).card)
        val next = decide(input(skipDay.plusDays(1), state)).card
        assertNotNull(next)
        assertEquals(CardKind.HEAD_START, next!!.kind)
        assertTrue(next.items.all { it.item.category in setOf("home", "car") })
        assertEquals(Picks.ordered(next.items), next.items)
        // Gone once followed, or once anything is added.
        assertNull(decide(input(skipDay.plusDays(2), SuggestionPolicy.followed(state, CardKind.HEAD_START))).card?.takeIf { it.kind == CardKind.HEAD_START })
        assertNull(decide(input(skipDay.plusDays(2), state, added = setOf("car-service"))).card?.takeIf { it.kind == CardKind.HEAD_START })
        // Never skipped: no head start.
        assertNull(decide(input(LocalDate.parse("2026-11-28"))).card)
    }

    // AT-8 rule 2 (first "Not now" hides that kind for 14 days)
    @Test
    fun at8FirstNotNowHidesForFourteenDays() {
        val skipped = SuggestionPolicy.skipped(SuggestionState(), skipDay)
        val day1 = skipDay.plusDays(1)
        val dismissed = SuggestionPolicy.dismiss(skipped, CardKind.HEAD_START, day1)
        assertEquals(day1.plusDays(14), SuggestionPolicy.hiddenUntil(dismissed, CardKind.HEAD_START))
        assertNull(decide(input(day1, dismissed)).card)
        assertNull(decide(input(day1.plusDays(13), dismissed)).card)
        assertEquals(CardKind.HEAD_START, decide(input(day1.plusDays(14), dismissed)).card!!.kind)
    }

    // AT-8 rule 2 (a second "Not now" hides it for 60 days)
    @Test
    fun at8SecondNotNowHidesForSixtyDays() {
        val skipped = SuggestionPolicy.skipped(SuggestionState(), skipDay)
        val first = SuggestionPolicy.dismiss(skipped, CardKind.HEAD_START, skipDay.plusDays(1))
        val back = skipDay.plusDays(15)
        val second = SuggestionPolicy.dismiss(first, CardKind.HEAD_START, back)
        assertEquals(back.plusDays(60), SuggestionPolicy.hiddenUntil(second, CardKind.HEAD_START))
        assertNull(decide(input(back.plusDays(59), second)).card)
        assertEquals(CardKind.HEAD_START, decide(input(back.plusDays(60), second)).card!!.kind)
        // A third "Not now" also waits 60 days.
        val third = SuggestionPolicy.dismiss(second, CardKind.HEAD_START, back.plusDays(60))
        assertEquals(back.plusDays(120), SuggestionPolicy.hiddenUntil(third, CardKind.HEAD_START))
    }

    // AT-8 rule 2 ("Not now" hides only that kind)
    @Test
    fun at8NotNowHidesOnlyThatKind() {
        val june = LocalDate.parse("2026-06-10")
        val skipped = SuggestionPolicy.skipped(SuggestionState(), june.minusDays(1))
        assertEquals(CardKind.HEAD_START, decide(input(june, skipped, areas = setOf("money"))).card!!.kind)
        val state = SuggestionPolicy.dismiss(skipped, CardKind.HEAD_START, june)
        val card = decide(input(june, state, areas = setOf("money"))).card
        assertEquals(CardKind.SEASONAL, card!!.kind)
    }

    // AT-8 rule 2 ("Stop suggesting" turns every card off)
    @Test
    fun at8StopSuggestingTurnsAllCardsOff() {
        var state = SuggestionPolicy.skipped(SuggestionState(), skipDay)
        state = SuggestionPolicy.stop(state)
        val june = LocalDate.parse("2027-06-10")
        val decision = decide(input(june, state, areas = setOf("money"), done = listOf("car-service")))
        assertNull(decision.card)
        assertEquals("stopped", decision.reason)
        // It survives the settings round trip.
        assertTrue(SuggestionState.decode(state.encode()).stopped)
    }

    // AT-8 rule 3 (seasonal: suggested month this month or next, in the user's areas, not added)
    @Test
    fun at8SeasonalThisMonthOrNext() {
        val june = LocalDate.parse("2026-06-10")
        val card = decide(input(june, areas = setOf("money"))).card!!
        assertEquals(CardKind.SEASONAL, card.kind)
        assertEquals(listOf("money-tax-return"), card.items.map { it.id })
        // March: April's flu vaccine, only for health.
        val march = LocalDate.parse("2026-03-03")
        assertEquals(listOf("health-flu-vaccine"), decide(input(march, areas = setOf("health"))).card!!.items.map { it.id })
        assertNull(decide(input(march, areas = setOf("car"))).card)
        // Added: no card.
        assertNull(decide(input(june, areas = setOf("money"), added = setOf("money-tax-return"))).card)
        // December: January's school forms.
        assertEquals(listOf("kids-school-forms"), decide(input(LocalDate.parse("2026-12-20"), areas = setOf("kids"))).card!!.items.map { it.id })
    }

    // AT-8 rule 3 (at most once a calendar month: it stays that day, then not until next month)
    @Test
    fun at8SeasonalAtMostOnceAMonth() {
        val june = LocalDate.parse("2026-06-10")
        val card = decide(input(june, areas = setOf("money"))).card!!
        val shown = SuggestionPolicy.shown(SuggestionState(), card, june)
        assertEquals(CardKind.SEASONAL, decide(input(june, shown, areas = setOf("money"))).card!!.kind)
        assertNull(decide(input(june.plusDays(1), shown, areas = setOf("money"))).card)
        assertNull(decide(input(LocalDate.parse("2026-06-30"), shown, areas = setOf("money"))).card)
        assertEquals(CardKind.SEASONAL, decide(input(LocalDate.parse("2026-07-01"), shown, areas = setOf("money"))).card!!.kind)
    }

    // AT-8 rule 4 (after a catalogue item is first Done: its first related item not added, once per item)
    @Test
    fun at8RelatedAfterFirstDoneOncePerItem() {
        val day = LocalDate.parse("2026-11-28")
        val added = setOf("car-service")
        val card = decide(input(day, added = added, done = listOf("car-service"))).card!!
        assertEquals(CardKind.RELATED, card.kind)
        assertEquals("car-service", card.forItemId)
        assertEquals(listOf("car-tyre-rotation"), card.items.map { it.id })
        // First related already added: the next one.
        val next = decide(input(day, added = added + "car-tyre-rotation", done = listOf("car-service"))).card!!
        assertEquals(listOf("car-rego-renewal"), next.items.map { it.id })
        // Shown: still there that day, never again after.
        val shown = SuggestionPolicy.shown(SuggestionState(), card, day)
        assertEquals(CardKind.RELATED, decide(input(day, shown, added = added, done = listOf("car-service"))).card!!.kind)
        assertNull(decide(input(day.plusDays(1), shown, added = added, done = listOf("car-service"))).card)
        // Another item done later gets its own card.
        val other = decide(input(day.plusDays(1), shown, added = added + "pets-vet-check", done = listOf("car-service", "pets-vet-check"))).card!!
        assertEquals("pets-vet-check", other.forItemId)
        assertEquals(listOf("pets-vaccinations"), other.items.map { it.id })
    }

    // AT-8 rule 6 (never more than one card, head start first)
    @Test
    fun at8OnlyOneCardAtATime() {
        val june = LocalDate.parse("2026-06-10")
        val state = SuggestionPolicy.skipped(SuggestionState(), june.minusDays(1))
        val decision = decide(input(june, state, areas = setOf("money", "car")))
        assertEquals(CardKind.HEAD_START, decision.card!!.kind)
        assertTrue(decision.reason.isNotEmpty())
    }

    // AT-8 (the state survives encode and decode; garbage gives the default)
    @Test
    fun at8StateRoundTrips() {
        val day = LocalDate.parse("2026-11-28")
        var state = SuggestionPolicy.skipped(SuggestionState(), day)
        state = SuggestionPolicy.dismiss(state, CardKind.SEASONAL, day)
        state = SuggestionPolicy.shown(state, SuggestionCard(CardKind.RELATED, emptyList(), "car-service"), day)
        assertEquals(state, SuggestionState.decode(state.encode()))
        assertEquals(SuggestionState.DEFAULT, SuggestionState.decode("{ nope"))
        assertEquals(SuggestionState.DEFAULT, SuggestionState.decode(null))
    }
}
