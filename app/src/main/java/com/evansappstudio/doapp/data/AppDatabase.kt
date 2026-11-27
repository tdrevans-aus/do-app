package com.evansappstudio.doapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Reminder::class, ReminderEvent::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    abstract fun reminderEventDao(): ReminderEventDao
}
