package com.evansappstudio.doapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.DoneReceiver
import com.evansappstudio.doapp.alarm.NagSchedule
import kotlinx.coroutines.launch

/**
 * Debug-only hook so QA can add and finish reminders without the UI.
 * nagMinutes 0, or a missing extra, is stored as null (nag off).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ADD -> add(context, intent)
            ACTION_DONE -> done(context, intent)
        }
    }

    private fun add(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-add missing title")
            return
        }
        val inSeconds = intent.getIntExtra(EXTRA_IN_SECONDS, 0)
        val nagRaw = if (intent.hasExtra(EXTRA_NAG_MINUTES)) {
            intent.getIntExtra(EXTRA_NAG_MINUTES, 0)
        } else {
            0
        }
        val nagMinutes = NagSchedule.normalize(nagRaw)
        val dueAt = System.currentTimeMillis() + inSeconds.toLong() * 1000L
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-add user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                val id = app.container.scheduler.add(title, dueAt, nagMinutes)
                DoAlarmLog.debugAdd(id, dueAt, nagMinutes)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun done(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent) ?: return
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.scheduler.markDone(id)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ADD = "com.evansappstudio.doapp.debug.ADD"
        const val ACTION_DONE = "com.evansappstudio.doapp.debug.DONE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IN_SECONDS = "inSeconds"
        const val EXTRA_NAG_MINUTES = "nagMinutes"
    }
}
