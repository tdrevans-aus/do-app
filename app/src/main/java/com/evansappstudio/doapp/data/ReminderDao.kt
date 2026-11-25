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

    @Query("UPDATE reminder SET dueAt = :dueAt WHERE id = :id AND isDone = 0")
    suspend fun updateDueAt(id: Long, dueAt: Long)

    @Query(
        "UPDATE reminder SET dueAt = :dueAt, patternDueAt = :patternDueAt WHERE id = :id AND isDone = 0",
    )
    suspend fun updateDueAndPattern(id: Long, dueAt: Long, patternDueAt: Long?)

    @Query(
        "UPDATE reminder SET dueAt = :dueAt, patternDueAt = :patternDueAt, isDone = 0, doneAt = NULL " +
            "WHERE id = :id AND isDone = 0",
    )
    suspend fun advanceOccurrence(id: Long, dueAt: Long, patternDueAt: Long?)

    @Query(
        "UPDATE reminder SET title = :title, dueAt = :dueAt, nagMinutes = :nagMinutes, " +
            "repeatInterval = :repeatInterval, repeatUnit = :repeatUnit, repeatFrom = :repeatFrom, " +
            "repeatWeekDays = :repeatWeekDays, repeatMonthOn = :repeatMonthOn, " +
            "patternDueAt = :patternDueAt, alertLevel = :alertLevel " +
            "WHERE id = :id AND isDone = 0",
    )
    suspend fun updateDetails(
        id: Long,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        repeatInterval: Int?,
        repeatUnit: String?,
        repeatFrom: String?,
        repeatWeekDays: String?,
        repeatMonthOn: String?,
        patternDueAt: Long?,
        alertLevel: String,
    )

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun deleteById(id: Long)
}

suspend fun ReminderDao.updateDetails(reminder: Reminder) {
    updateDetails(
        id = reminder.id,
        title = reminder.title,
        dueAt = reminder.dueAt,
        nagMinutes = reminder.nagMinutes,
        repeatInterval = reminder.repeatInterval,
        repeatUnit = reminder.repeatUnit,
        repeatFrom = reminder.repeatFrom,
        repeatWeekDays = reminder.repeatWeekDays,
        repeatMonthOn = reminder.repeatMonthOn,
        patternDueAt = reminder.patternDueAt,
        alertLevel = reminder.alertLevel,
    )
}
