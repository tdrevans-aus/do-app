package com.evansappstudio.doapp.alarm

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.app.usage.UsageStatsManager
import com.evansappstudio.doapp.BuildConfig
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.backup.BackupHealth
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.notify.ReminderNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the phone, records boot receipts and late fires, and keeps the last
 * report so the menu badge is right before the next refresh.
 */
class HealthMonitor(
    context: Context,
    private val mirror: ReminderMirror,
    private val notifications: ReminderNotifier,
    private val scheduler: () -> Scheduler,
    private val commands: () -> ReminderCommands,
    private val dao: () -> ReminderDao,
    private val unlocked: () -> Boolean = { UserUnlock.isUnlocked(context) },
    private val exactMode: String = BuildConfig.EXACT_ALARM_MODE,
    private val backupHealth: () -> BackupHealth = {
        BackupHealth(ok = false, successAt = null, failed = false)
    },
) {
    private val appContext = context.applicationContext
    private val store = HealthStore(appContext)
    private val gate = Mutex()
    private val snapshotState = MutableStateFlow(store.loadReport())
    val snapshot: StateFlow<HealthReport> = snapshotState.asStateFlow()

    fun observeClock() {
        store.observeClock(readBootCount(), SystemClock.elapsedRealtime(), mirrorNeedsSchedule())
    }

    fun recordBoot(action: String?) {
        val locked = action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        if (!locked && action != Intent.ACTION_BOOT_COMPLETED) return
        store.recordBoot(locked, readBootCount(), SystemClock.elapsedRealtime(), mirrorNeedsSchedule())
    }

    suspend fun refresh() {
        gate.withLock {
            val report = evaluateNow()
            store.saveReport(report)
            snapshotState.value = report
        }
    }

    fun noteDelivery(id: Long, scheduled: Long, actual: Long) {
        if (!HealthEvaluator.isLate(scheduled, actual)) {
            if (scheduled >= 0L && actual >= scheduled) store.clearLate()
            return
        }
        val lag = actual - scheduled
        DoAlarmLog.healthLate(id, scheduled, actual, lag)
        store.setLate(lag)
    }

    suspend fun finishTest(id: Long, scheduled: Long, actual: Long) {
        val armed = store.testId() ?: return
        if (armed != id) return
        DoAlarmLog.healthTestFire(id, scheduled, actual)
        val early = scheduled >= 0L && actual < scheduled
        val result = HealthEvaluator.testStatus(scheduled, actual)
        store.setTestResult(result, early)
        if (result == HealthEvaluator.TEST_OK) store.clearLate()
        try {
            commands().markDone(Origin(Actor.SYSTEM), id)
            DoAlarmLog.healthTestDone(id)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        } finally {
            store.clearTestArmed()
        }
    }

    suspend fun noteSyntheticLate(lagMs: Long) {
        if (lagMs <= 0L || lagMs <= HealthEvaluator.LATE_BOUNDARY_MS) {
            store.clearLate()
        } else {
            store.setLate(lagMs)
        }
        DoAlarmLog.healthLateSynthetic(lagMs)
        refresh()
    }

    suspend fun armTest(): Long {
        if (!unlocked()) {
            DoAlarmLog.handlingFailed(id = -1, IllegalStateException("health test before unlock"))
            return 0L
        }
        val scheduled = gate.withLock {
            val existing = store.testId()
            if (existing != null && testStillActive(existing)) {
                val whenAt = store.testScheduled()
                DoAlarmLog.healthTestScheduled(whenAt, existing)
                return@withLock whenAt
            }
            val whenAt = System.currentTimeMillis() + TEST_DELAY_MS
            val id = commands().add(
                origin = Origin(Actor.SYSTEM),
                title = TEST_TITLE,
                dueAt = whenAt,
                nagMinutes = null,
                rule = null,
                alertLevel = AlertLevel.STANDARD,
            )
            if (id == null) {
                DoAlarmLog.handlingFailed(
                    id = -1,
                    IllegalStateException("health test blocked by the free cap"),
                )
                return@withLock 0L
            }
            store.setTestArmed(id, whenAt)
            DoAlarmLog.healthTestScheduled(whenAt, id)
            whenAt
        }
        refresh()
        return scheduled
    }

    suspend fun clearLate() {
        store.clearLate()
        refresh()
    }

    suspend fun restoreAlarms() {
        if (unlocked()) scheduler().rescheduleAll() else scheduler().rescheduleFromMirror()
        refresh()
    }

    suspend fun reenableBootReceiver() {
        val component = ComponentName(appContext, RescheduleReceiver::class.java)
        try {
            appContext.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
        }
        refresh()
        if (receiverDisabled()) {
            HealthIntents.open(
                appContext,
                HealthFix("App details", HealthActionKind.APP_DETAILS),
            )
        }
    }

    suspend fun dump() {
        refresh()
        DoAlarmLog.healthDump(snapshotState.value.dump)
    }

    private suspend fun testStillActive(id: Long): Boolean {
        if (!unlocked()) return true
        val row = try {
            dao().getById(id)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
            return true
        }
        if (row == null || row.isDone || row.deletedAt != null) {
            store.clearTestArmed()
            return false
        }
        return true
    }

    private suspend fun evaluateNow(): HealthReport {
        val channels = readChannels()
        val standby = readStandby()
        val storedBoot = store.bootSnapshot()
        val active = readActiveMirror()
        val generation = storedBoot.generation
        val backup = backupHealth()
        val facts = HealthFacts(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsGranted = AlarmPermissions.canPostNotifications(appContext),
            channels = channels,
            canScheduleExact = AlarmPermissions.canScheduleExact(appContext),
            exactMode = exactMode,
            fullScreenGranted = AlertPermissions.canUseFullScreenIntent(appContext),
            dndGranted = AlertPermissions.hasDndAccess(appContext),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
            backgroundRestricted = readBackgroundRestricted(),
            samsung = isSamsung(Build.MANUFACTURER, Build.BRAND),
            standbyBucket = standby,
            boot = BootFacts(
                receiverDisabled = receiverDisabled(),
                userUnlocked = unlocked(),
                rebootedSinceInstall = storedBoot.rebootedSinceInstall,
                unlockReceiptThisBoot = storedBoot.unlockGeneration == generation,
                lockedReceiptThisBoot = storedBoot.lockedGeneration == generation,
                mirrorNeededPreUnlock = storedBoot.mirrorNeededGeneration == generation,
                activeReminderCount = active.first,
                mirrorMissingActive = active.second,
            ),
            lateLagMs = store.lateLagMs(),
            testResult = store.testResult(),
            testPending = store.testId()?.let { testStillActive(it) } == true,
            testEarly = store.testEarly(),
            backupOk = backup.ok,
            backupFailed = backup.failed,
            backupSuccessText = backup.successAt?.let { formatBackupTime(it) }.orEmpty(),
        )
        return HealthEvaluator.evaluate(facts)
    }

    private fun readChannels(): List<ChannelFact> {
        notifications.ensureChannelsCreated()
        val context = if (unlocked()) appContext else appContext.createDeviceProtectedStorageContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        val specs = listOf(
            Triple(ReminderNotifier.CHANNEL_ID, "Reminders", false),
            Triple(ReminderNotifier.CRITICAL_CHANNEL_ID, "Critical reminders", false),
            Triple(ReminderNotifier.PROMINENT_CHANNEL_ID, "Prominent reminders", false),
            Triple(ReminderNotifier.SUMMARY_CHANNEL_ID, "Reminder summary", true),
        )
        return specs.map { (id, name, summary) ->
            val channel = manager?.getNotificationChannel(id)
            ChannelFact(
                id = id,
                name = name,
                present = channel != null,
                importance = channel?.importance ?: HealthEvaluator.IMPORTANCE_NONE,
                blocked = channelBlocked(channel),
                shouldExist = true,
                summary = summary,
            )
        }
    }

    private fun ignoringBatteryOptimizations(): Boolean {
        val power = appContext.getSystemService(PowerManager::class.java) ?: return false
        return try {
            power.isIgnoringBatteryOptimizations(appContext.packageName)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            false
        }
    }

    private fun readBackgroundRestricted(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val activity = appContext.getSystemService(ActivityManager::class.java) ?: return null
        return try {
            activity.isBackgroundRestricted
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            null
        }
    }

    private fun readStandby(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val stats = appContext.getSystemService(UsageStatsManager::class.java) ?: return null
        return try {
            stats.appStandbyBucket
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            null
        }
    }

    private fun receiverDisabled(): Boolean {
        val component = ComponentName(appContext, RescheduleReceiver::class.java)
        val state = try {
            appContext.packageManager.getComponentEnabledSetting(component)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            return false
        }
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    private suspend fun readActiveMirror(): Pair<Int, Boolean> {
        if (!unlocked()) return 0 to false
        return try {
            val active = dao().active()
            val ids = mirror.all().map { it.id }.toSet()
            active.size to active.any { it.id !in ids }
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            0 to false
        }
    }

    private fun mirrorNeedsSchedule(): Boolean {
        return mirror.all().any { !it.pendingDone && it.nextFireAt != null }
    }

    /**
     * API 28 added NotificationChannel.isBlocked(). On current SDKs that method
     * is the same as importance NONE, and API 36's public stub no longer declares
     * it. Call it when the device still has it, and otherwise rely on importance.
     */
    private fun channelBlocked(channel: android.app.NotificationChannel?): Boolean {
        if (channel == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val method = android.app.NotificationChannel::class.java.getMethod("isBlocked")
            method.invoke(channel) == true
        } catch (error: Throwable) {
            false
        }
    }

    private fun formatBackupTime(epochMs: Long): String {
        val formatter = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        )
        return formatter.format(java.util.Date(epochMs))
    }

    private fun readBootCount(): Int? {
        return try {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (error: Throwable) {
            null
        }
    }

    companion object {
        const val TEST_TITLE = "Health check test"
        const val TEST_DELAY_MS = 60_000L

        fun isSamsung(manufacturer: String?, brand: String?): Boolean {
            return manufacturer.equals("samsung", ignoreCase = true) ||
                brand.equals("samsung", ignoreCase = true)
        }
    }
}
