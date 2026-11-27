package com.evansappstudio.doapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evansappstudio.doapp.DoApp
import kotlinx.coroutines.launch

/** Silences a Prominent alert without marking the reminder done. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent) ?: return
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.commands.stopAlert(Origin(Actor.NOTIFICATION), id)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.evansappstudio.doapp.STOP"
        const val EXTRA_ID = DoneReceiver.EXTRA_ID
    }
}
