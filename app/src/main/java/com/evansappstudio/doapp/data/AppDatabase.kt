package com.evansappstudio.doapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Reminder::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
}
