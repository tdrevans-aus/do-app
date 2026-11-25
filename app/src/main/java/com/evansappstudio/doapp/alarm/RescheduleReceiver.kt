package com.evansappstudio.doapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evansappstudio.doapp.DoApp
import kotlinx.coroutines.launch

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        val zoneChanged = intent.action == Intent.ACTION_TIMEZONE_CHANGED
        app.container.scope.launch {
            try {
                app.container.scheduler.onExternalClock(zoneChanged)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }
}
