package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.parseJson
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 11 section A: the bundled catalogue and its parser. */
class CatalogueParserTest {
    // AT-1 (the bundled file parses with nothing skipped)
    @Test
    fun at1BundledFileParsesWithNothingSkipped() {
        val parse = CatalogueParser.parse(bundledText())
        assertEquals(emptyList<String>(), parse.skipped)
        assertEquals(1, parse.catalogue.schema)
        assertEquals(1, parse.catalogue.catalogueVersion)
        assertEquals(57, parse.catalogue.items.size)
        val raw = (parseJson(bundledText()) as JsonValue.Obj).fields["items"] as JsonValue.Arr
        assertEquals(raw.items.size, parse.catalogue.items.size)
    }

    // AT-1 (ids are unique and every related id exists)
    @Test
    fun at1IdsAreUniqueAndEveryRelatedIdExists() {
        val raw = (parseJson(bundledText()) as JsonValue.Obj).fields["items"] as JsonValue.Arr
        val ids = raw.items.map { ((it as JsonValue.Obj).fields["id"] as JsonValue.Str).value }
        assertEquals(ids.size, ids.toSet().size)
        val known = ids.toSet()
        raw.items.forEach { value ->
            val obj = value as JsonValue.Obj
            val id = (obj.fields["id"] as JsonValue.Str).value
            val related = (obj.fields["related"] as JsonValue.Arr).items.map { (it as JsonValue.Str).value }
            related.forEach { other ->
                assertTrue("$id -> $other", other in known)
                assertFalse("$id relates to itself", other == id)
            }
        }
        // The parsed related lists are the raw ones: nothing was dropped.
        bundled().items.forEach { item -> assertTrue(item.related.all { bundled().item(it) != null }) }
    }

