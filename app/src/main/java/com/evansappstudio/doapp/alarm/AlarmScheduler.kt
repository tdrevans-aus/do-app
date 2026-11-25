package com.evansappstudio.doapp.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.evansappstudio.doapp.MainActivity

/**
 * One alarm-clock alarm per reminder. setAlarmClock is the primary path because
 * setExactAndAllowWhileIdle is limited to about once per 9 minutes in Doze.
 * If exact alarms are not allowed, fall back to setAndAllowWhileIdle and never crash.
 */
class AlarmScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun schedule(id: Long, triggerAtMillis: Long) {
        val alarmManager = alarmManager()
        val operation = operation(id, triggerAtMillis)
        val exactAllowed = AlarmPermissions.canScheduleExact(appContext)
        try {
            if (exactAllowed) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent()),
                    operation,
                )
            } else {
                DoAlarmLog.exactUnavailable(id, error = null)
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation,
                )
            }
        } catch (security: SecurityException) {
            DoAlarmLog.exactUnavailable(id, security)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    operation,
                )
            } catch (again: SecurityException) {
                DoAlarmLog.inexactFailed(id, again)
            }
        }
    }

    fun cancel(id: Long) {
        val existing = PendingIntent.getBroadcast(
            appContext,
            ReminderIds.asInt(id),
            alarmIntent(id, triggerAtMillis = null),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager().cancel(existing)
        existing.cancel()
    }

    private fun alarmManager(): AlarmManager {
        return appContext.getSystemService(AlarmManager::class.java)
            ?: error("AlarmManager missing")
    }

    private fun operation(id: Long, triggerAtMillis: Long): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            appContext,
            ReminderIds.asInt(id),
            alarmIntent(id, triggerAtMillis),
            flags,
        )
    }

    private fun alarmIntent(id: Long, triggerAtMillis: Long?): Intent {
        return Intent(appContext, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION
            data = Uri.parse("doapp://reminder/$id")
            putExtra(AlarmReceiver.EXTRA_ID, id)
            if (triggerAtMillis != null) {
                putExtra(AlarmReceiver.EXTRA_SCHEDULED, triggerAtMillis)
            }
        }
    }

    private fun showIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, 0, intent, flags)
    }
}
