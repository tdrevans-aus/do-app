package com.evansappstudio.doapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Rows are deleted only by retention [trim], purge and Replace import. */
@Dao
interface ReminderEventDao {
    @Insert
    suspend fun insert(event: ReminderEvent): Long

    /** Newest first. */
    @Query("SELECT * FROM reminder_event WHERE reminderUuid = :uuid ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun forReminder(uuid: String, limit: Int): List<ReminderEvent>

    /** Keeps the newest [keep] events for [uuid]. */
    @Query(
        "DELETE FROM reminder_event WHERE reminderUuid = :uuid AND id NOT IN " +
            "(SELECT id FROM reminder_event WHERE reminderUuid = :uuid ORDER BY at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(uuid: String, keep: Int)

    /** Callers pass at most a few hundred uuids per call (SQLite before Android 11 allows 999 variables). */
    @Query("DELETE FROM reminder_event WHERE reminderUuid IN (:uuids)")
    suspend fun deleteForReminders(uuids: List<String>)

    @Query("DELETE FROM reminder_event")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM reminder_event")
    suspend fun count(): Int
}
