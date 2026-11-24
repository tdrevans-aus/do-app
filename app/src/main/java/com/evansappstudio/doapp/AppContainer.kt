package com.evansappstudio.doapp

import android.content.Context
import androidx.room.Room
import com.evansappstudio.doapp.alarm.AlarmScheduler
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.ReminderMirror
import com.evansappstudio.doapp.alarm.Scheduler
import com.evansappstudio.doapp.data.AppDatabase
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.SettingsStore
import com.evansappstudio.doapp.notify.ReminderNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mirror = ReminderMirror(appContext)
    val alarms = AlarmScheduler(appContext)
    val notifications = ReminderNotifier(appContext)
    val settings = SettingsStore(appContext)

    private val database: AppDatabase by lazy {
        check(UserUnlock.isUnlocked(appContext)) { "Room is unavailable before unlock" }
        Room.databaseBuilder(appContext, AppDatabase::class.java, "do.db").build()
    }

    val scheduler: Scheduler by lazy {
        Scheduler(
            daoProvider = { database.reminderDao() },
            mirror = mirror,
            alarms = alarms,
            notifications = notifications,
            unlocked = { UserUnlock.isUnlocked(appContext) },
        )
    }

    fun reminderDao(): ReminderDao = database.reminderDao()

    fun start() {
        scope.launch {
            try {
                if (UserUnlock.isUnlocked(appContext)) {
                    scheduler.rescheduleAll()
                } else {
                    scheduler.rescheduleFromMirror()
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            }
        }
    }
}
