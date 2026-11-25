package com.evansappstudio.doapp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminder")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