    // AT-1 (the converted fields: alert names, renewal dates, state rules, hidden states)
    @Test
    fun at1ConvertedFieldsAreSetFromTheStarterNotes() {
        val catalogue = bundled()
        val needsDate = catalogue.items.filter { it.needsDate }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "renter-lease-end-check",
                "renter-contents-insurance",
                "car-rego-renewal",
                "car-insurance-renewal",
                "pets-council-registration",
                "money-insurance-renewals",
                "money-health-insurance-review",
            ),
            needsDate,
        )
        assertEquals(CatalogueAlert.LOUD, catalogue.item("car-rego-renewal")!!.alert)
        assertEquals("CRITICAL", catalogue.item("car-rego-renewal")!!.alert.stored)
        assertEquals(
            listOf("car-rego-renewal"),
            catalogue.items.filter { it.alert != CatalogueAlert.NORMAL }.map { it.id },
        )
        val safety = catalogue.item("home-safety-switch-test")!!
        assertEquals(CatalogueInterval(3, RepeatUnit.MONTH), safety.interval)
        assertEquals(CatalogueInterval(6, RepeatUnit.MONTH), safety.stateRules["AU-NSW"]!!.interval)
        assertEquals(listOf("AU-WA", "AU-TAS", "AU-NT"), catalogue.item("money-energy-plan-compare")!!.hiddenIn)
        assertEquals(7, catalogue.item("money-tax-return")!!.suggestedMonth)
        assertEquals(9 * 60, catalogue.item("home-smoke-alarm-test")!!.suggestedMinute)
        catalogue.items.forEach { item ->
            assertTrue(item.id, item.priority in 0..100)
            assertTrue(item.id, item.keywords.isNotEmpty())
            assertTrue(item.id, item.id.startsWith(item.category + "-"))
            assertFalse(item.id, item.title.contains('\u2014') || item.why.contains('\u2014'))
        }
    }

    // AT-1 (Tim's content decisions of 28 Nov 2026 are in the asset)
    @Test
    fun at1TimsContentDecisionsAreInTheAsset() {
        val catalogue = bundled()
        // One smoke alarm test, shown under Home and Renting.
        assertNull(catalogue.item("renter-smoke-alarm-test"))
        val smoke = catalogue.item("home-smoke-alarm-test")!!
        assertEquals("home", smoke.category)
        assertEquals(listOf("home", "renter"), smoke.areas)
        assertTrue(catalogue.items.none { "renter-smoke-alarm-test" in it.related })
        assertEquals(1, catalogue.items.count { it.title == "Test the smoke alarms" })
        catalogue.items.forEach { assertEquals(it.id, it.category, it.areas.first()) }
        // The 19 low-confidence starter items are never pre-ticked.
        val low = setOf(
            "home-hot-water-check", "home-dryer-vent-clean", "home-roof-check", "renter-inspection-prep",
            "renter-repairs-list", "renter-condition-photos", "kids-health-check", "kids-immunisation-check",
            "kids-school-forms", "kids-emergency-contacts", "kids-car-seat-check", "kids-clothes-shoes-sizes",
            "health-skin-check", "health-eye-test", "health-prescriptions-check", "money-will-review",
            "digital-password-check", "digital-app-permissions", "digital-account-recovery",
        )
        assertEquals(low, catalogue.items.filter { !it.preTick }.map { it.id }.toSet())
        // The two state-varying items now say to check the state's rules.
        listOf("kids-school-forms", "pets-microchip-details").forEach { id ->
            assertTrue(id, catalogue.item(id)!!.why.endsWith("Check your state's rules."))
        }
        catalogue.items.filter { it.stateVaries }.forEach { item ->
            assertTrue(item.id, item.why.contains("state", ignoreCase = true))
        }
    }

    // AT-1 (areas and preTick parse; an unknown area skips the item)
    @Test
    fun at1AreasAndPreTickParse() {
        val text = """{ "schema": 1, "catalogueVersion": 1, "items": [
            ${item("home-a").replace("\"link\": null", "\"link\": null, \"areas\": [\"renter\", \"home\"], \"preTick\": false")},
            ${item("home-b").replace("\"link\": null", "\"link\": null, \"areas\": [\"spaceship\"]")},
            ${item("home-c")}
        ] }"""
        val parse = CatalogueParser.parse(text)
        assertEquals(listOf("home-a", "home-c"), parse.catalogue.items.map { it.id })
        assertEquals(listOf("home", "renter"), parse.catalogue.item("home-a")!!.areas)
        assertFalse(parse.catalogue.item("home-a")!!.preTick)
        assertEquals(listOf("home"), parse.catalogue.item("home-c")!!.areas)
        assertTrue(parse.catalogue.item("home-c")!!.preTick)
    }

    // AT-1 (a malformed item is skipped and the rest load)
    @Test
    fun at1MalformedItemIsSkippedAndTheRestLoad() {
        val text = """
            { "schema": 1, "catalogueVersion": 7, "items": [
              ${item("home-a", related = "[\"home-c\", \"home-missing\"]")},
              { "id": "home-b", "version": 1, "category": "home", "title": "B", "why": "",
                "interval": { "every": 0, "unit": "MONTH" }, "repeatFrom": "COMPLETION",
                "suggestedTime": "09:00", "regions": ["*"] },
              "not an object",
              { "id": "home-d", "version": 1, "category": "spaceship", "title": "D",
                "interval": { "every": 1, "unit": "MONTH" }, "repeatFrom": "COMPLETION",
                "suggestedTime": "09:00", "regions": ["*"] },
              ${item("home-c")},
              ${item("home-a")}
            ] }
        """.trimIndent()
        val parse = CatalogueParser.parse(text)
        assertEquals(listOf("home-a", "home-c"), parse.catalogue.items.map { it.id })
        assertEquals(7, parse.catalogue.catalogueVersion)
        assertEquals(listOf("home-c"), parse.catalogue.item("home-a")!!.related)
        // bad interval, not an object, bad category, duplicate id, unknown related
        assertEquals(5, parse.skipped.size)
        val a = parse.catalogue.item("home-a")!!
        assertEquals(RepeatFrom.COMPLETION, a.repeatFrom)
        assertNull(a.suggestedMonth)
    }

    // AT-1 (a file that is not a catalogue gives an empty one, not a crash)
    @Test
    fun at1BrokenFileGivesAnEmptyCatalogue() {
        assertTrue(CatalogueParser.parse("{ not json").catalogue.items.isEmpty())
        assertTrue(CatalogueParser.parse("{ \"schema\": 9, \"items\": [] }").catalogue.items.isEmpty())
        assertTrue(CatalogueParser.parse("").catalogue.items.isEmpty())
    }

    private fun item(id: String, related: String = "[]") = """
        { "id": "$id", "version": 1, "category": "home", "title": "Title", "why": "Why",
          "interval": { "every": 1, "unit": "MONTH" }, "repeatFrom": "COMPLETION",
          "suggestedMonth": null, "suggestedTime": "09:00", "alertLevel": "NORMAL",
          "regions": ["*"], "stateRules": {}, "hiddenIn": [], "needsDate": false,
          "priority": 50, "related": $related, "keywords": ["x"], "health": false, "link": null }
    """.trimIndent()
}
