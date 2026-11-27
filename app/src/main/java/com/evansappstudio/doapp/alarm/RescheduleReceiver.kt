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
        val action = intent.action
        val boot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_BOOT_COMPLETED
        if (boot) app.container.health.recordBoot(action)
        val zoneChanged = action == Intent.ACTION_TIMEZONE_CHANGED
        app.container.scope.launch {
            try {
                app.container.scheduler.onExternalClock(zoneChanged)
                if (boot) app.container.health.refresh()
                // Spec 12: boot and clock changes move what counts as overdue.
                app.container.widgets.request()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }
}
