package com.evansappstudio.doapp.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    @Test
    fun summaryCountsOverdueAndShowsTheNextThree() {
        val now = 10_000L
        val items = listOf(
            WidgetItem(1, "Later", 50_000L),
            WidgetItem(2, "Overdue", 5_000L),
            WidgetItem(3, "Done", 1_000L, isDone = true),
            WidgetItem(4, "Due now", 10_000L),
            WidgetItem(5, "Soon", 20_000L),
            WidgetItem(6, "Much later", 90_000L),
        )
        val summary = WidgetModel.summarise(items, now)
        assertEquals(2, summary.overdue)
        assertEquals(5, summary.active)
        assertEquals(listOf(2L, 4L, 5L), summary.next.map { it.id })
        assertTrue(WidgetModel.isOverdue(items[1], now))
        assertEquals(WidgetSummary(0, 0, emptyList()), WidgetModel.summarise(emptyList(), now))
    }
}
