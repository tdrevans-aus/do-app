package com.evansappstudio.doapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.UserUnlock
import android.text.format.DateFormat
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.DoneReceiver
import com.evansappstudio.doapp.alarm.NagSchedule
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeBase
import com.evansappstudio.doapp.alarm.QuickTimeDay
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimeUnit
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeOption
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/**
 * Debug-only hook so QA can add and finish reminders without the UI.
 * nagMinutes 0, or a missing extra, is stored as null (nag off).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ADD -> add(context, intent)
            ACTION_DONE -> done(context, intent)
            ACTION_SNOOZE -> snooze(context, intent)
            ACTION_CONFIG_SNOOZE -> configSnooze(context, intent)
            ACTION_CONFIG_QAT -> configQat(context, intent)
            ACTION_CONFIG_POSTPONE -> configPostpone(context, intent)
            ACTION_DUMP_QAT -> dumpQat(context)
            ACTION_APPLY_QAT -> applyQat(context, intent)
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

    private fun snooze(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        if (id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-snooze missing id")
            return
        }
        val optionIndex = intent.getIntExtra(EXTRA_OPTION, -1)
        if (optionIndex !in 0..2) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-snooze bad option")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        val option = app.container.snoozeConfig.get()[optionIndex]
        app.container.scope.launch {
            try {
                app.container.scheduler.snooze(id, optionIndex, option)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun configSnooze(context: Context, intent: Intent) {
        val optionIndex = intent.getIntExtra(EXTRA_OPTION, -1)
        val kind = SnoozeKind.fromName(intent.getStringExtra(EXTRA_KIND))
        val value = if (intent.hasExtra(EXTRA_VALUE)) {
            intent.getIntExtra(EXTRA_VALUE, 0)
        } else {
            Int.MIN_VALUE
        }
        val option = if (kind == null) null else SnoozeOption.orNull(kind, value)
        if (optionIndex !in 0..2 || option == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-snooze bad args")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-snooze user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                app.container.saveSnoozeOption(optionIndex, option)
                DoAlarmLog.configSnooze(optionIndex, option.kind.name, option.value)
                app.container.scheduler.rebuildShowingNotifications()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun configQat(context: Context, intent: Intent) {
        val slotIndex = intent.getIntExtra(EXTRA_SLOT, -1)
        val kind = SnoozeKind.fromName(intent.getStringExtra(EXTRA_KIND))
        val hasAmount = intent.hasExtra(EXTRA_AMOUNT)
        val amount = intent.getIntExtra(EXTRA_AMOUNT, 0)
        val unit = QuickTimeUnit.fromName(intent.getStringExtra(EXTRA_UNIT))
        val slot = when {
            kind == null || !hasAmount || slotIndex !in QuickTimes.FACTORY.indices -> null
            kind == SnoozeKind.AT_TIME -> QuickTimeSlot.orNull(kind, amount, QuickTimeUnit.MINUTE)
            unit == null -> null
            else -> QuickTimeSlot.orNull(kind, amount, unit)
        }
        if (slot == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-qat bad args")
            return
        }
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-qat user locked")
            return
        }
        app.container.settings.setQuickTimeSlot(slotIndex, slot)
    }

    private fun configPostpone(context: Context, intent: Intent) {
        val mode = PostponeMode.fromName(intent.getStringExtra(EXTRA_VALUE))
        if (mode == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-postpone bad args")
            return
        }
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-postpone user locked")
            return
        }
        app.container.settings.setPostponeMode(mode)
    }

    private fun dumpQat(context: Context) {
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm dump-qat user locked")
            return
        }
        val settings = app.container.settings.quickTimes()
        val is24Hour = DateFormat.is24HourFormat(context)
        val locale = Locale.getDefault()
        settings.slots.forEachIndexed { index, slot ->
            DoAlarmLog.qatConfig(QuickTimes.dumpLine(settings.postpone, index, slot, is24Hour, locale))
        }
    }

    private fun applyQat(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        if (id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm apply-qat missing id")
            return
        }
        val slotIndex = intent.getIntExtra(EXTRA_SLOT, -1)
        val count = if (intent.hasExtra(EXTRA_COUNT)) intent.getIntExtra(EXTRA_COUNT, 1) else 1
        val fromRaw = if (intent.hasExtra(EXTRA_FROM)) intent.getStringExtra(EXTRA_FROM) else null
        val dayRaw = if (intent.hasExtra(EXTRA_DAY)) intent.getStringExtra(EXTRA_DAY) else null
        val from = if (fromRaw == null) null else QuickTimeBase.fromName(fromRaw)
        val day = if (dayRaw == null) null else QuickTimeDay.fromName(dayRaw)
        if (
            slotIndex !in QuickTimes.FACTORY.indices ||
            count !in 1..MAX_APPLY_COUNT ||
            (fromRaw != null && from == null) ||
            (dayRaw != null && day == null)
        ) {
            Log.e(DoAlarmLog.TAG, "DoAlarm apply-qat bad args")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm apply-qat user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                val reminder = app.container.reminderDao().getById(id)
                if (reminder == null || reminder.isDone) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm apply-qat inactive id=$id")
                    return@launch
                }
                val settings = app.container.settings.quickTimes()
                val newDue = QuickTimes.applyRepeated(
                    slot = settings.slots[slotIndex],
                    draftDueAt = reminder.dueAt,
                    now = System.currentTimeMillis(),
                    zone = ZoneId.systemDefault(),
                    postpone = settings.postpone,
                    count = count,
                    base = from,
                    day = day,
                )
                app.container.scheduler.update(id, reminder.title, newDue, reminder.nagMinutes)
                DoAlarmLog.qat(id, newDue, slotIndex)
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
        const val ACTION_SNOOZE = "com.evansappstudio.doapp.debug.SNOOZE"
        const val ACTION_CONFIG_SNOOZE = "com.evansappstudio.doapp.debug.CONFIG_SNOOZE"
        const val ACTION_CONFIG_QAT = "com.evansappstudio.doapp.debug.CONFIG_QAT"
        const val ACTION_CONFIG_POSTPONE = "com.evansappstudio.doapp.debug.CONFIG_POSTPONE"
        const val ACTION_DUMP_QAT = "com.evansappstudio.doapp.debug.DUMP_QAT"
        const val ACTION_APPLY_QAT = "com.evansappstudio.doapp.debug.APPLY_QAT"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IN_SECONDS = "inSeconds"
        const val EXTRA_NAG_MINUTES = "nagMinutes"
        const val EXTRA_OPTION = "option"
        const val EXTRA_KIND = "kind"
        const val EXTRA_VALUE = "value"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_UNIT = "unit"
        const val EXTRA_COUNT = "count"
        const val EXTRA_FROM = "from"
        const val EXTRA_DAY = "day"
        private const val MAX_APPLY_COUNT = 1_000
    }
}
