package com.evansappstudio.doapp.catalogue

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderCommands
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.ReminderEventDao

/** How a batch add went. [blocked] is true when the free cap stopped it part way. */
data class CatalogueAddResult(val ids: List<Long>, val blocked: Boolean)

/**
 * Catalogue items in and out of the reminder list, always through
 * [ReminderCommands] with a CATALOGUE origin. Room access only through the DAOs,
 * so the JVM tests run it on the in-memory fixture.
 */
class CatalogueActions(
    private val commands: () -> ReminderCommands,
    private val dao: () -> ReminderDao,
    private val events: () -> ReminderEventDao,
) {
    /** Adds in order. Stops at the first add the free cap refuses. */
    suspend fun add(plans: List<PlannedAdd>, nagMinutes: Int?, actor: Actor = Actor.USER): CatalogueAddResult {
        val ids = ArrayList<Long>()
        for (plan in plans) {
            val item = plan.item.item
            val id = commands().add(
                origin = Origin(actor, ReminderSource.CATALOGUE, sourceId = item.id),
                title = plan.item.title,
                dueAt = plan.dueAt,
                nagMinutes = nagMinutes,
                rule = plan.rule,
                alertLevel = AlertLevel.fromName(item.alert.stored) ?: AlertLevel.STANDARD,
                category = item.category,
                templateId = item.id,
                templateVersion = item.version,
            ) ?: return CatalogueAddResult(ids, blocked = true)
            ids += id
        }
        return CatalogueAddResult(ids, blocked = false)
    }

    /** Soft-deletes every live reminder made from [itemId]. Returns how many. */
    suspend fun remove(itemId: String, actor: Actor = Actor.USER): Int {
        val rows = dao().all().filter { it.templateId == itemId }
        rows.forEach { commands().delete(Origin(actor, ReminderSource.CATALOGUE, sourceId = itemId), it.id) }
        return rows.size
    }

    /** Item ids with at least one non-deleted reminder. */
    suspend fun addedIds(): Set<String> = addedIds(dao().all())

    /** Catalogue item ids whose reminder has been marked Done at least once, oldest Done first. */
    suspend fun doneItemIds(): List<String> {
        val firstDone = HashMap<String, Long>()
        for (row in dao().all()) {
            val itemId = row.templateId ?: continue
            val done = events().forReminder(row.uuid, EVENT_LOOKBACK)
                .filter { it.type == EventType.DONE.name || it.type == EventType.ADVANCED.name }
                .minOfOrNull { it.at } ?: continue
            firstDone[itemId] = minOf(done, firstDone[itemId] ?: Long.MAX_VALUE)
        }
        return firstDone.entries.sortedBy { it.value }.map { it.key }
    }

    companion object {
        private const val EVENT_LOOKBACK = 200

        fun addedIds(rows: List<Reminder>): Set<String> =
            rows.filter { it.deletedAt == null }.mapNotNull { it.templateId }.toSet()
    }
}
