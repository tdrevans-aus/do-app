package com.evansappstudio.doapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.UserUnlock
import kotlinx.coroutines.launch

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        val fromMirror = intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            !UserUnlock.isUnlocked(app)
        app.container.scope.launch {
            try {
                if (fromMirror) {
                    app.container.scheduler.rescheduleFromMirror()
                } else {
                    app.container.scheduler.rescheduleAll()
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }
}
