package com.evansappstudio.doapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evansappstudio.doapp.DoApp
import kotlinx.coroutines.launch

/** Marks a reminder done from the notification, including the lock screen. */
class DoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = longExtra(intent) ?: return
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.commands.markDone(Origin(Actor.NOTIFICATION), id)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.evansappstudio.doapp.DONE"
        const val EXTRA_ID = "id"

        fun longExtra(intent: Intent): Long? {
            val extras = intent.extras ?: return null
            if (!extras.containsKey(EXTRA_ID)) return null
            val asLong = extras.getLong(EXTRA_ID, Long.MIN_VALUE)
            if (asLong != Long.MIN_VALUE) return asLong
            val asInt = extras.getInt(EXTRA_ID, Int.MIN_VALUE)
            return if (asInt != Int.MIN_VALUE) asInt.toLong() else asLong
        }
    }
}
