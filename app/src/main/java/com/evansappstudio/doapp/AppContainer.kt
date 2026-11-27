package com.evansappstudio.doapp

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.room.withTransaction
import com.evansappstudio.doapp.alarm.AlarmScheduler
import com.evansappstudio.doapp.alarm.DndState
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.HealthMonitor
import com.evansappstudio.doapp.alarm.QuietHoursStore
import com.evansappstudio.doapp.alarm.ReminderCommands
import com.evansappstudio.doapp.alarm.ReminderMirror
import com.evansappstudio.doapp.alarm.Scheduler
import com.evansappstudio.doapp.alarm.SnoozeConfig
import com.evansappstudio.doapp.alarm.ZoneMemory
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.alarm.asAlerts
import com.evansappstudio.doapp.alarm.asArm
import com.evansappstudio.doapp.backup.BackupController
import com.evansappstudio.doapp.backup.BackupStore
import com.evansappstudio.doapp.billing.EntitlementStore
import com.evansappstudio.doapp.billing.UnlockBilling
import com.evansappstudio.doapp.billing.createUnlockBilling
import com.evansappstudio.doapp.data.AppDatabase
import com.evansappstudio.doapp.data.MIGRATION_1_2
import com.evansappstudio.doapp.data.MIGRATION_2_3
import com.evansappstudio.doapp.data.MIGRATION_3_4
import com.evansappstudio.doapp.data.MIGRATION_4_5
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.ReminderEventDao
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
    val quiet = QuietHoursStore(appContext)
    val zones = ZoneMemory(appContext)
    val entitlement = EntitlementStore(appContext)
    val backupStore = BackupStore(appContext)
    val billing: UnlockBilling = createUnlockBilling(appContext, entitlement)
    val health = HealthMonitor(
        appContext,
        mirror = mirror,
        notifications = notifications,
        scheduler = { scheduler },
        commands = { commands },
        dao = { reminderDao() },
        backupHealth = { backupStore.health() },
    )

    private val database: AppDatabase by lazy {
        check(UserUnlock.isUnlocked(appContext)) { "Room is unavailable before unlock" }
        Room.databaseBuilder(appContext, AppDatabase::class.java, "do.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    val scheduler: Scheduler by lazy {
        Scheduler(
            daoProvider = { database.reminderDao() },
            eventsProvider = { database.reminderEventDao() },
            mirror = mirror,
            alarms = alarms.asArm(),
            notifications = notifications.asAlerts(),
            unlocked = { UserUnlock.isUnlocked(appContext) },
            zones = zones,
            weekStart = { settings.weekStart() },
            paidUnlocked = { entitlement.read().unlocked },
            inTransaction = { body -> database.withTransaction { body() } },
            onCommitted = { backup.noteChange() },
            quietConfig = { quiet.read() },
            dndActive = { DndState.isActive(appContext) },
        )
    }

    /** The one public way to change reminders. Every caller passes an Origin. */
    val commands: ReminderCommands by lazy { ReminderCommands(scheduler) }

    val backup: BackupController by lazy {
        BackupController(
            context = appContext,
            store = backupStore,
            settings = settings,
            quiet = quiet,
            snoozeConfig = snoozeConfig,
            dao = { reminderDao() },
            commands = { commands },
            health = { health },
            scope = scope,
            versionName = BuildConfig.VERSION_NAME,
        )
    }

    fun reminderDao(): ReminderDao = database.reminderDao()

    fun reminderEventDao(): ReminderEventDao = database.reminderEventDao()

    /** Credential settings are the source of truth. The device-protected copy feeds notifications. */
    fun saveSnoozeOption(index: Int, option: SnoozeOption) {
        settings.setSnoozeOption(index, option)
        snoozeConfig.setAll(settings.snoozeOptions())
    }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) return
            reportDnd()
        }
    }

    /** Same path as the interruption-filter broadcast: log the live filter, then reconcile. */
    fun reportDnd() {
        val filter = DndState.filter(appContext)
        DoAlarmLog.dndFilter(filter, DndState.isActive(filter))
        scope.launch {
            try {
                scheduler.reconcileQuietHours()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            }
        }
    }

    fun start() {
        try {
            ContextCompat.registerReceiver(
                appContext,
                dndReceiver,
                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
        }
        billing.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    backup.noteChange()
                }
            },
        )
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
