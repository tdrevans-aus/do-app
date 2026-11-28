package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderEvent

/**
 * Spec 13 mirror sync: tells each connected assistant about changes to the
 * reminders it created, and their new events. Only reminders whose source is
 * AGENT and whose sourceId is that assistant's id go to that assistant; a
 * person's own reminders, catalogue ones and on-phone (AppFunctions) agents'
 * ones are never sent. Events carry id, uuid, type and time only.
 *
 * Nothing advances until every call has succeeded, so a failure resends next
 * time (the server ignores older versions and repeated event ids).
 */
object MirrorPlanner {
    const val EVENT_PAGE = 1000

    data class Plan(
        val batches: List<MirrorBatch>,
        /** The event cursor to keep once every batch is sent. */
        val cursor: Long,
        /** uuid to version, for every reminder an assistant now knows. */
        val versions: Map<String, Int>,
    )

    private val EVENT_NAMES: Set<String> = EventType.entries.map { it.name }.toSet()

    fun plan(
        agentIds: Collection<String>,
        reminders: List<Reminder>,
        events: List<ReminderEvent>,
        cursor: Long,
        synced: Map<String, Int>,
    ): Plan {
        val agents = agentIds.filter { CloudAgent.isAgentId(it) }.toSet()
        val owned = reminders.filter { row -> row.uuid.isNotBlank() && agents.any { CloudInbox.ownedBy(row, it) } }
        val ownerOf = owned.associate { it.uuid to it.sourceId!! }
        val batches = ArrayList<MirrorBatch>()
        for (agentId in agents.sorted()) {
            val mine = owned.filter { it.sourceId == agentId }
            val changed = mine
                .filter { synced[it.uuid] != it.version }
                .sortedByDescending { it.updatedAt }
                .map(CloudInbox::wire)
            val newEvents = events
                .filter { it.id > cursor && ownerOf[it.reminderUuid] == agentId && it.type in EVENT_NAMES }
                .sortedBy { it.id }
                .map { MirrorEvent(id = it.id, reminderUuid = it.reminderUuid, type = it.type, at = it.at) }
            val reminderChunks = changed.chunked(MirrorBatch.MAX_REMINDERS)
            val eventChunks = newEvents.chunked(MirrorBatch.MAX_EVENTS)
            val count = maxOf(reminderChunks.size, eventChunks.size)
            for (index in 0 until count) {
                batches += MirrorBatch(
                    agentId = agentId,
                    reminders = reminderChunks.getOrElse(index) { emptyList() },
                    events = eventChunks.getOrElse(index) { emptyList() },
                )
            }
        }
        val newCursor = maxOf(cursor, events.maxOfOrNull { it.id } ?: cursor)
        val versions = owned.associate { it.uuid to it.version }
        return Plan(batches, newCursor, versions)
    }
}

/** Runs one mirror sync against [port]. */
class CloudMirror(
    private val port: CloudPort,
    private val store: CloudStore,
    private val reminders: suspend () -> List<Reminder>,
    private val eventsAfter: suspend (afterId: Long, limit: Int) -> List<ReminderEvent>,
    private val maxEventId: suspend () -> Long?,
) {
    data class Run(val batches: Int, val reminders: Int, val events: Int, val goneAgents: List<String>)

    /** [agentIds] are the assistants connected to this device. */
    suspend fun sync(agentIds: Collection<String>): Run {
        var cursor = store.mirrorCursor()
        // A Replace import empties the event log and SQLite may hand out the same ids again.
        if ((maxEventId() ?: 0L) < cursor) cursor = 0L
        var sentBatches = 0
        var sentReminders = 0
        var sentEvents = 0
        val gone = ArrayList<String>()
        var versions = store.mirrorVersions()
        // Pages of events, so a long offline stretch still drains.
        while (true) {
            val events = eventsAfter(cursor, MirrorPlanner.EVENT_PAGE)
            val plan = MirrorPlanner.plan(agentIds.filter { it !in gone }, reminders(), events, cursor, versions)
            for (batch in plan.batches) {
                try {
                    port.syncAgentMirror(batch)
                    sentBatches++
                    sentReminders += batch.reminders.size
                    sentEvents += batch.events.size
                } catch (error: CloudException) {
                    // A disconnected assistant: stop sending to it. Anything else: retry the whole sync later.
                    if (error.code == CloudCallCode.NOT_FOUND) gone += batch.agentId else throw error
                }
            }
            cursor = plan.cursor
            versions = plan.versions
            store.saveMirror(cursor, versions)
            if (events.size < MirrorPlanner.EVENT_PAGE) break
        }
        return Run(sentBatches, sentReminders, sentEvents, gone.distinct())
    }
}
