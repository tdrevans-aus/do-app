package com.evansappstudio.doapp.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 11 section F rule 5: the "Related: ..." line while typing in Add. */
class KeywordMatchTest {
    private val wa: List<LocalItem> by lazy { CatalogueFilter.forRegion(bundled(), Region("AU-WA")) }

    // AT-9 ("car service" returns the related car items, at most two)
    @Test
    fun at9CarServiceReturnsTheRelatedCarItems() {
        val related = KeywordMatch.related("Car service", wa, added = emptySet())
        assertEquals(listOf("car-tyre-rotation", "car-rego-renewal"), related.map { it.id })
        assertTrue(related.size <= KeywordMatch.MAX_ITEMS)
        assertTrue(related.all { it.item.category == "car" })
        assertEquals(listOf("car-service"), KeywordMatch.matches("Book car service Friday 9am", wa).map { it.id })
    }

    // AT-9 (added items are left out; never more than two)
    @Test
    fun at9AddedItemsAreLeftOutAndTheLimitHolds() {
        assertEquals(
            listOf("car-rego-renewal"),
            KeywordMatch.related("car service", wa, added = setOf("car-tyre-rotation")).map { it.id },
        )
        // Three items match here, with four related items between them; still at most two.
        val many = KeywordMatch.related("smoke alarm tyre pressure oil", wa, emptySet())
        assertEquals(2, many.size)
    }

    // AT-9 (whole words only, and nothing for unrelated text)
    @Test
    fun at9WholeWordsOnly() {
        assertTrue(KeywordMatch.related("Buy milk", wa, emptySet()).isEmpty())
        assertTrue(KeywordMatch.matches("Willow tree", wa).isEmpty())
        assertTrue(KeywordMatch.matches("supermarket", wa).isEmpty())
        assertTrue(KeywordMatch.related("", wa, emptySet()).isEmpty())
        assertEquals(listOf("money-drivers-licence"), KeywordMatch.matches("Renew driver's licence", wa).map { it.id })
    }
}
