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
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.DoneReceiver
import com.evansappstudio.doapp.alarm.ReminderIds
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

    fun show(id: Long, title: String, dueAt: Long) {
        try {
            val others = readPosted().filter { it.id != id }
            val grouped = others.isNotEmpty()
            post(id, title, dueAt, grouped = grouped, alert = true)
            if (grouped) {
                for (other in others) {
                    post(other.id, other.title, other.dueAt, grouped = true, alert = false)
                }
                postSummary(others.size + 1)
            } else {
                cancelSummary()
            }
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    fun dismiss(id: Long) {
        try {
            manager(notificationContext())?.cancel(ReminderIds.asInt(id))
            val remaining = readPosted().filter { it.id != id }
            when {
                remaining.size >= 2 -> postSummary(remaining.size)
                remaining.size == 1 -> {
                    val only = remaining.single()
                    post(only.id, only.title, only.dueAt, grouped = false, alert = false)
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
            val grouped = posted.size >= 2
            for (item in posted) {
                post(item.id, item.title, item.dueAt, grouped = grouped, alert = false)
            }
            if (grouped) postSummary(posted.size) else cancelSummary()
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
        }
    }

    fun refreshIfShowing(id: Long, title: String, dueAt: Long) {
        try {
            val posted = readPosted()
            if (posted.none { it.id == id }) return
            val grouped = posted.size >= 2
            post(id, title, dueAt, grouped = grouped, alert = false)
            if (grouped) postSummary(posted.size)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    private fun post(id: Long, title: String, dueAt: Long, grouped: Boolean, alert: Boolean) {
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
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(dueText(dueAt))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(false)
            .setContentIntent(contentIntent(id))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(expandedView(context, title, dueAt, done, snoozes))
            .addExtras(reminderExtras(id, title, dueAt))
        if (alert) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        } else {
            builder.setDefaults(0)
            builder.setSound(null)
        }
        if (grouped) {
            builder.setGroup(GROUP_KEY)
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        }
        // All four actions are on the notification so dumpsys lists every title.
        // The standard row shows at most three: Mark Done and the first two snoozes.
        // The expanded custom layout shows all four.
        builder.addAction(systemAction(context.getString(R.string.action_done), done))
        for (snooze in snoozes) {
            builder.addAction(systemAction(snooze.label, snooze.pending))
        }
        val notification = builder.build()
        if (alert) {
            notification.flags = notification.flags and Notification.FLAG_ONLY_ALERT_ONCE.inv()
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
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_reminder_expanded)
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
            PostedReminder(
                id = extras.getLong(EXTRA_ID),
                title = title,
                dueAt = extras.getLong(EXTRA_DUE),
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

    private fun reminderExtras(id: Long, title: String, dueAt: Long): Bundle {
        return Bundle().apply {
            putLong(EXTRA_ID, id)
            putString(EXTRA_TITLE, title)
            putLong(EXTRA_DUE, dueAt)
        }
    }

    private fun dueText(dueAt: Long): String {
        val formatted = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(dueAt))
        return appContext.getString(R.string.notification_due, formatted)
    }

    private data class PostedReminder(val id: Long, val title: String, val dueAt: Long)

    private data class SnoozeAction(
        val pending: PendingIntent,
        val label: String,
        val description: String,
    )

    companion object {
        const val CHANNEL_ID = "reminders"
        const val CHANNEL_NAME = "Reminders"
        const val SUMMARY_CHANNEL_ID = "reminders_summary"
        const val GROUP_KEY = "com.evansappstudio.doapp.REMINDERS"
        private const val EXTRA_ID = "doapp_id"
        private const val EXTRA_TITLE = "doapp_title"
        private const val EXTRA_DUE = "doapp_due"
    }
}
