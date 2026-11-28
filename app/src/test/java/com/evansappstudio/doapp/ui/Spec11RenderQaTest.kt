package com.evansappstudio.doapp.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.catalogue.CardKind
import com.evansappstudio.doapp.catalogue.CatalogueFilter
import com.evansappstudio.doapp.catalogue.CatalogueParser
import com.evansappstudio.doapp.catalogue.Region
import com.evansappstudio.doapp.catalogue.SuggestionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** QA: renders Spec 11 screens with Robolectric (no emulator). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Spec11RenderQaTest {
    val compose = createComposeRule()

    // ui-test-manifest is not merged into the unit-test manifest, so register its activity first.
    @get:Rule
    val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(object : org.junit.rules.ExternalResource() {
        override fun before() {
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            org.robolectric.Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                android.content.ComponentName(app, androidx.activity.ComponentActivity::class.java),
            )
        }
    }).around(compose)

    private val items by lazy {
        val file = listOf(File("src/main/assets/catalogue/sysd.json"), File("app/src/main/assets/catalogue/sysd.json")).first { it.isFile }
        CatalogueFilter.forRegion(CatalogueParser.parse(file.readText()).catalogue, Region("AU-WA"))
    }

    @Test
    fun areasScreenSkipAndNext() {
        var skipped = 0
        var next: Set<String>? = null
        compose.setContent { AreasScreen(initial = emptySet(), onNext = { next = it }, onSkip = { skipped++ }) }
        compose.onNodeWithText("What do you look after?").assertExists()
        compose.onNodeWithText("Your answers stay on this phone and only shape suggestions.").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Skip").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals(1, skipped)
        compose.onNodeWithText("Car").performClick()
        compose.onNodeWithText("Pets").performClick()
        compose.onNodeWithText("Next").assertIsEnabled().performClick()
        assertEquals(setOf("car", "pets"), next)
    }

    @Test
    fun todayCardShowsOneSetOfActions() {
        val card = SuggestionCard(CardKind.SEASONAL, items.filter { it.id == "kids-school-forms" })
        var notNow = 0
        var stop = 0
        val added = ArrayList<String>()
        compose.setContent {
            TodayCard(card = card, doneTitle = null, onAdd = { item, _ -> added += item.id }, onOpenCatalogue = {}, onNotNow = { notNow++ }, onStop = { stop++ })
        }
        compose.onNodeWithText("Coming up this time of year").assertExists()
        compose.onNodeWithText("Not now").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Stop suggesting").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Add").performClick()
        assertEquals(1, notNow)
        assertEquals(1, stop)
        assertEquals(listOf("kids-school-forms"), added)
    }

    @Test
    fun relatedLineOffersAtMostTwo() {
        val related = com.evansappstudio.doapp.catalogue.KeywordMatch.related("car service", items, emptySet())
        compose.setContent { RelatedLine(items = related, onAdd = { _, _ -> }) }
        compose.onNodeWithText("Related:").assertExists()
        assertTrue(compose.onAllNodesWithText("Add ", substring = true).fetchSemanticsNodes().size <= 2)
    }

    @Test
    fun todayCardAddButtonsSayWhatTheyAdd() {
        // QA: fails until each "Add" button on the card names its item for TalkBack (two items read as "Add", "Add").
        val card = SuggestionCard(CardKind.HEAD_START, items.filter { it.id in setOf("home-smoke-alarm-test", "car-tyre-pressure") })
        compose.setContent { TodayCard(card = card, doneTitle = null, onAdd = { _, _ -> }, onOpenCatalogue = {}, onNotNow = {}, onStop = {}) }
        val nodes = compose.onAllNodes(
            androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText("Add"),
        ).fetchSemanticsNodes()
        assertEquals(2, nodes.size)
        nodes.forEach { node ->
            val said = (node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }.orEmpty() +
                node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty()).joinToString(" ")
            assertTrue("button reads only \"$said\"", card.items.any { said.contains(it.title) })
        }
    }
}
