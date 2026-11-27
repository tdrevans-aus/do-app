package com.evansappstudio.doapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Reads that feed the UI, counts, scheduling, health, backup export and the
 * free-tier cap skip soft-deleted rows. [getById] and [getByUuid] return them
 * (restore and merge need them); callers that act on a row check [Reminder.deletedAt].
 *
 * Writes that change a row set updatedAt and add one to version. They return
 * the number of rows changed so the caller writes an event only for a real change.
 */
@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder WHERE deletedAt IS NULL ORDER BY isDone ASC, dueAt ASC, id ASC")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminder WHERE isDone = 0 AND deletedAt IS NULL ORDER BY dueAt ASC, id ASC")
    suspend fun active(): List<Reminder>

    @Query("SELECT COUNT(*) FROM reminder WHERE isDone = 0 AND deletedAt IS NULL")
    suspend fun activeCount(): Int

    @Query("SELECT * FROM reminder WHERE deletedAt IS NULL")
    suspend fun all(): List<Reminder>

    /** Every row, soft-deleted ones included. Internal use only (Replace import). */
    @Query("SELECT * FROM reminder")
    suspend fun allIncludingDeleted(): List<Reminder>

    /** Returns soft-deleted rows too. */
    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    /** Returns soft-deleted rows too, so merge import can restore a match. */
    @Query("SELECT * FROM reminder WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): Reminder?

    /** Soft-deleted rows whose deletedAt is before [cutoff]. */
    @Query("SELECT * FROM reminder WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deletedBefore(cutoff: Long): List<Reminder>

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Query("DELETE FROM reminder")
    suspend fun deleteAll()

    @Query("DELETE FROM reminder WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    @Query(
        "UPDATE reminder SET isDone = 1, doneAt = :doneAt, updatedAt = :now, version = version + 1 " +
            "WHERE id = :id AND isDone = 0 AND deletedAt IS NULL",
    )
    suspend fun markDone(id: Long, doneAt: Long, now: Long): Int

    @Query(
        "UPDATE reminder SET dueAt = :dueAt, updatedAt = :now, version = version + 1 " +
            "WHERE id = :id AND isDone = 0 AND deletedAt IS NULL",
    )
    suspend fun updateDueAt(id: Long, dueAt: Long, now: Long): Int

    @Query(
        "UPDATE reminder SET dueAt = :dueAt, patternDueAt = :patternDueAt, updatedAt = :now, " +
            "version = version + 1 WHERE id = :id AND isDone = 0 AND deletedAt IS NULL",
    )
    suspend fun updateDueAndPattern(id: Long, dueAt: Long, patternDueAt: Long?, now: Long): Int

    @Query(
        "UPDATE reminder SET dueAt = :dueAt, patternDueAt = :patternDueAt, isDone = 0, doneAt = NULL, " +
            "updatedAt = :now, version = version + 1 WHERE id = :id AND isDone = 0 AND deletedAt IS NULL",
    )
    suspend fun advanceOccurrence(id: Long, dueAt: Long, patternDueAt: Long?, now: Long): Int

    @Query(
        "UPDATE reminder SET title = :title, dueAt = :dueAt, nagMinutes = :nagMinutes, " +
            "repeatInterval = :repeatInterval, repeatUnit = :repeatUnit, repeatFrom = :repeatFrom, " +
            "repeatWeekDays = :repeatWeekDays, repeatMonthOn = :repeatMonthOn, " +
            "patternDueAt = :patternDueAt, alertLevel = :alertLevel, updatedAt = :now, version = version + 1 " +
            "WHERE id = :id AND isDone = 0 AND deletedAt IS NULL",
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
        now: Long,
    ): Int

    /** A change that lives outside Room (Stop silences the alert) still counts as a change. */
    @Query(
        "UPDATE reminder SET updatedAt = :now, version = version + 1 " +
            "WHERE id = :id AND isDone = 0 AND deletedAt IS NULL",
    )
    suspend fun touch(id: Long, now: Long): Int

    @Query(
        "UPDATE reminder SET deletedAt = :now, updatedAt = :now, version = version + 1 " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun softDelete(id: Long, now: Long): Int

    @Query(
        "UPDATE reminder SET deletedAt = NULL, updatedAt = :now, version = version + 1 " +
            "WHERE id = :id AND deletedAt IS NOT NULL",
    )
    suspend fun restore(id: Long, now: Long): Int
}

suspend fun ReminderDao.updateDetails(reminder: Reminder, now: Long): Int {
    return updateDetails(
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
        now = now,
    )
}
