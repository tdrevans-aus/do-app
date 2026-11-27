package com.evansappstudio.doapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One thing that happened to a reminder. Append-only: there is no update query.
 * Keyed by [reminderUuid], not the local id, so events survive export and import
 * of their reminder. [detail] is short key=value text and never holds the title.
 */
@Entity(tableName = "reminder_event", indices = [Index(value = ["reminderUuid", "at"])])
data class ReminderEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderUuid: String,
    val at: Long,
    val type: String,
    val actor: String,
    val sourceId: String? = null,
    val detail: String? = null,
)
