package com.evansappstudio.doapp.data

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
)
