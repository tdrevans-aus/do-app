package com.evansappstudio.doapp.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.evansappstudio.doapp.MainActivity
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.UserUnlock
import android.view.View
import com.evansappstudio.doapp.alarm.AlertDecision
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.AlertPermissions
import com.evansappstudio.doapp.alarm.AlertPolicy
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.DoneReceiver
import com.evansappstudio.doapp.alarm.ProminentAlertActivity
import com.evansappstudio.doapp.alarm.ReminderIds
import com.evansappstudio.doapp.alarm.StopReceiver
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.alarm.SnoozeLabels
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.alarm.SnoozeReceiver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReminderNotifier(
    context: Context,
    private val optionsProvider: () -> List<SnoozeOption>,
) {
    private val appContext = context.applicationContext

    fun decide(requested: AlertLevel): AlertDecision = prepare(requested)

    /** Creates the reminder channels when they are missing. A muted channel stays muted. */
    fun ensureChannelsCreated() {
        val context = notificationContext()
        val manager = manager(context) ?: return
        ensureChannels(context, manager)
    }

    /** Posts the fire. Prominent stays out of the quiet group summary. */
    fun show(id: Long, title: String, dueAt: Long, requested: AlertLevel = AlertLevel.STANDARD): AlertDecision {
        return try {
            val decision = prepare(requested)
            val others = readPosted().filter { it.id != id }
            if (decision.requested == AlertLevel.PROMINENT) {
                post(id, title, dueAt, decision, grouped = false, sound = true)
                return decision
            }
            val groupable = others.filter { it.requested != AlertLevel.PROMINENT }
            val grouped = groupable.isNotEmpty()
            post(id, title, dueAt, decision, grouped = grouped, sound = true)
            if (grouped) {
                for (other in groupable) {
                    post(other.id, other.title, other.dueAt, other.decision, grouped = true, sound = false)
                }
                postSummary(groupable.size + 1)
            } else {
                cancelSummary()
            }
            decision
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
            AlertPolicy.decide(requested, canUseFullScreenIntent = false, hasDndAccess = false, criticalChannelAvailable = false)
        }
    }

    /** The activity owns the looping sound while it is visible. */
    fun setLoopAudible(id: Long, audible: Boolean) {
        try {
            val item = readPosted().find { it.id == id } ?: return
            post(
                item.id,
                item.title,
                item.dueAt,
                item.decision,
                grouped = false,
                sound = audible && item.decision.loopUntilAttended,
            )
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    fun dismiss(id: Long) {
        try {
            manager(notificationContext())?.cancel(ReminderIds.asInt(id))
            val remaining = readPosted().filter { it.id != id && it.requested != AlertLevel.PROMINENT }
            when {
                remaining.size >= 2 -> postSummary(remaining.size)
                remaining.size == 1 -> {
                    val only = remaining.single()
                    post(only.id, only.title, only.dueAt, only.decision, grouped = false, sound = false)
                    cancelSummary()
                }
                else -> cancelSummary()
            }
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    fun showingIds(): List<Long> {
        return try {
            readPosted().map { it.id }
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            emptyList()
        }
    }

    /** Re-posts visible reminders so labels and baked extras match the current options. */
    fun rebuildShowing() {
        try {
            val posted = readPosted()
            val groupable = posted.filter { it.requested != AlertLevel.PROMINENT }
            val grouped = groupable.size >= 2
            for (item in groupable) {
                post(item.id, item.title, item.dueAt, prepare(item.requested), grouped = grouped, sound = false)
            }
            for (item in posted.filter { it.requested == AlertLevel.PROMINENT }) {
                post(item.id, item.title, item.dueAt, prepare(item.requested), grouped = false, sound = false)
            }
            if (grouped) postSummary(groupable.size) else cancelSummary()
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
        }
    }

    fun refreshIfShowing(id: Long, title: String, dueAt: Long, requested: AlertLevel = AlertLevel.STANDARD) {
        try {
            val posted = readPosted()
            if (posted.none { it.id == id }) return
            val decision = prepare(requested)
            val others = posted.filter { it.id != id && it.requested != AlertLevel.PROMINENT }
            val grouped = decision.requested != AlertLevel.PROMINENT && others.isNotEmpty()
            post(id, title, dueAt, decision, grouped = grouped, sound = false)
            if (grouped) postSummary(others.size + 1)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    private fun post(
        id: Long,
        title: String,
        dueAt: Long,
        decision: AlertDecision,
        grouped: Boolean,
        sound: Boolean,
    ) {
        val context = notificationContext()
        val manager = manager(context) ?: return
        ensureChannels(context, manager)
        val choices = options()
        val done = donePending(id)
        val snoozes = choices.mapIndexed { index, option ->
            SnoozeAction(
                snoozePending(id, index, option),
                SnoozeLabels.format(option),
                SnoozeLabels.contentDescription(option),
            )
        }
        val stop = if (decision.loopUntilAttended) stopPending(id) else null
        val category = if (decision.effective == AlertLevel.STANDARD) {
            NotificationCompat.CATEGORY_REMINDER
        } else {
            NotificationCompat.CATEGORY_ALARM
        }
        val builder = NotificationCompat.Builder(context, channelId(decision.effective))
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(dueText(dueAt))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(category)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!sound)
            .setAutoCancel(false)
            .setContentIntent(contentIntent(id))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(expandedView(context, title, dueAt, done, snoozes, decision, stop))
            .addExtras(reminderExtras(id, title, dueAt, decision))
        if (decision.effective == AlertLevel.CRITICAL) {
            builder.setSubText(context.getString(R.string.critical_label))
            builder.color = context.getColor(R.color.critical_label)
        }
        if (decision.effective == AlertLevel.STANDARD) {
            if (sound) {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            } else {
                builder.setDefaults(0)
                builder.setSound(null)
            }
        } else if (!sound) {
            builder.setDefaults(0)
            builder.setSound(null)
        }
        if (decision.requested != AlertLevel.PROMINENT && grouped) {
            builder.setGroup(GROUP_KEY)
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        }
        if (decision.fullScreen) {
            builder.setFullScreenIntent(fullScreenPending(id, title, dueAt), true)
        }
        // All four actions are on the notification so dumpsys lists every title.
        // The standard row shows at most three: Mark Done and the first two snoozes.
        // The expanded custom layout shows all four. Prominent also has Stop.
        builder.addAction(systemAction(context.getString(R.string.action_done), done))
        for (snooze in snoozes) {
            builder.addAction(systemAction(snooze.label, snooze.pending))
        }
        if (stop != null) {
            builder.addAction(systemAction(context.getString(R.string.action_stop), stop))
        }
        val notification = builder.build()
        if (sound && decision.effective == AlertLevel.STANDARD) {
            notification.flags = notification.flags and Notification.FLAG_ONLY_ALERT_ONCE.inv()
        }
        if (sound && decision.loopUntilAttended) {
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
        }
        manager.notify(ReminderIds.asInt(id), notification)
    }

    private fun postSummary(count: Int) {
        val context = notificationContext()
        val manager = manager(context) ?: return
        ensureChannels(context, manager)
        val option = options().first()
        val notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(context.getString(R.string.summary_overdue, count))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addAction(
                systemAction(
                    context.getString(R.string.action_snooze_all),
                    snoozeAllPending(option),
                ),
            )
            .build()
        manager.notify(ReminderIds.SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun cancelSummary() {
        manager(notificationContext())?.cancel(ReminderIds.SUMMARY_NOTIFICATION_ID)
    }

    private fun expandedView(
        context: Context,
        title: String,
        dueAt: Long,
        done: PendingIntent,
        snoozes: List<SnoozeAction>,
        decision: AlertDecision,
        stop: PendingIntent?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_reminder_expanded)
        val critical = decision.effective == AlertLevel.CRITICAL
        views.setViewVisibility(R.id.notification_label, if (critical) View.VISIBLE else View.GONE)
        if (critical) {
            views.setTextViewText(R.id.notification_label, context.getString(R.string.critical_label))
        }
        views.setTextViewText(R.id.notification_title, title)
        views.setTextViewText(R.id.notification_text, dueText(dueAt))
        val doneLabel = context.getString(R.string.action_done)
        views.setTextViewText(R.id.action_done, doneLabel)
        views.setContentDescription(R.id.action_done, doneLabel)
        views.setOnClickPendingIntent(R.id.action_done, done)
        val ids = intArrayOf(R.id.action_snooze_0, R.id.action_snooze_1, R.id.action_snooze_2)
        for (index in ids.indices) {
            views.setTextViewText(ids[index], snoozes[index].label)
            views.setContentDescription(ids[index], snoozes[index].description)
            views.setOnClickPendingIntent(ids[index], snoozes[index].pending)
        }
        if (stop != null) {
            views.setViewVisibility(R.id.action_stop, View.VISIBLE)
            views.setTextViewText(R.id.action_stop, context.getString(R.string.action_stop))
            views.setOnClickPendingIntent(R.id.action_stop, stop)
        } else {
            views.setViewVisibility(R.id.action_stop, View.GONE)
        }
        return views
    }

    private fun options(): List<SnoozeOption> {
        val loaded = runCatching { optionsProvider() }.getOrNull()
        return if (loaded != null && loaded.size == SnoozeDue.DEFAULTS.size && loaded.all { it.isValid() }) {
            loaded
        } else {
            SnoozeDue.DEFAULTS
        }
    }

    private fun readPosted(): List<PostedReminder> {
        val manager = manager(notificationContext()) ?: return emptyList()
        return manager.activeNotifications.mapNotNull { status ->
            if (status.id == ReminderIds.SUMMARY_NOTIFICATION_ID) return@mapNotNull null
            val extras = status.notification.extras ?: return@mapNotNull null
            if (!extras.containsKey(EXTRA_ID)) return@mapNotNull null
            val title = extras.getString(EXTRA_TITLE) ?: return@mapNotNull null
            val requested = AlertLevel.fromName(extras.getString(EXTRA_ALERT)) ?: AlertLevel.STANDARD
            PostedReminder(
                id = extras.getLong(EXTRA_ID),
                title = title,
                dueAt = extras.getLong(EXTRA_DUE),
                requested = requested,
                decision = storedDecision(extras, requested),
            )
        }
    }

    private fun notificationContext(): Context {
        return if (UserUnlock.isUnlocked(appContext)) {
            appContext
        } else {
            appContext.createDeviceProtectedStorageContext()
        }
    }

    private fun manager(context: Context): NotificationManager? {
        return context.getSystemService(NotificationManager::class.java)
    }

    private fun ensureChannels(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Reminders that keep alerting until they are marked done"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attributes)
            }
            manager.createNotificationChannel(channel)
        }
        runCatching {
            ensureSpecialChannel(manager, CRITICAL_CHANNEL_ID, "Critical reminders", dndBypass = true)
            ensureSpecialChannel(manager, PROMINENT_CHANNEL_ID, "Prominent reminders", dndBypass = true)
        }
        if (manager.getNotificationChannel(SUMMARY_CHANNEL_ID) == null) {
            val summary = NotificationChannel(
                SUMMARY_CHANNEL_ID,
                context.getString(R.string.summary_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.summary_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(summary)
        }
    }

    private fun prepare(requested: AlertLevel): AlertDecision {
        val context = notificationContext()
        val manager = manager(context)
        if (manager != null) ensureChannels(context, manager)
        val criticalOk = manager != null && channelReady(manager, CRITICAL_CHANNEL_ID)
        val fsi = AlertPermissions.canUseFullScreenIntent(appContext)
        val dnd = AlertPermissions.hasDndAccess(appContext)
        var decision = AlertPolicy.decide(requested, fsi, dnd, criticalOk)
        if (decision.effective == AlertLevel.PROMINENT &&
            (manager == null || !channelReady(manager, PROMINENT_CHANNEL_ID))
        ) {
            decision = AlertPolicy.decide(requested, canUseFullScreenIntent = false, dnd, criticalOk)
        }
        return decision
    }

    private fun channelReady(manager: NotificationManager, id: String): Boolean {
        return try {
            ensureSpecialChannel(
                manager,
                id,
                if (id == CRITICAL_CHANNEL_ID) "Critical reminders" else "Prominent reminders",
                dndBypass = true,
            )
            manager.getNotificationChannel(id) != null
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            false
        }
    }

    private fun ensureSpecialChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        dndBypass: Boolean,
    ) {
        val existing = manager.getNotificationChannel(id)
        if (existing == null) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = name
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, attributes)
                if (dndBypass) setBypassDnd(true)
            }
            manager.createNotificationChannel(channel)
            return
        }
        if (dndBypass && AlertPermissions.hasDndAccess(appContext) && !existing.canBypassDnd()) {
            existing.setBypassDnd(true)
            manager.createNotificationChannel(existing)
        }
    }

    private fun channelId(level: AlertLevel): String = when (level) {
        AlertLevel.STANDARD -> CHANNEL_ID
        AlertLevel.CRITICAL -> CRITICAL_CHANNEL_ID
        AlertLevel.PROMINENT -> PROMINENT_CHANNEL_ID
    }

    private fun contentIntent(id: Long): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("doapp://open/$id")
        }
        return PendingIntent.getActivity(
            appContext,
            ReminderIds.asInt(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fullScreenPending(id: Long, title: String, dueAt: Long): PendingIntent {
        val intent = Intent(appContext, ProminentAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("doapp://alert/$id")
            putExtra(ProminentAlertActivity.EXTRA_ID, id)
            putExtra(ProminentAlertActivity.EXTRA_TITLE, title)
            putExtra(ProminentAlertActivity.EXTRA_DUE, dueAt)
        }
        return PendingIntent.getActivity(
            appContext,
            ReminderIds.fullScreenRequestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPending(id: Long): PendingIntent {
        val intent = Intent(appContext, StopReceiver::class.java).apply {
            action = StopReceiver.ACTION
            data = Uri.parse("doapp://stop/$id")
            putExtra(StopReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ReminderIds.stopRequestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun donePending(id: Long): PendingIntent {
        val intent = Intent(appContext, DoneReceiver::class.java).apply {
            action = DoneReceiver.ACTION
            data = Uri.parse("doapp://done/$id")
            putExtra(DoneReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ReminderIds.asInt(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun snoozePending(id: Long, optionIndex: Int, option: SnoozeOption): PendingIntent {
        val intent = Intent(appContext, SnoozeReceiver::class.java).apply {
            action = SnoozeReceiver.ACTION
            data = Uri.parse("doapp://snooze/$id/$optionIndex")
            putExtra(SnoozeReceiver.EXTRA_ID, id)
            putExtra(SnoozeReceiver.EXTRA_OPTION, optionIndex)
            putExtra(SnoozeReceiver.EXTRA_KIND, option.kind.name)
            putExtra(SnoozeReceiver.EXTRA_VALUE, option.value)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ReminderIds.snoozeRequestCode(id, optionIndex),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun snoozeAllPending(option: SnoozeOption): PendingIntent {
        val intent = Intent(appContext, SnoozeReceiver::class.java).apply {
            action = SnoozeReceiver.ACTION
            data = Uri.parse("doapp://snooze/all")
            putExtra(SnoozeReceiver.EXTRA_ALL, true)
            putExtra(SnoozeReceiver.EXTRA_OPTION, 0)
            putExtra(SnoozeReceiver.EXTRA_KIND, option.kind.name)
            putExtra(SnoozeReceiver.EXTRA_VALUE, option.value)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ReminderIds.SNOOZE_ALL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun systemAction(label: String, pending: PendingIntent): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun reminderExtras(id: Long, title: String, dueAt: Long, decision: AlertDecision): Bundle {
        return Bundle().apply {
            putLong(EXTRA_ID, id)
            putString(EXTRA_TITLE, title)
            putLong(EXTRA_DUE, dueAt)
            putString(EXTRA_ALERT, decision.requested.name)
            putString(EXTRA_EFFECTIVE, decision.effective.name)
            putBoolean(EXTRA_LOOP, decision.loopUntilAttended)
            putBoolean(EXTRA_FULL_SCREEN, decision.fullScreen)
            putBoolean(EXTRA_BYPASS, decision.bypassDnd)
        }
    }

    private fun storedDecision(extras: Bundle, requested: AlertLevel): AlertDecision {
        val effective = AlertLevel.fromName(extras.getString(EXTRA_EFFECTIVE)) ?: requested
        return AlertDecision(
            requested = requested,
            effective = effective,
            fullScreen = extras.getBoolean(EXTRA_FULL_SCREEN, false),
            bypassDnd = extras.getBoolean(EXTRA_BYPASS, false),
            loopUntilAttended = extras.getBoolean(EXTRA_LOOP, false),
            reasons = emptyList(),
        )
    }

    private fun dueText(dueAt: Long): String {
        val formatted = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(dueAt))
        return appContext.getString(R.string.notification_due, formatted)
    }

    private data class PostedReminder(
        val id: Long,
        val title: String,
        val dueAt: Long,
        val requested: AlertLevel,
        val decision: AlertDecision,
    )

    private data class SnoozeAction(
        val pending: PendingIntent,
        val label: String,
        val description: String,
    )

    companion object {
        const val CHANNEL_ID = "reminders"
        const val CHANNEL_NAME = "Reminders"
        const val CRITICAL_CHANNEL_ID = "reminders_critical"
        const val PROMINENT_CHANNEL_ID = "reminders_prominent"
        const val SUMMARY_CHANNEL_ID = "reminders_summary"
        const val GROUP_KEY = "com.evansappstudio.doapp.REMINDERS"
        private const val EXTRA_ID = "doapp_id"
        private const val EXTRA_TITLE = "doapp_title"
        private const val EXTRA_DUE = "doapp_due"
        private const val EXTRA_ALERT = "doapp_alert"
        private const val EXTRA_EFFECTIVE = "doapp_effective"
        private const val EXTRA_LOOP = "doapp_loop"
        private const val EXTRA_FULL_SCREEN = "doapp_fullscreen"
        private const val EXTRA_BYPASS = "doapp_bypass"
    }
}
