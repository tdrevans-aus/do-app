package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.data.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 11 section B: time zone to state, and filtering by region. */
class RegionTest {
    // AT-2 (each listed zone maps as in section B)
    @Test
    fun at2EachListedZoneMaps() {
        val expected = mapOf(
            "Australia/Perth" to "AU-WA",
            "Australia/Eucla" to "AU-WA",
            "Australia/Brisbane" to "AU-QLD",
            "Australia/Lindeman" to "AU-QLD",
            "Australia/Adelaide" to "AU-SA",
            "Australia/Darwin" to "AU-NT",
            "Australia/Hobart" to "AU-TAS",
            "Australia/Melbourne" to "AU-VIC",
            "Australia/Canberra" to "AU-ACT",
            "Australia/Broken_Hill" to "AU-NSW",
        )
        expected.forEach { (zone, state) ->
            val guess = RegionResolver.resolve(zone)
            assertEquals(zone, state, guess.region.code)
            assertFalse(zone, guess.needsChoice)
        }
    }

    // AT-2 (Sydney and Lord Howe are "NSW or ACT", which needs one tap)
    @Test
    fun at2SydneyAndLordHoweNeedOneTap() {
        listOf("Australia/Sydney", "Australia/Lord_Howe").forEach { zone ->
            val guess = RegionResolver.resolve(zone)
            assertTrue(zone, guess.needsChoice)
            assertEquals("AU", guess.region.code)
            assertEquals("AU", guess.region.country)
            assertNull(guess.region.state)
        }
        assertEquals(listOf("AU-NSW", "AU-ACT"), RegionResolver.NSW_OR_ACT)
    }

    // AT-2 (an unknown zone gives outside Australia)
    @Test
    fun at2UnknownZoneIsOutsideAustralia() {
        listOf("Europe/London", "Pacific/Auckland", "UTC", "", "Australia/Atlantis").forEach { zone ->
            val guess = RegionResolver.resolve(zone)
            assertEquals(zone, Region.OUTSIDE, guess.region)
            assertFalse(guess.needsChoice)
        }
        assertEquals(Region.OUTSIDE, RegionResolver.resolve(null).region)
    }

    // AT-2 (a USER choice is kept; AUTO follows the zone)
    @Test
    fun at2StoredSettingUserWinsAutoFollowsTheZone() {
        val user = RegionSetting.effective("AU-VIC", "USER", "Australia/Perth")
        assertEquals(Region("AU-VIC"), user.region)
        assertEquals(RegionSource.USER, user.source)
        val outside = RegionSetting.effective("none", "USER", "Australia/Perth")
        assertEquals(Region.OUTSIDE, outside.region)
        val auto = RegionSetting.effective("AU-VIC", "AUTO", "Australia/Perth")
        assertEquals(Region("AU-WA"), auto.region)
        assertEquals(RegionSource.AUTO, auto.source)
        assertEquals(Region("AU-WA"), RegionSetting.effective(null, null, "Australia/Perth").region)
        assertTrue(RegionSetting.effective(null, null, "Australia/Sydney").needsChoice)
        assertEquals(Region("AU-WA"), RegionResolver.parse("au-wa"))
        assertNull(RegionResolver.parse("AU-XX"))
    }

    // AT-3 (WA: hiddenIn WA excluded, WA rules apply)
    @Test
    fun at3WaHidesItemsAndAppliesWaRules() {
        val catalogue = bundled()
        val wa = CatalogueFilter.forRegion(catalogue, Region("AU-WA"))
        val ids = wa.map { it.id }
        assertFalse(ids.contains("money-energy-plan-compare"))
        assertTrue(ids.contains("home-smoke-alarm-test"))
        assertEquals(CatalogueInterval(3, RepeatUnit.MONTH), wa.first { it.id == "home-safety-switch-test" }.interval)
        assertEquals(catalogue.items.size - 1, wa.size)

        val nsw = CatalogueFilter.forRegion(catalogue, Region("AU-NSW"))
        assertTrue(nsw.any { it.id == "money-energy-plan-compare" })
        val safety = nsw.first { it.id == "home-safety-switch-test" }
        assertEquals(CatalogueInterval(6, RepeatUnit.MONTH), safety.interval)
        assertTrue(safety.why.contains("NSW"))
    }

    // AT-3 (a state rule for WA applies in WA and nowhere else)
    @Test
    fun at3StateRulesApplyOnlyInTheirState() {
        val item = testItem("home-x", regions = listOf("AU")).item.copy(
            stateRules = mapOf("AU-WA" to StateRule(CatalogueInterval(2, RepeatUnit.WEEK), "WA why", "WA title")),
            hiddenIn = listOf("AU-TAS"),
        )
        val wa = CatalogueFilter.localise(item, Region("AU-WA"))
        assertEquals("WA title", wa.title)
        assertEquals("WA why", wa.why)
        assertEquals(CatalogueInterval(2, RepeatUnit.WEEK), wa.interval)
        val vic = CatalogueFilter.localise(item, Region("AU-VIC"))
        assertEquals(item.title, vic.title)
        assertEquals(item.interval, vic.interval)
        assertFalse(CatalogueFilter.visible(item, Region("AU-TAS")))
        assertTrue(CatalogueFilter.visible(item, Region("AU")))
        val stateOnly = item.copy(regions = listOf("AU-WA"), hiddenIn = emptyList())
        assertTrue(CatalogueFilter.visible(stateOnly, Region("AU-WA")))
        assertFalse(CatalogueFilter.visible(stateOnly, Region("AU-QLD")))
        assertFalse(CatalogueFilter.visible(stateOnly, Region("AU")))
    }

    // AT-3 (outside Australia only "*" items show)
    @Test
    fun at3OutsideAustraliaShowsOnlyEverywhereItems() {
        val catalogue = bundled()
        val outside = CatalogueFilter.forRegion(catalogue, Region.OUTSIDE)
        assertTrue(outside.isNotEmpty())
        assertTrue(outside.all { "*" in it.item.regions })
        assertEquals(catalogue.items.count { "*" in it.regions }, outside.size)
        assertFalse(outside.any { it.id == "money-tax-return" })
    }
}
