package com.evansappstudio.doapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder ORDER BY isDone ASC, dueAt ASC, id ASC")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminder WHERE isDone = 0 ORDER BY dueAt ASC, id ASC")
    suspend fun active(): List<Reminder>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("UPDATE reminder SET isDone = 1, doneAt = :doneAt WHERE id = :id AND isDone = 0")
    suspend fun markDone(id: Long, doneAt: Long)

    @Query(
        "UPDATE reminder SET title = :title, dueAt = :dueAt, nagMinutes = :nagMinutes " +
            "WHERE id = :id AND isDone = 0",
    )
    suspend fun updateDetails(id: Long, title: String, dueAt: Long, nagMinutes: Int?)

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun deleteById(id: Long)
}
