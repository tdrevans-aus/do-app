package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.alarm.schedulerFixture
import com.evansappstudio.doapp.backup.BackupCodec
import com.evansappstudio.doapp.backup.BackupDecode
import com.evansappstudio.doapp.backup.BackupDocument
import com.evansappstudio.doapp.backup.BackupNames
import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.PortableSettings
import com.evansappstudio.doapp.backup.parseJson
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Spec 11 QA (independent of the build agent's tests). Tests marked
 * "QA: fails until ..." expose a bug and are expected to fail on 43e2d9b.
 */
class Spec11QaTest {
    private val perth = ZoneId.of("Australia/Perth")
    private val cap = 5

    private val catalogue: Catalogue by lazy { bundled() }
    private val wa: List<LocalItem> by lazy { CatalogueFilter.forRegion(catalogue, Region("AU-WA")) }

    private fun at(zone: ZoneId, dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    private fun local(zone: ZoneId, millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    private fun starter(): List<JsonValue.Obj> {
        val relative = "docs/catalogue/sysd-starter.json"
        val file = listOf(File("../$relative"), File(relative)).first { it.isFile }
        return (parseJson(file.readText()) as JsonValue.Arr).items.map { it as JsonValue.Obj }
    }

    private fun JsonValue.Obj.s(key: String): String? = (fields[key] as? JsonValue.Str)?.value
    private fun JsonValue.Obj.b(key: String): Boolean = (fields[key] as? JsonValue.Bool)?.value ?: false

    // ---- Check 3: the asset -------------------------------------------------------

    @Test
    fun assetParsesCleanlyWithEveryStarterItem() {
        val parse = CatalogueParser.parse(bundledText())
        assertEquals(emptyList<String>(), parse.skipped)
        assertEquals(1, parse.catalogue.schema)
        assertEquals(1, parse.catalogue.catalogueVersion)
        val ids = parse.catalogue.items.map { it.id }
        assertEquals(58, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(starter().map { it.s("id") }.toSet(), ids.toSet())
        parse.catalogue.items.forEach { item ->
            assertTrue(item.id, item.id.startsWith(item.category + "-"))
            assertTrue(item.id, item.related.isNotEmpty())
            item.related.forEach { assertNotNull("${item.id} -> $it", parse.catalogue.item(it)) }
            assertFalse(item.id, item.id in item.related)
            assertTrue(item.id, item.keywords.isNotEmpty())
        }
    }

    @Test
    fun assetHasNoDashesNoLinksAndMatchesTheStarterText() {
        val raw = bundledText()
        assertFalse("em dash", raw.contains('—'))
        assertFalse("en dash", raw.contains('–'))
        val root = parseJson(raw) as JsonValue.Obj
        (root.fields["items"] as JsonValue.Arr).items.forEach { value ->
            val obj = value as JsonValue.Obj
            assertTrue("${obj.s("id")} link", obj.fields["link"] == null || obj.fields["link"] == JsonValue.Null)
            assertNull("${obj.s("id")} confidence is editorial", obj.fields["confidence"])
        }
        val byId = catalogue.items.associateBy { it.id }
        starter().forEach { s ->
            val item = byId.getValue(s.s("id")!!)
            assertEquals(item.id, s.s("title"), item.title)
            assertEquals(item.id, s.s("why"), item.why)
            assertEquals(item.id, s.s("category"), item.category)
            assertEquals(item.id, s.s("repeatFrom"), item.repeatFrom.name)
            assertEquals(item.id, s.b("health"), item.health)
            assertEquals(item.id, s.b("stateVaries"), item.stateVaries)
            val expectedAlert = when (s.s("alertLevel")) {
                "STANDARD" -> CatalogueAlert.NORMAL
                "CRITICAL" -> CatalogueAlert.LOUD
                "PROMINENT" -> CatalogueAlert.ALARM
                else -> error("unknown starter alert for ${item.id}")
            }
            assertEquals(item.id, expectedAlert, item.alert)
            assertEquals(item.id, AlertLevel.valueOf(s.s("alertLevel")!!), AlertLevel.fromName(item.alert.stored))
        }
    }

    @Test
    fun healthNeedsDateStateRulesAndLowConfidenceAreSensible() {
        val health = catalogue.items.filter { it.health }.map { it.id }.toSet()
        val expectedHealth = catalogue.items.filter { it.category == "health" }.map { it.id }.toSet() +
            setOf("kids-health-check", "kids-dentist", "kids-immunisation-check")
        assertEquals(expectedHealth, health)
        assertEquals(
            setOf(
                "renter-lease-end-check", "renter-contents-insurance", "car-rego-renewal", "car-insurance-renewal",
                "pets-council-registration", "money-insurance-renewals", "money-health-insurance-review",
            ),
            catalogue.items.filter { it.needsDate }.map { it.id }.toSet(),
        )
        // needsDate items are yearly fixed-date renewals with no national month.
        catalogue.items.filter { it.needsDate }.forEach {
            assertEquals(it.id, RepeatFrom.DUE_DATE, it.repeatFrom)
            assertNull(it.id, it.suggestedMonth)
        }
        // A suggested month only on from-due-date items (otherwise it is ignored by FirstDue).
        catalogue.items.filter { it.suggestedMonth != null }.forEach { assertEquals(it.id, RepeatFrom.DUE_DATE, it.repeatFrom) }
        // State rules and hiddenIn name real states, only on items flagged stateVaries.
        catalogue.items.forEach { item ->
            (item.stateRules.keys + item.hiddenIn).forEach { state ->
                assertTrue("${item.id} $state", state in RegionResolver.STATES)
                assertTrue("${item.id} has a state rule but is not stateVaries", item.stateVaries)
            }
        }
        // The 19 low-confidence starter items rank below every other item.
        val low = starter().filter { it.s("confidence") == "low" }.map { it.s("id")!! }.toSet()
        assertEquals(19, low.size)
        val maxLow = catalogue.items.filter { it.id in low }.maxOf { it.priority }
        val minOther = catalogue.items.filter { it.id !in low }.minOf { it.priority }
        assertTrue("low $maxLow vs other $minOther", maxLow < minOther)
    }

    @Test
    fun aDecimalNumberInOneItemSkipsOnlyThatItem() {
        // QA: fails until the parser survives a non-integer number (the whole catalogue goes empty instead of one item being skipped).
        val good = """{"id":"home-a","version":1,"category":"home","title":"A","why":"","interval":{"every":1,"unit":"MONTH"},"repeatFrom":"COMPLETION","suggestedTime":"09:00","regions":["*"]}"""
        val bad = """{"id":"home-b","version":1,"category":"home","title":"B","why":"","interval":{"every":1,"unit":"MONTH"},"repeatFrom":"COMPLETION","suggestedTime":"09:00","regions":["*"],"priority":50.5}"""
        val parse = CatalogueParser.parse("""{"schema":1,"catalogueVersion":1,"items":[$good,$bad]}""")
        assertEquals(listOf("home-a"), parse.catalogue.items.map { it.id })
        assertEquals(1, parse.skipped.size)
    }

    // ---- Check 4: region -----------------------------------------------------------

    @Test
    fun everyAustraliaZoneInTzdataResolvesInsideAustralia() {
        val zones = ZoneId.getAvailableZoneIds().filter { it.startsWith("Australia/") }.sorted()
        assertTrue(zones.toString(), zones.size >= 20)
        val expected = mapOf(
            "Australia/Perth" to "AU-WA", "Australia/West" to "AU-WA", "Australia/Eucla" to "AU-WA",
            "Australia/Brisbane" to "AU-QLD", "Australia/Queensland" to "AU-QLD", "Australia/Lindeman" to "AU-QLD",
            "Australia/Adelaide" to "AU-SA", "Australia/South" to "AU-SA",
            "Australia/Darwin" to "AU-NT", "Australia/North" to "AU-NT",
            "Australia/Hobart" to "AU-TAS", "Australia/Tasmania" to "AU-TAS", "Australia/Currie" to "AU-TAS",
            "Australia/Melbourne" to "AU-VIC", "Australia/Victoria" to "AU-VIC",
            "Australia/Canberra" to "AU-ACT", "Australia/ACT" to "AU-ACT",
            "Australia/Broken_Hill" to "AU-NSW", "Australia/Yancowinna" to "AU-NSW",
            "Australia/Sydney" to "AU", "Australia/NSW" to "AU", "Australia/Lord_Howe" to "AU", "Australia/LHI" to "AU",
        )
        zones.forEach { zone ->
            val guess = RegionResolver.resolve(zone)
            assertNotNull("$zone resolved outside Australia", guess.region.code)
            assertEquals(zone, expected[zone], guess.region.code)
            assertEquals(zone, guess.region.code == "AU", guess.needsChoice)
        }
    }

    @Test
    fun nonAustralianZonesAndLookalikesAreOutside() {
        listOf(
            "Asia/Singapore", "Asia/Shanghai", "Etc/GMT-8", "GMT+08:00", "Pacific/Auckland", "Pacific/Norfolk",
            "Antarctica/Macquarie", "Antarctica/Casey", "Indian/Christmas", "Indian/Cocos", "America/New_York",
            "australia/perth", " Australia/Perth ".trim() + "x", "Z",
        ).forEach { zone ->
            assertEquals(zone, Region.OUTSIDE, RegionResolver.resolve(zone).region)
        }
        // Whitespace around a real id is tolerated.
        assertEquals("AU-WA", RegionResolver.resolve(" Australia/Perth ").region.code)
        // ZoneId.of normalises nothing for Australia; ids reach the resolver as set on the phone.
        assertEquals("Australia/Canberra", ZoneId.of("Australia/Canberra").id)
    }

    @Test
    fun regionSettingRules() {
        // USER "AU" (NSW or ACT not yet chosen) still needs the tap.
        assertTrue(RegionSetting.effective("AU", "USER", "Australia/Perth").needsChoice)
        // USER outside sticks on a Perth phone.
        assertEquals(Region.OUTSIDE, RegionSetting.effective("none", "USER", "Australia/Perth").region)
        // Garbage stored values fall back to the zone.
        assertEquals("AU-WA", RegionSetting.effective("AU-XX", "USER", "Australia/Perth").region.code)
        assertEquals("AU-WA", RegionSetting.effective(null, "SOMETIMES", "Australia/Perth").region.code)
        // Sydney with no state: AU and * items show, AU-NSW state rule does not apply.
        val au = CatalogueFilter.forRegion(catalogue, Region("AU"))
        assertEquals(58, au.size)
        assertEquals(3, au.first { it.id == "home-safety-switch-test" }.interval.every)
        val nsw = CatalogueFilter.forRegion(catalogue, Region("AU-NSW"))
        assertEquals(6, nsw.first { it.id == "home-safety-switch-test" }.interval.every)
        assertEquals(57, wa.size)
        assertFalse(wa.any { it.id == "money-energy-plan-compare" })
        assertTrue(CatalogueFilter.forRegion(catalogue, Region.OUTSIDE).all { "*" in it.item.regions })
    }

    // ---- Check 5: pre-tick, free cap and first due -----------------------------------

    @Test
    fun preTickNeverPassesTheFreeSlotsForAnyAreaChoice() {
        val areaSets = ReminderCategory.ALL.map { setOf(it) } + listOf(emptySet(), setOf("home", "renter"), setOf("car", "kids", "pets"))
        for (areas in areaSets) {
            val items = Picks.grouped(wa, areas, emptySet(), ReminderCategory.ALL).flatMap { it.second }
            for (active in 0..7) {
                val ticked = Picks.preTicked(items, active, unlocked = false, cap = cap)
                assertTrue("$areas active=$active ${ticked.size}", active + ticked.size <= maxOf(cap, active))
                assertTrue(ticked.all { id -> items.any { it.id == id } })
                assertTrue(ticked.none { id -> items.first { it.id == id }.item.needsDate })
                // The counter agrees with the pre-tick.
                val left = Picks.freeLeft(active, ticked.size, unlocked = false, cap = cap)!!
                assertTrue(left >= 0)
                if (active <= cap) assertFalse(Picks.overCap(active, ticked.size, unlocked = false, cap = cap))
            }
            assertTrue(Picks.preTicked(items, 30, unlocked = true, cap = cap).size <= Picks.PRE_TICK_CAP)
        }
    }

    @Test
    fun preTickedItemsAreAllVisibleBeforeShowMore() {
        // QA: fails until pre-ticked items can no longer sit hidden behind "Show more" (needsDate items take display slots).
        val hidden = ArrayList<String>()
        val areaSets = ReminderCategory.ALL.map { setOf(it) } + listOf(emptySet())
        for (areas in areaSets) {
            for ((active, unlocked) in listOf(1 to false, 0 to true)) {
                val groups = Picks.grouped(wa, areas, emptySet(), ReminderCategory.ALL)
                val ticked = Picks.preTicked(groups.flatMap { it.second }, active, unlocked, cap)
                val visible = groups.flatMap { it.second.take(Picks.SHOWN_PER_AREA) }.map { it.id }.toSet()
                (ticked - visible).forEach { hidden += "$areas active=$active unlocked=$unlocked: $it" }
            }
        }
        assertEquals("pre-ticked but hidden: $hidden", emptyList<String>(), hidden)
    }

    @Test
    fun keepFiveForFreeAddsOnlyWhatFitsAndLeavesExistingRemindersAlone() = runBlocking {
        val fx = schedulerFixture()
        fx.paid = false
        val actions = CatalogueActions(commands = { fx.commands }, dao = { fx.dao }, events = { fx.events })
        repeat(3) { fx.commands.add(Origin(Actor.USER), "Mine $it", fx.clockMillis + 60_000L * (it + 1), 5) }
        val before = fx.dao.all().sortedBy { it.id }
        val items = Picks.ordered(wa.filter { !it.item.needsDate }).take(4)
        val result = actions.add(FirstDue.plan(items, fx.clockMillis, perth), nagMinutes = 5)
        assertTrue(result.blocked)
        assertEquals(2, result.ids.size)
        assertEquals(5, fx.dao.activeCount())
        assertEquals(before, fx.dao.all().filter { it.templateId == null }.sortedBy { it.id })
        // At the cap, nothing more goes in.
        val again = actions.add(FirstDue.plan(items.drop(2), fx.clockMillis, perth), nagMinutes = 5)
        assertTrue(again.blocked)
        assertTrue(again.ids.isEmpty())
        // Removing touches only catalogue rows.
        actions.remove(items[0].id)
        assertEquals(before, fx.dao.all().filter { it.templateId == null }.sortedBy { it.id })
    }

    @Test
    fun spreadKeepsLocalTimeAcrossDstInEveryDstZone() {
        // Start 2026: first Sunday in October 4 Oct; end 2026: first Sunday in April 5 Apr.
        val zones = listOf("Australia/Sydney", "Australia/Melbourne", "Australia/Adelaide", "Australia/Hobart", "Australia/Lord_Howe", "Australia/Broken_Hill")
        val items = (0 until 4).map { testItem("home-$it", minute = 9 * 60) } +
            testItem("home-early", minute = 2 * 60 + 30)
        for (name in zones) {
            val zone = ZoneId.of(name)
            for (start in listOf("2026-10-02T22:00", "2026-04-03T22:00")) {
                val plans = FirstDue.plan(items, at(zone, start), zone)
                val day0 = LocalDate.parse(start.substring(0, 10)).plusDays(1)
                plans.take(4).forEachIndexed { i, plan ->
                    assertEquals("$name $start $i", day0.plusDays(i.toLong()).atTime(9, 0), local(zone, plan.dueAt))
                }
                // The fifth is on day0 + 4 at 02:30 (no gap that day), always after now.
                assertTrue(plans.all { it.dueAt > at(zone, start) })
            }
        }
        // 02:30 on the DST start day in Sydney does not exist: it lands at 03:30, still on that day.
        val sydney = ZoneId.of("Australia/Sydney")
        val gap = FirstDue.plan(listOf(testItem("home-gap", minute = 2 * 60 + 30)), at(sydney, "2026-10-03T12:00"), sydney).single()
        assertEquals(LocalDateTime.parse("2026-10-04T03:30"), local(sydney, gap.dueAt))
    }

    @Test
    fun spreadAcrossLeapDayAndYearEnd() {
        val items = (0 until 3).map { testItem("home-$it") }
        val leap = FirstDue.plan(items, at(perth, "2028-02-27T23:59"), perth)
        assertEquals(listOf("2028-02-28", "2028-02-29", "2028-03-01"), leap.map { local(perth, it.dueAt).toLocalDate().toString() })
        val plain = FirstDue.plan(items, at(perth, "2027-02-27T08:00"), perth)
        assertEquals(listOf("2027-02-28", "2027-03-01", "2027-03-02"), plain.map { local(perth, it.dueAt).toLocalDate().toString() })
        val year = FirstDue.plan(items, at(perth, "2026-12-31T23:30"), perth)
        assertEquals(listOf("2027-01-01", "2027-01-02", "2027-01-03"), year.map { local(perth, it.dueAt).toLocalDate().toString() })
        // A picked 29 Feb is kept as picked.
        val rego = CatalogueFilter.localise(catalogue.item("car-rego-renewal")!!, Region("AU-WA"))
        val picked = FirstDue.plan(listOf(rego), at(perth, "2027-11-27T12:00"), perth, mapOf(rego.id to LocalDate.parse("2028-02-29"))).single()
        assertEquals(LocalDateTime.parse("2028-02-29T19:00"), local(perth, picked.dueAt))
        assertEquals(RepeatUnit.YEAR, picked.rule!!.unit)
    }

    @Test
    fun suggestedMonthEdges() {
        val tax = CatalogueFilter.localise(catalogue.item("money-tax-return")!!, Region("AU-WA"))
        val school = CatalogueFilter.localise(catalogue.item("kids-school-forms")!!, Region("AU-WA"))
        // 31 Dec, late: school forms 1 Jan next year (tomorrow).
        assertEquals(LocalDateTime.parse("2027-01-01T19:00"), local(perth, FirstDue.plan(listOf(school), at(perth, "2026-12-31T23:00"), perth).single().dueAt))
        // 30 June: tax is tomorrow.
        assertEquals(LocalDateTime.parse("2026-07-01T19:00"), local(perth, FirstDue.plan(listOf(tax), at(perth, "2026-06-30T20:00"), perth).single().dueAt))
        // Today: 27 Nov 2026, tax next July.
        assertEquals(LocalDateTime.parse("2027-07-01T19:00"), local(perth, FirstDue.plan(listOf(tax), at(perth, "2026-11-27T16:00"), perth).single().dueAt))
    }

    @Test
    fun anItemOfferedInItsOwnMonthIsNotDueElevenMonthsLater() {
        // QA: fails until an item added during its suggested month gets a near first due (the seasonal card offers it "around now").
        val items = CatalogueFilter.forRegion(catalogue, Region("AU-WA"))
        val today = LocalDate.parse("2026-07-02")
        val decision = SuggestionPolicy.decide(
            SuggestionInput(today, setOf("money"), emptySet(), emptyList(), items, SuggestionState.DEFAULT),
        )
        val card = decision.card!!
        assertEquals(CardKind.SEASONAL, card.kind)
        val tax = card.items.first { it.id == "money-tax-return" }
        val now = at(perth, "2026-07-02T12:00")
        val due = FirstDue.plan(listOf(tax), now, perth).single().dueAt
        val days = Duration.ofMillis(due - now).toDays()
        assertTrue("tax added on 2 July from the seasonal card is due in $days days (${local(perth, due)})", days <= 31)
    }

    // ---- Check 6: SuggestionPolicy -----------------------------------------------------

    private fun input(today: String, state: SuggestionState, areas: Set<String> = emptySet(), added: Set<String> = emptySet(), done: List<String> = emptyList()) =
        SuggestionInput(LocalDate.parse(today), areas, added, done, wa, state)

    @Test
    fun backoffsAreExactly14Then60Days() {
        val skipped = SuggestionPolicy.skipped(SuggestionState.DEFAULT, LocalDate.parse("2026-11-27"))
        assertNull(SuggestionPolicy.decide(input("2026-11-27", skipped)).card)
        assertEquals(CardKind.HEAD_START, SuggestionPolicy.decide(input("2026-11-28", skipped)).card!!.kind)
        val once = SuggestionPolicy.dismiss(skipped, CardKind.HEAD_START, LocalDate.parse("2026-11-28"))
        // Day 13 hidden; nothing else qualifies in late November for a WA user with all areas except... seasonal.
        val d13 = SuggestionPolicy.decide(input("2026-12-11", once)).card
        assertTrue(d13 == null || d13.kind != CardKind.HEAD_START)
        assertEquals(CardKind.HEAD_START, SuggestionPolicy.decide(input("2026-12-12", once)).card!!.kind)
        val twice = SuggestionPolicy.dismiss(once, CardKind.HEAD_START, LocalDate.parse("2026-12-12"))
        assertEquals(LocalDate.parse("2027-02-10"), SuggestionPolicy.hiddenUntil(twice, CardKind.HEAD_START))
        assertTrue(SuggestionPolicy.decide(input("2027-02-09", twice)).card?.kind != CardKind.HEAD_START)
        assertEquals(CardKind.HEAD_START, SuggestionPolicy.decide(input("2027-02-10", twice)).card!!.kind)
        val thrice = SuggestionPolicy.dismiss(twice, CardKind.HEAD_START, LocalDate.parse("2027-02-10"))
        assertEquals(LocalDate.parse("2027-04-11"), SuggestionPolicy.hiddenUntil(thrice, CardKind.HEAD_START))
        // A dismissal of one kind does not hide another.
        assertNull(SuggestionPolicy.hiddenUntil(thrice, CardKind.SEASONAL))
    }

    @Test
    fun stopSuggestingBeatsEveryRule() {
        var state = SuggestionPolicy.skipped(SuggestionState.DEFAULT, LocalDate.parse("2026-11-01"))
        state = SuggestionPolicy.stop(state)
        listOf("2026-11-02", "2026-12-15", "2027-06-20").forEach { day ->
            val decision = SuggestionPolicy.decide(input(day, state, done = listOf("car-service")))
            assertNull(day, decision.card)
            assertEquals("stopped", decision.reason)
        }
        // Survives an encode and decode.
        assertTrue(SuggestionState.decode(state.encode()).stopped)
        assertEquals(state, SuggestionState.decode(state.encode()))
        assertEquals(SuggestionState.DEFAULT, SuggestionState.decode("{not json"))
    }

    @Test
    fun seasonalOncePerCalendarMonthAndWrapsTheYear() {
        // December: January school forms qualify ("this month or next").
        val dec = SuggestionPolicy.decide(input("2026-12-03", SuggestionState.DEFAULT, areas = setOf("kids"))).card!!
        assertEquals(CardKind.SEASONAL, dec.kind)
        assertEquals(listOf("kids-school-forms"), dec.items.map { it.id })
        val shown = SuggestionPolicy.shown(SuggestionState.DEFAULT, dec, LocalDate.parse("2026-12-03"))
        assertNotNull(SuggestionPolicy.decide(input("2026-12-03", shown, areas = setOf("kids"))).card)
        assertNull(SuggestionPolicy.decide(input("2026-12-04", shown, areas = setOf("kids"))).card)
        assertNull(SuggestionPolicy.decide(input("2026-12-31", shown, areas = setOf("kids"))).card)
        assertEquals(CardKind.SEASONAL, SuggestionPolicy.decide(input("2027-01-01", shown, areas = setOf("kids"))).card!!.kind)
        // Not in the user's areas, or already added: nothing.
        assertNull(SuggestionPolicy.decide(input("2026-12-03", SuggestionState.DEFAULT, areas = setOf("car"))).card)
        assertNull(SuggestionPolicy.decide(input("2026-12-03", SuggestionState.DEFAULT, areas = setOf("kids"), added = setOf("kids-school-forms"))).card)
    }

    @Test
    fun relatedOncePerItemAndOneCardAtATime() {
        val state = SuggestionPolicy.skipped(SuggestionState.DEFAULT, LocalDate.parse("2026-11-01"))
        // Something added: the head start is over, related wins over seasonal.
        val decision = SuggestionPolicy.decide(input("2026-11-10", state, added = setOf("car-service"), done = listOf("car-service")))
        val card = decision.card!!
        assertEquals(CardKind.RELATED, card.kind)
        assertEquals(1, card.items.size)
        assertEquals("car-tyre-rotation", card.items.single().id)
        val after = SuggestionPolicy.shown(state, card, LocalDate.parse("2026-11-10"))
        assertNotEquals(CardKind.RELATED, SuggestionPolicy.decide(input("2026-11-11", after, added = setOf("car-service"), done = listOf("car-service"))).card?.kind)
        // Head start while nothing is added, even with a Done catalogue item recorded.
        assertEquals(CardKind.HEAD_START, SuggestionPolicy.decide(input("2026-11-10", state, done = listOf("car-service"))).card!!.kind)
        assertTrue(SuggestionPolicy.decide(input("2026-11-10", state)).card!!.items.size <= SuggestionPolicy.HEAD_START_ITEMS)
    }

    private fun assertNotEquals(unexpected: Any?, actual: Any?) = assertFalse("$actual", unexpected == actual)

    // ---- Check 6: nothing in Spec 11 posts a notification or touches alarms -------------

    @Test
    fun spec11FilesNeverNotifyOrScheduleAlarms() {
        val root = listOf(File("src/main/java/com/evansappstudio/doapp"), File("app/src/main/java/com/evansappstudio/doapp")).first { it.isDirectory }
        val files = listOf(
            "catalogue/Catalogue.kt", "catalogue/Region.kt", "catalogue/Picks.kt", "catalogue/SuggestionPolicy.kt",
            "catalogue/CatalogueActions.kt", "catalogue/CatalogueService.kt", "ui/SysdScreens.kt", "ui/SysdViewModel.kt", "ui/DoLook.kt",
        )
        val banned = listOf("NotificationManager", "NotificationCompat", ".notify(", "AlarmManager", "setExact", "setAlarmClock", "rescheduleAll", "PendingIntent", "scheduler.")
        files.forEach { path ->
            val text = File(root, path).readText()
            banned.forEach { word -> assertFalse("$path uses $word", text.contains(word)) }
        }
    }

    // ---- Check 7: backups ------------------------------------------------------------

    @Test
    fun anOlderSchemaFileWithoutTheNewKeysStillImports() {
        val settings = PortableSettings(
            defaultNagMinutes = 10,
            snooze = SnoozeDue.DEFAULTS,
            quickTimes = QuickTimesSettings(QuickTimes.FACTORY, PostponeMode.AUTO),
            weekStart = WeekStart.MONDAY,
            parseDates = true,
            defaultAlertLevel = AlertLevel.STANDARD,
            repeatPromptMode = RepeatPromptMode.PROMPT,
        )
        val text = BackupCodec.encode(BackupDocument(1, BackupNames.APP_ID, "0.9", 10L, settings, emptyList()))
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertFalse(text.contains("sysd"))
        val decoded = (BackupCodec.decode(text) as BackupDecode.Ok).document.settings
        assertNull(decoded.sysdRegion)
        assertNull(decoded.sysdRegionSource)
        assertNull(decoded.sysdAreas)
        assertEquals(10, decoded.defaultNagMinutes)
        // A schema 1 file that does carry the keys (written by this build with an old schema stamp) keeps them.
        val withKeys = BackupCodec.encode(BackupDocument(1, BackupNames.APP_ID, "0.9", 10L, settings.copy(sysdRegion = "AU", sysdRegionSource = "USER", sysdAreas = listOf("pets")), emptyList()))
        val back = (BackupCodec.decode(withKeys) as BackupDecode.Ok).document.settings
        assertEquals("AU", back.sysdRegion)
        assertEquals(listOf("pets"), back.sysdAreas)
    }

    @Test
    fun wronglyTypedNewBackupKeysAreIgnoredNotFatal() {
        // QA: fails until decodeSettings reads sysdRegion and sysdRegionSource leniently (a number there throws out of BackupCodec.decode and crashes import).
        val settings = PortableSettings(
            defaultNagMinutes = 10,
            snooze = SnoozeDue.DEFAULTS,
            quickTimes = QuickTimesSettings(QuickTimes.FACTORY, PostponeMode.AUTO),
            weekStart = WeekStart.MONDAY,
            parseDates = true,
            defaultAlertLevel = AlertLevel.STANDARD,
            repeatPromptMode = RepeatPromptMode.PROMPT,
        )
        val withKeys = BackupCodec.encode(BackupDocument(1, BackupNames.APP_ID, "0.9", 10L, settings.copy(sysdRegion = "AU", sysdRegionSource = "USER", sysdAreas = listOf("pets")), emptyList()))
        val typed = withKeys.replace("\"sysdAreas\": [\"pets\"]", "\"sysdAreas\": \"pets\"").replace("\"sysdRegion\": \"AU\"", "\"sysdRegion\": 7")
        val odd = (BackupCodec.decode(typed) as BackupDecode.Ok).document.settings
        assertNull(odd.sysdAreas)
        assertNull(odd.sysdRegion)
    }

    // ---- Check 9 (AT-9): keyword match -----------------------------------------------------

    @Test
    fun carServiceOffersTwoCarItems() {
        val related = KeywordMatch.related("Book the car service", wa, emptySet())
        assertEquals(listOf("car-tyre-rotation", "car-rego-renewal"), related.map { it.id })
        assertTrue(related.all { it.item.category == "car" })
        // Added items are not offered; the next related one takes the slot.
        val next = KeywordMatch.related("car service", wa, setOf("car-tyre-rotation"))
        assertTrue(next.size <= KeywordMatch.MAX_ITEMS)
        assertFalse(next.any { it.id == "car-tyre-rotation" })
        // Many matches still give at most two.
        assertTrue(KeywordMatch.related("smoke alarm gutters roof tyres tax passport vet", wa, emptySet()).size <= 2)
        assertTrue(KeywordMatch.related("", wa, emptySet()).isEmpty())
    }

    @Test
    fun everydayTitlesDoNotOfferRelatedItems() {
        // QA: fails until broad keywords ("will", "tick", "oil", "glasses", "school", "super") stop matching everyday titles.
        val titles = listOf(
            "Call Will about Saturday",
            "I will ring mum",
            "Tick off the shopping list",
            "Buy olive oil",
            "Wash the wine glasses",
            "Pick up kids from school",
            "Super Bowl party",
        )
        val offers = titles.associateWith { KeywordMatch.related(it, wa, emptySet()).map { item -> item.id } }.filterValues { it.isNotEmpty() }
        assertEquals("unexpected Related offers: $offers", emptyMap<String, List<String>>(), offers)
    }
}
