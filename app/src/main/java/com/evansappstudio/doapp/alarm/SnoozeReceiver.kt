package com.evansappstudio.doapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evansappstudio.doapp.DoApp
import kotlinx.coroutines.launch

/**
 * One-tap snooze from the notification, including the lock screen and direct boot.
 * Does not start an activity.
 */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val optionIndex = intent.getIntExtra(EXTRA_OPTION, -1)
        val option = optionFrom(intent)
        if (optionIndex !in 0..2 || option == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm snooze bad option")
            return
        }
        val all = intent.getBooleanExtra(EXTRA_ALL, false)
        val id = if (all) null else DoneReceiver.longExtra(intent)
        if (!all && id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm snooze missing id")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                if (all) {
                    app.container.commands.snoozeShowing(Origin(Actor.NOTIFICATION), optionIndex, option)
                } else {
                    app.container.commands.snooze(Origin(Actor.NOTIFICATION), id!!, optionIndex, option)
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id ?: -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.evansappstudio.doapp.SNOOZE"
        const val EXTRA_ID = "id"
        const val EXTRA_OPTION = "option"
        const val EXTRA_KIND = "kind"
        const val EXTRA_VALUE = "value"
        const val EXTRA_ALL = "all"

        fun optionFrom(intent: Intent): SnoozeOption? {
            if (!intent.hasExtra(EXTRA_VALUE)) return null
            val kind = SnoozeKind.fromName(intent.getStringExtra(EXTRA_KIND)) ?: return null
            return SnoozeOption.orNull(kind, intent.getIntExtra(EXTRA_VALUE, 0))
        }
    }
}
