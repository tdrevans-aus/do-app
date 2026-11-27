package com.evansappstudio.doapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminder")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable across export and import. Local [id] is not portable. */
    @ColumnInfo(defaultValue = "''")
    val uuid: String = "",
    val title: String,
    val dueAt: Long,
    val nagMinutes: Int?,
    val isDone: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long,
    val repeatInterval: Int? = null,
    val repeatUnit: String? = null,
    val repeatFrom: String? = null,
    val repeatWeekDays: String? = null,
    val repeatMonthOn: String? = null,
    val patternDueAt: Long? = null,
    @ColumnInfo(defaultValue = "STANDARD")
    val alertLevel: String = "STANDARD",
    /** Where it was created: a ReminderSource name. Set once at create; import keeps the exported value. */
    @ColumnInfo(defaultValue = "USER")
    val source: String = "USER",
    /** Catalogue item id or agent id, at most 200 characters. */
    val sourceId: String? = null,
    /** One of ReminderCategory.ALL, or null. Set only by add. */
    val category: String? = null,
    /** Catalogue item id it came from. Set only by add. */
    val templateId: String? = null,
    /** Catalogue item version it came from. Set only by add. */
    val templateVersion: Int? = null,
    /** Epoch millis of the last change. Migration backfills it from [createdAt]. */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L,
    /** Starts at 1 and goes up by one on every change. */
    @ColumnInfo(defaultValue = "1")
    val version: Int = 1,
    /** Set by soft delete. A deleted row is gone everywhere except [ReminderDao.getById]. */
    val deletedAt: Long? = null,
    /** An Approval name. Stored only in this spec. */
    @ColumnInfo(defaultValue = "NONE")
    val approval: String = "NONE",
    /** The level an agent asked for, kept beside [alertLevel], which is what is in force. Stored only. */
    val requestedAlertLevel: String? = null,
) {
    fun repeatRule(): RepeatRule? = RepeatRule.fromColumns(
        interval = repeatInterval,
        unit = repeatUnit,
        from = repeatFrom,
        weekDays = repeatWeekDays,
        monthOn = repeatMonthOn,
    )

    fun withRepeat(rule: RepeatRule?, patternDueAt: Long?): Reminder {
        val days = rule?.weekDays?.joinToString(",") { it.name }
        return copy(
            repeatInterval = rule?.interval,
            repeatUnit = rule?.unit?.name,
            repeatFrom = rule?.from?.name,
            repeatWeekDays = days?.takeIf { it.isNotEmpty() },
            repeatMonthOn = rule?.monthOn?.token(),
            patternDueAt = if (rule == null) null else patternDueAt,
        )
    }
}
