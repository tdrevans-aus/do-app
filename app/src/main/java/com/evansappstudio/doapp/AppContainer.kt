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
import com.evansappstudio.doapp.billing.SubscriptionBilling
import com.evansappstudio.doapp.billing.SubscriptionState
import com.evansappstudio.doapp.billing.createSubscriptionBilling
import com.evansappstudio.doapp.catalogue.CatalogueActions
import com.evansappstudio.doapp.catalogue.CatalogueService
import com.evansappstudio.doapp.capture.AgentFunctions
import com.evansappstudio.doapp.capture.CaptureCommands
import com.evansappstudio.doapp.billing.EntitlementRecord
import com.evansappstudio.doapp.cloud.CloudConfig
import com.evansappstudio.doapp.cloud.CloudInbox
import com.evansappstudio.doapp.cloud.CloudMirror
import com.evansappstudio.doapp.cloud.CloudNetworkWatcher
import com.evansappstudio.doapp.cloud.CloudStore
import com.evansappstudio.doapp.cloud.DoCloud
import com.evansappstudio.doapp.cloud.FirebaseCloud
import com.evansappstudio.doapp.cloud.WorkManagerCloudJobs
import com.evansappstudio.doapp.widget.WidgetRefresher
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

    /** Home-screen widgets, refreshed from the command layer, fires, boot and clock changes. */
    val widgets = WidgetRefresher(appContext, scope)
    val mirror = ReminderMirror(appContext)
    val alarms = AlarmScheduler(appContext)
    val snoozeConfig = SnoozeConfig(appContext)
    val notifications = ReminderNotifier(appContext, snoozeConfig::get)
    val settings = SettingsStore(appContext)
    val quiet = QuietHoursStore(appContext)
    val zones = ZoneMemory(appContext)
    val entitlement = EntitlementStore(appContext)
    val backupStore = BackupStore(appContext)
    val billing: SubscriptionBilling = createSubscriptionBilling(appContext, entitlement)
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
            paidUnlocked = { SubscriptionState.of(entitlement.read()).liftsCap },
            inTransaction = { body -> database.withTransaction { body() } },
            onCommitted = { backup.noteChange() },
            quietConfig = { quiet.read() },
            dndActive = { DndState.isActive(appContext) },
            onFired = { cloudChanged() },
        )
    }

    /** The one public way to change reminders. Every caller passes an Origin. */
    val commands: ReminderCommands by lazy {
        ReminderCommands(scheduler).also {
            it.addListener(widgets::request)
            it.addListener(::cloudChanged)
        }
    }

    /** Spec 12: the Add screen, Share, the widgets, the tile and links save through this. */
    val capture: CaptureCommands by lazy { CaptureCommands(commands) }

    /** Spec 12: the layer under AppFunctions. Off unless BuildConfig.APP_FUNCTIONS_ENABLED. */
    val agent: AgentFunctions by lazy {
        AgentFunctions(
            enabled = BuildConfig.APP_FUNCTIONS_ENABLED,
            commands = commands,
            active = { reminderDao().active() },
            byId = { id -> reminderDao().getById(id) },
            defaultNagMinutes = { settings.defaultNagMinutes() },
        )
    }

    /**
     * Spec 13: Do Cloud. Null when the build has no cloud values (release until Tim
     * creates the project): then every cloud feature is hidden and nothing Firebase
     * starts. Built on first use after unlock (its state is in credential storage).
     */
    val cloudConfig = CloudConfig(
        projectId = BuildConfig.CLOUD_PROJECT_ID,
        apiKey = BuildConfig.CLOUD_API_KEY,
        appId = BuildConfig.CLOUD_APP_ID,
        emulatorHost = BuildConfig.CLOUD_EMULATOR_HOST,
        siteUrl = BuildConfig.CLOUD_SITE_URL,
    )

    val cloud: DoCloud? by lazy {
        if (!cloudConfig.enabled) return@lazy null
        check(UserUnlock.isUnlocked(appContext)) { "Do Cloud is unavailable before unlock" }
        val store = CloudStore(appContext)
        DoCloud(
            config = cloudConfig,
            portFactory = { FirebaseCloud(appContext, cloudConfig) },
            store = store,
            inbox = {
                CloudInbox(
                    agent = {
                        AgentFunctions(
                            enabled = store.assistantsEnabled(),
                            commands = commands,
                            active = { reminderDao().active() },
                            byId = { id -> reminderDao().getById(id) },
                            defaultNagMinutes = { settings.defaultNagMinutes() },
                        )
                    },
                    commands = commands,
                    byId = { id -> reminderDao().getById(id) },
                    byUuid = { uuid -> reminderDao().getByUuid(uuid) },
                    askBeforeRingThrough = { store.askBeforeRingThrough() },
                )
            },
            mirror = { port ->
                CloudMirror(
                    port = port,
                    store = store,
                    reminders = { reminderDao().allIncludingDeleted() },
                    eventsAfter = { after, limit -> reminderEventDao().after(after, limit) },
                    maxEventId = { reminderEventDao().maxId() },
                )
            },
            commands = { commands },
            jobs = WorkManagerCloudJobs(appContext),
            entitlement = object : DoCloud.EntitlementAccess {
                override fun read(): EntitlementRecord = entitlement.read()
                override fun write(record: EntitlementRecord) = entitlement.write(record)
                override fun changed() = billing.reload()
            },
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    /**
     * Spec 13 (QA M1): starts a Do Unlimited purchase. With Do Cloud on, signs in first
     * (at most 10 seconds) so the purchase carries this installation's obfuscated account id;
     * offline, the purchase goes ahead without it and Spec 10's local result stands.
     */
    fun startPurchase(activity: android.app.Activity) {
        if (!cloudConfig.enabled || !UserUnlock.isUnlocked(appContext)) {
            billing.buy(activity)
            return
        }
        scope.launch {
            val accountId = try {
                cloud?.purchaseAccountId()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
                null
            }
            DoAlarmLog.cloud("purchase start bound=${accountId != null}")
            kotlinx.coroutines.withContext(Dispatchers.Main) { billing.buy(activity, accountId) }
        }
    }

    /** Spec 13: after any reminder change or fire. Never throws, never waits. */
    private fun cloudChanged() {
        if (!cloudConfig.enabled || !UserUnlock.isUnlocked(appContext)) return
        try {
            cloud?.onRemindersChanged()
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
        }
    }

    private val cloudStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Spec 13: wires Do Cloud once unlocked (app start, or the first resume after a direct-boot
     * start) and pulls when this device already has a cloud identity. Safe to call often.
     */
    fun startCloud() {
        if (!cloudConfig.enabled || !UserUnlock.isUnlocked(appContext)) return
        try {
            val cloud = cloud ?: return
            if (!cloudStarted.compareAndSet(false, true)) {
                cloud.onAppStart()
                return
            }
            billing.setPurchaseObserver { productId, token, justBought ->
                scope.launch {
                    try {
                        val outcome = cloud.onPurchase(productId, token, justBought, appContext.packageName)
                        DoAlarmLog.cloud("purchase outcome=${outcome.name.lowercase()} justBought=$justBought")
                    } catch (error: Throwable) {
                        DoAlarmLog.handlingFailed(id = -1, error)
                    }
                }
            }
            CloudNetworkWatcher(appContext) { cloud.onNetworkAvailable() }.start()
            cloud.onAppStart()
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
        }
    }

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

    /** Stuff You Should Do. Adds and removes go through [commands]. */
    val catalogue: CatalogueService by lazy {
        CatalogueService(
            context = appContext,
            settings = settings,
            actions = CatalogueActions(
                commands = { commands },
                dao = { reminderDao() },
                events = { reminderEventDao() },
            ),
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
        startCloud()
        billing.start()
        widgets.start()
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
