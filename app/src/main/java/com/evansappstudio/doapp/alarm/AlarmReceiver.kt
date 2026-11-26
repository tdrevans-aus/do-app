package com.evansappstudio.doapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.evansappstudio.doapp.DoApp
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, Long.MIN_VALUE)
        if (id == Long.MIN_VALUE) return
        val scheduled = intent.getLongExtra(EXTRA_SCHEDULED, -1L)
        val actual = System.currentTimeMillis()
        DoAlarmLog.fire(id, scheduled, actual)
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.health.noteDelivery(id, scheduled, actual)
                app.container.scheduler.onFire(id, actual, scheduled)
                app.container.health.finishTest(id, scheduled, actual)
                app.container.health.refresh()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.evansappstudio.doapp.ALARM"
        const val EXTRA_ID = "id"
        const val EXTRA_SCHEDULED = "scheduled"
    }
}
