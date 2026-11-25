package com.evansappstudio.doapp

import android.content.Context
import androidx.room.Room
import com.evansappstudio.doapp.alarm.AlarmScheduler
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.HealthMonitor
import com.evansappstudio.doapp.alarm.ReminderMirror
import com.evansappstudio.doapp.alarm.Scheduler
import com.evansappstudio.doapp.alarm.SnoozeConfig
import com.evansappstudio.doapp.alarm.ZoneMemory
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.data.AppDatabase
import com.evansappstudio.doapp.data.MIGRATION_1_2
import com.evansappstudio.doapp.data.MIGRATION_2_3
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
    val snoozeConfig = SnoozeConfig(appContext)
    val notifications = ReminderNotifier(appContext, snoozeConfig::get)
    val settings = SettingsStore(appContext)
    val zones = ZoneMemory(appContext)
    val health = HealthMonitor(
        appContext,
        mirror = mirror,
        notifications = notifications,
        scheduler = { scheduler },
        dao = { reminderDao() },
    )

    private val database: AppDatabase by lazy {
        check(UserUnlock.isUnlocked(appContext)) { "Room is unavailable before unlock" }
        Room.databaseBuilder(appContext, AppDatabase::class.java, "do.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    val scheduler: Scheduler by lazy {
        Scheduler(
            daoProvider = { database.reminderDao() },
            mirror = mirror,
            alarms = alarms,
            notifications = notifications,
            unlocked = { UserUnlock.isUnlocked(appContext) },
            zones = zones,
            weekStart = { settings.weekStart() },
        )
    }

    fun reminderDao(): ReminderDao = database.reminderDao()

    /** Credential settings are the source of truth. The device-protected copy feeds notifications. */
    fun saveSnoozeOption(index: Int, option: SnoozeOption) {
        settings.setSnoozeOption(index, option)
        snoozeConfig.setAll(settings.snoozeOptions())
    }

    fun start() {
        scope.launch {
            try {
                health.observeClock()
                if (UserUnlock.isUnlocked(appContext)) {
                    snoozeConfig.setAll(settings.snoozeOptions())
                    scheduler.rescheduleAll()
                } else {
                    scheduler.rescheduleFromMirror()
                }
                health.refresh()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            }
        }
    }
}
