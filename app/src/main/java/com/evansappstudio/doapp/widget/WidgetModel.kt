package com.evansappstudio.doapp.widget

// Spec 12 pure widget summary. No Android imports: this file is in CorePurityTest's list.

data class WidgetItem(val id: Long, val title: String, val dueAt: Long, val isDone: Boolean = false)

/** [next] is the soonest active reminders, overdue first; [overdue] counts every active one due by now. */
data class WidgetSummary(val overdue: Int, val active: Int, val next: List<WidgetItem>)

object WidgetModel {
    const val LARGE_ROWS = 3

    fun summarise(items: List<WidgetItem>, now: Long, rows: Int = LARGE_ROWS): WidgetSummary {
        val live = items.filter { !it.isDone }.sortedWith(compareBy<WidgetItem>({ it.dueAt }, { it.id }))
        return WidgetSummary(
            overdue = live.count { it.dueAt <= now },
            active = live.size,
            next = live.take(rows.coerceAtLeast(0)),
        )
    }

    fun isOverdue(item: WidgetItem, now: Long): Boolean = !item.isDone && item.dueAt <= now
}
