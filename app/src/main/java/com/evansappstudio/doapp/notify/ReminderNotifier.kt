package com.evansappstudio.doapp.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.evansappstudio.doapp.MainActivity
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.DoneReceiver
import com.evansappstudio.doapp.alarm.ReminderIds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReminderNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun show(id: Long, title: String, dueAt: Long) {
        try {
            showUnchecked(id, title, dueAt)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    fun dismiss(id: Long) {
        try {
            manager(notificationContext())?.cancel(ReminderIds.asInt(id))
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    private fun showUnchecked(id: Long, title: String, dueAt: Long) {
        val context = notificationContext()
        val manager = manager(context) ?: return
        ensureChannel(manager)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(dueText(dueAt))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentIntent(id))
            .addAction(doneAction(id))
            .build()
        notification.flags = notification.flags and Notification.FLAG_ONLY_ALERT_ONCE.inv()
        manager.notify(ReminderIds.asInt(id), notification)
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

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
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

    private fun contentIntent(id: Long): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("doapp://open/$id")
        }
        return PendingIntent.getActivity(
            appContext,
            ReminderIds.asInt(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun doneAction(id: Long): NotificationCompat.Action {
        val intent = Intent(appContext, DoneReceiver::class.java).apply {
            action = DoneReceiver.ACTION
            data = Uri.parse("doapp://done/$id")
            putExtra(DoneReceiver.EXTRA_ID, id)
        }
        val pending = PendingIntent.getBroadcast(
            appContext,
            ReminderIds.asInt(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_stat_reminder,
            appContext.getString(R.string.action_done),
            pending,
        ).build()
    }

    private fun dueText(dueAt: Long): String {
        val formatted = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(dueAt))
        return appContext.getString(R.string.notification_due, formatted)
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val CHANNEL_NAME = "Reminders"
    }
}
