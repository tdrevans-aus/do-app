package com.evansappstudio.doapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.backup.BackupMode
import com.evansappstudio.doapp.billing.FakeSubscriptionBilling
import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.billing.SubscriptionState
import android.text.format.DateFormat
import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.EventLog
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.alarm.RestoreResult
import com.evansappstudio.doapp.alarm.SoftDelete
import com.evansappstudio.doapp.alarm.DndState
import com.evansappstudio.doapp.alarm.QuietConfig
import com.evansappstudio.doapp.alarm.QuietHours
import com.evansappstudio.doapp.alarm.AlertPermissions
import com.evansappstudio.doapp.alarm.AlertPolicy
import com.evansappstudio.doapp.alarm.DateParser
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.alert
import com.evansappstudio.doapp.alarm.DoneReceiver
import com.evansappstudio.doapp.alarm.NagSchedule
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeBase
import com.evansappstudio.doapp.alarm.QuickTimeDay
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimeUnit
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.RepeatMath
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekDay
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
            ACTION_SET_REPEAT -> setRepeat(context, intent)
            ACTION_DUMP_REPEAT -> dumpRepeat(context, intent)
            ACTION_SHIFT_DUE -> shiftDue(context, intent)
            ACTION_CONFIG_REPEAT_PROMPT -> configRepeatPrompt(context, intent)
            ACTION_PARSE -> parse(context, intent)
            ACTION_SET_ALERT_LEVEL -> setAlertLevel(context, intent)
            ACTION_CONFIG_PARSE -> configParse(context, intent)
            ACTION_CONFIG_ALERT_DEFAULT -> configAlertDefault(context, intent)
            ACTION_DUMP_ALERT -> dumpAlert(context, intent)
            ACTION_DUMP_HEALTH -> dumpHealth(context)
            ACTION_TEST_HEALTH -> testHealth(context)
            ACTION_NOTE_LATE -> noteLate(context, intent)
            ACTION_FAKE_PURCHASE -> fakePurchase(context, intent)
            ACTION_DUMP_UNLOCK -> dumpUnlock(context)
            ACTION_EXPORT -> exportBackup(context, intent)
            ACTION_IMPORT -> importBackup(context, intent)
            ACTION_DUMP_BACKUP -> dumpBackup(context)
            ACTION_CONFIG_BACKUP_FOLDER -> configBackupFolder(context, intent)
            ACTION_AUTO_BACKUP -> autoBackup(context)
            ACTION_SET_TITLE -> setTitle(context, intent)
            ACTION_SET_QUIET_HOURS -> setQuietHours(context, intent)
            ACTION_DUMP_QUIET -> dumpQuiet(context, intent)
            ACTION_DUMP_META -> dumpMeta(context, intent)
            ACTION_DUMP_EVENTS -> dumpEvents(context, intent)
            ACTION_DELETE -> delete(context, intent)
            ACTION_RESTORE -> restore(context, intent)
            ACTION_PURGE_NOW -> purgeNow(context, intent)
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
        val now = System.currentTimeMillis()
        var dueAt = now + inSeconds.toLong() * 1000L
        var storedTitle = title
        val parseRequested = boolExtra(intent, EXTRA_PARSE)
        var parsed: DateParser.Match? = null
        if (parseRequested) {
            parsed = DateParser.parse(storedTitle, now, dueAt, java.time.ZoneId.systemDefault())
            if (parsed != null) {
                if (parsed.title.isBlank()) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm debug-add empty title after parse")
                    return
                }
                storedTitle = parsed.title
                dueAt = parsed.dueAt
            }
        }
        val explicitAlert = explicitAlert(intent)
        if (intent.hasExtra(EXTRA_ALERT) && explicitAlert == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-add bad alert")
            return
        }
        val source = if (intent.hasExtra(EXTRA_SOURCE)) {
            ReminderSource.fromName(intent.getStringExtra(EXTRA_SOURCE))
        } else {
            ReminderSource.USER
        }
        // IMPORT is set only by backup import, never by a create.
        if (source == null || source == ReminderSource.IMPORT) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-add bad source")
            return
        }
        val categoryRaw = intent.getStringExtra(EXTRA_CATEGORY)
        val category = ReminderCategory.normalize(categoryRaw)
        if (!categoryRaw.isNullOrBlank() && category == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-add bad category")
            return
        }
        val origin = Origin(Actor.DEBUG, source, intent.getStringExtra(EXTRA_SOURCE_ID))
        val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
        val templateVersion = if (intent.hasExtra(EXTRA_TEMPLATE_VERSION)) {
            intent.getIntExtra(EXTRA_TEMPLATE_VERSION, 0)
        } else {
            null
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm debug-add user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                val rule = if (parsed != null) parsed.rule else repeatFromIntent(intent)
                if (parsed == null && rule == null && !repeatUnitIsOff(intent)) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm debug-add bad repeat")
                    return@launch
                }
                val alert = explicitAlert ?: app.container.settings.defaultAlertLevel()
                val id = app.container.commands.add(
                    origin = origin,
                    title = storedTitle,
                    dueAt = dueAt,
                    nagMinutes = nagMinutes,
                    rule = rule,
                    alertLevel = alert,
                    category = category,
                    templateId = templateId,
                    templateVersion = templateVersion,
                )
                if (id == null) return@launch
                DoAlarmLog.debugAdd(id, dueAt, nagMinutes)
                parsed?.let { match ->
                    DoAlarmLog.parseMatch(
                        match.matched,
                        match.title,
                        match.dueAt,
                        DateParser.debugRule(match.rule),
                        match.start,
                        match.end,
                    )
                }
                DoAlarmLog.alert(id, alert.name)
                if (rule != null) {
                    val stored = app.container.reminderDao().getById(id)
                    DoAlarmLog.repeat(
                        id = id,
                        rule = rule.token(),
                        from = rule.from.name,
                        dueAt = stored?.dueAt ?: dueAt,
                        patternDue = stored?.patternDueAt,
                        next = null,
                    )
                }
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
                app.container.commands.markDone(DEBUG, id)
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
                app.container.commands.snooze(DEBUG, id, optionIndex, option)
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
                if (reminder == null || reminder.isDone || reminder.deletedAt != null) {
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
                val rule = reminder.repeatRule()
                val shift = if (
                    newDue != reminder.dueAt && rule?.from == RepeatFrom.DUE_DATE
                ) {
                    DueShiftMode.THIS
                } else {
                    null
                }
                app.container.commands.update(
                    DEBUG,
                    id,
                    reminder.title,
                    newDue,
                    reminder.nagMinutes,
                    rule,
                    shift,
                    reminder.alert(),
                )
                DoAlarmLog.qat(id, newDue, slotIndex)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun setRepeat(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        if (id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm set-repeat missing id")
            return
        }
        val rule = repeatFromIntent(intent)
        if (rule == null && !repeatUnitIsOff(intent)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm set-repeat bad args")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm set-repeat user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                app.container.commands.setRepeat(DEBUG, id, rule)
                val stored = app.container.reminderDao().getById(id)
                if (stored == null) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm set-repeat missing id=$id")
                } else {
                    DoAlarmLog.repeat(
                        id = id,
                        rule = stored.repeatRule()?.token() ?: "OFF",
                        from = stored.repeatRule()?.from?.name ?: "none",
                        dueAt = stored.dueAt,
                        patternDue = stored.patternDueAt,
                        next = null,
                    )
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun dumpRepeat(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        if (id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm dump-repeat missing id")
            return
        }
        val count = if (intent.hasExtra(EXTRA_COUNT)) intent.getIntExtra(EXTRA_COUNT, 6) else 6
        if (count !in 1..MAX_DUMP_COUNT) {
            Log.e(DoAlarmLog.TAG, "DoAlarm dump-repeat bad count")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm dump-repeat user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                val reminder = app.container.reminderDao().getById(id)
                if (reminder == null) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm dump-repeat missing id=$id")
                    return@launch
                }
                val rule = reminder.repeatRule()
                if (rule == null) {
                    DoAlarmLog.repeat(id, "OFF", "none", reminder.dueAt, null, null)
                    return@launch
                }
                if (rule.from == RepeatFrom.COMPLETION) {
                    DoAlarmLog.repeat(
                        id = id,
                        rule = rule.token(),
                        from = rule.from.name,
                        dueAt = reminder.dueAt,
                        patternDue = null,
                        next = "completion",
                    )
                    return@launch
                }
                DoAlarmLog.repeat(
                    id = id,
                    rule = rule.token(),
                    from = rule.from.name,
                    dueAt = reminder.dueAt,
                    patternDue = reminder.patternDueAt,
                    next = null,
                )
                val sequence = RepeatMath.sequence(
                    dueAt = reminder.dueAt,
                    patternDueAt = reminder.patternDueAt,
                    rule = rule,
                    zone = ZoneId.systemDefault(),
                    count = count,
                    weekStart = app.container.settings.weekStart(),
                )
                sequence.forEachIndexed { index, at -> DoAlarmLog.repeatNext(id, index, at) }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun shiftDue(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        val mode = DueShiftMode.fromName(intent.getStringExtra(EXTRA_MODE))
        if (id == null || mode == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm shift-due bad args")
            return
        }
        val hasDue = intent.hasExtra(EXTRA_DUE_AT)
        val hasIn = intent.hasExtra(EXTRA_IN_SECONDS)
        if (!hasDue && !hasIn) {
            Log.e(DoAlarmLog.TAG, "DoAlarm shift-due bad args")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm shift-due user locked")
            pending.finish()
            return
        }
        val newDue = if (hasDue) {
            intent.getLongExtra(EXTRA_DUE_AT, 0L)
        } else {
            System.currentTimeMillis() + intent.getIntExtra(EXTRA_IN_SECONDS, 0).toLong() * 1000L
        }
        app.container.scope.launch {
            try {
                val stored = app.container.commands.shiftDue(DEBUG, id, newDue, mode)
                if (stored == null) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm shift-due missing id=$id")
                } else {
                    DoAlarmLog.shiftDue(id, stored.dueAt, mode.name, stored.patternDueAt)
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun configRepeatPrompt(context: Context, intent: Intent) {
        val mode = RepeatPromptMode.fromName(intent.getStringExtra(EXTRA_VALUE))
        if (mode == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-repeat-prompt bad args")
            return
        }
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-repeat-prompt user locked")
            return
        }
        app.container.settings.setRepeatPromptMode(mode)
        DoAlarmLog.configRepeatPrompt(mode.name)
    }

    private fun parse(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)
        if (text == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm parse missing text")
            return
        }
        val now = if (intent.hasExtra(EXTRA_NOW)) intent.getLongExtra(EXTRA_NOW, 0L) else System.currentTimeMillis()
        val draft = if (intent.hasExtra(EXTRA_DRAFT_DUE)) {
            intent.getLongExtra(EXTRA_DRAFT_DUE, now)
        } else {
            now + 5L * 60L * 1000L
        }
        val match = DateParser.parse(text, now, draft, java.time.ZoneId.systemDefault())
        if (match == null) {
            DoAlarmLog.parseNone(text)
        } else {
            DoAlarmLog.parseMatch(
                match.matched,
                match.title,
                match.dueAt,
                DateParser.debugRule(match.rule),
                match.start,
                match.end,
            )
        }
    }

    private fun setAlertLevel(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        val level = AlertLevel.fromName(intent.getStringExtra(EXTRA_LEVEL))
        if (id == null || level == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm alert bad args")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm alert user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                val existing = app.container.reminderDao().getById(id)
                if (existing == null || existing.isDone || existing.deletedAt != null) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm alert missing id=$id")
                } else {
                    app.container.commands.setAlertLevel(DEBUG, id, level)
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun configParse(context: Context, intent: Intent) {
        val value = intent.getStringExtra(EXTRA_VALUE)?.trim()?.uppercase()
        if (value != "ON" && value != "OFF") {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-parse bad args")
            return
        }
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-parse user locked")
            return
        }
        app.container.settings.setParseDatesEnabled(value == "ON")
        DoAlarmLog.configParse(value)
    }

    private fun configAlertDefault(context: Context, intent: Intent) {
        val level = AlertLevel.fromName(intent.getStringExtra(EXTRA_LEVEL))
        if (level == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-alert-default bad args")
            return
        }
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm config-alert-default user locked")
            return
        }
        app.container.settings.setDefaultAlertLevel(level)
        DoAlarmLog.configAlertDefault(level.name)
    }

    private fun dumpAlert(context: Context, intent: Intent) {
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm alert user locked")
            return
        }
        val pending = goAsync()
        app.container.scope.launch {
            try {
                val defaultLevel = app.container.settings.defaultAlertLevel()
                val fullScreen = AlertPermissions.canUseFullScreenIntent(app)
                val dnd = AlertPermissions.hasDndAccess(app)
                val parse = if (app.container.settings.parseDatesEnabled()) "ON" else "OFF"
                if (!intent.hasExtra(DoneReceiver.EXTRA_ID)) {
                    DoAlarmLog.alertDump(null, null, defaultLevel.name, fullScreen, dnd, null, parse)
                    return@launch
                }
                val id = DoneReceiver.longExtra(intent)
                val reminder = if (id == null) null else app.container.reminderDao().getById(id)
                if (id == null || reminder == null) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm alert missing id=${id ?: -1}")
                    return@launch
                }
                val decision = AlertPolicy.decide(reminder.alert(), fullScreen, dnd)
                DoAlarmLog.alertDump(
                    id,
                    reminder.alert().name,
                    defaultLevel.name,
                    fullScreen,
                    dnd,
                    decision.effective.name,
                    parse,
                )
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun dumpHealth(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.health.dump()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun testHealth(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm health-test user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                app.container.health.armTest()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun noteLate(context: Context, intent: Intent) {
        val lagMs = if (intent.hasExtra(EXTRA_LAG_MS)) intent.getLongExtra(EXTRA_LAG_MS, 0L) else 0L
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.health.noteSyntheticLate(lagMs)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * FAKE_PURCHASE --es state free|active|pending|expired [--ez intro true|false].
     * With only intro, the state is kept. With neither, the state is active (Spec 7's default).
     */
    private fun fakePurchase(context: Context, intent: Intent) {
        val hasIntro = intent.hasExtra(EXTRA_INTRO)
        val intro = intent.getBooleanExtra(EXTRA_INTRO, true)
        val requested = intent.getStringExtra(EXTRA_STATE)?.trim()?.lowercase().orEmpty()
        val state = requested.ifBlank { if (hasIntro) "" else FakeSubscriptionBilling.STATE_ACTIVE }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                val billing = app.container.billing
                if (billing !is FakeSubscriptionBilling) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm unlock fake unavailable")
                    return@launch
                }
                if (state.isNotEmpty() && FakeSubscriptionBilling.parseState(state) == null) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm unlock bad state")
                    return@launch
                }
                if (hasIntro) billing.setIntro(intro)
                if (state.isNotEmpty()) billing.applyState(state)
                val shown = state.ifEmpty { billing.ui.value.state.name.lowercase() }
                val active = if (UserUnlock.isUnlocked(app)) app.container.reminderDao().activeCount() else 0
                DoAlarmLog.unlockFake(shown, active, FreeTier.capLabel(), billing.intro)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun dumpUnlock(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                val record = app.container.entitlement.read()
                val billing = app.container.billing
                val active = if (UserUnlock.isUnlocked(app)) app.container.reminderDao().activeCount() else 0
                val state = if (record.active) "unlocked" else "locked"
                val price = billing.price()
                DoAlarmLog.unlockDump(
                    state = state,
                    source = billing.dumpSource(),
                    pending = record.pending,
                    price = price?.renewal ?: "none",
                    active = active,
                    cap = FreeTier.capLabel(),
                    subscription = SubscriptionState.of(record).name,
                    everSubscribed = record.everSubscribed,
                    intro = price?.intro ?: "none",
                    renewal = price?.renewal ?: "none",
                    product = FreeTier.PRODUCT_ID,
                )
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun exportBackup(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        if (path.isEmpty()) {
            Log.e(DoAlarmLog.TAG, "DoAlarm backup export missing path")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.backup.exportToFile(path)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun importBackup(context: Context, intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val mode = BackupMode.fromName(intent.getStringExtra(EXTRA_MODE))
        val uuids = intent.getStringExtra(EXTRA_UUIDS)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        if (path.isEmpty()) {
            DoAlarmLog.backupImportError("corrupt")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.backup.importPath(path, mode, if (intent.hasExtra(EXTRA_UUIDS)) uuids else null)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
                DoAlarmLog.backupImportError("corrupt")
            } finally {
                pending.finish()
            }
        }
    }

    private fun dumpBackup(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.health.refresh()
                val store = app.container.backupStore
                val health = if (store.health().ok) "OK" else "WARN"
                DoAlarmLog.backupDump(store.folderSet(), store.lastSuccessAt(), health)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun configBackupFolder(context: Context, intent: Intent) {
        val path = if (intent.hasExtra(EXTRA_PATH)) intent.getStringExtra(EXTRA_PATH) else null
        val clearSuccess = boolExtra(intent, EXTRA_CLEAR_SUCCESS)
        if (path == null && !clearSuccess) {
            Log.e(DoAlarmLog.TAG, "DoAlarm backup folder missing path")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.backup.configureFolder(path, clearSuccess)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun autoBackup(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                app.container.backup.maybeAutoBackup(announceSkip = true)
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun setQuietHours(context: Context, intent: Intent) {
        DndDebugOverride.install()
        val app = context.applicationContext as DoApp
        val current = app.container.quiet.read()
        val enabled = if (intent.hasExtra(EXTRA_ENABLED)) boolExtra(intent, EXTRA_ENABLED) else current.enabled
        val follow = if (intent.hasExtra(EXTRA_FOLLOW_DND)) boolExtra(intent, EXTRA_FOLLOW_DND) else current.followDnd
        val critical = if (intent.hasExtra(EXTRA_CRITICAL_PAUSES)) {
            boolExtra(intent, EXTRA_CRITICAL_PAUSES)
        } else {
            current.criticalPauses
        }
        val start = if (intent.hasExtra(EXTRA_START)) {
            QuietHours.parseMinute(intent.getStringExtra(EXTRA_START).orEmpty())
        } else {
            current.startMinute
        }
        val end = if (intent.hasExtra(EXTRA_END)) {
            QuietHours.parseMinute(intent.getStringExtra(EXTRA_END).orEmpty())
        } else {
            current.endMinute
        }
        val simRaw = if (intent.hasExtra(EXTRA_SIM_DND)) intent.getStringExtra(EXTRA_SIM_DND) else null
        val simOk = simRaw == null || simRaw.trim().lowercase() in setOf(
            DndDebugOverride.ON,
            DndDebugOverride.OFF,
            DndDebugOverride.REAL,
        )
        if (start == null || end == null || start == end || !simOk) {
            DoAlarmLog.quietBadArgs()
            return
        }
        val next = QuietConfig(
            enabled = enabled,
            startMinute = start,
            endMinute = end,
            followDnd = follow,
            criticalPauses = critical,
        )
        if (!app.container.quiet.write(next)) {
            DoAlarmLog.quietBadArgs()
            return
        }
        if (simRaw != null) DndDebugOverride.set(simRaw)
        DoAlarmLog.quietSet(
            enabled = next.enabled,
            start = QuietHours.formatMinute(next.startMinute),
            end = QuietHours.formatMinute(next.endMinute),
            followDnd = next.followDnd,
            criticalPauses = next.criticalPauses,
            simDnd = DndDebugOverride.mode,
        )
        if (simRaw != null) {
            val filter = DndState.filter(app)
            DoAlarmLog.dndFilter(filter, DndState.isActive(filter))
        }
        val pending = goAsync()
        app.container.scope.launch {
            try {
                app.container.scheduler.reconcileQuietHours()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun dumpQuiet(context: Context, intent: Intent) {
        DndDebugOverride.install()
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                val config = app.container.quiet.read()
                val filter = DndState.filter(app)
                val active = DndState.isActive(filter)
                val zone = java.time.ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val inside = QuietHours.inWindow(now, config, zone)
                val endAt = QuietHours.windowEnd(now, config, zone)
                DoAlarmLog.quietDump(
                    "enabled=${config.enabled} start=${QuietHours.formatMinute(config.startMinute)} " +
                        "end=${QuietHours.formatMinute(config.endMinute)} followDnd=${config.followDnd} " +
                        "criticalPauses=${config.criticalPauses} filter=$filter filterSource=${DndDebugOverride.source()} " +
                        "dndActive=$active zone=$zone now=$now inWindow=$inside windowEnd=${endAt?.toString() ?: "none"}",
                )
                if (intent.hasExtra(DoneReceiver.EXTRA_ID) || intent.hasExtra("id")) {
                    val id = DoneReceiver.longExtra(intent)
                    if (id != null) {
                        val entry = app.container.mirror.get(id)
                        if (entry != null) {
                            val level = com.evansappstudio.doapp.alarm.AlertLevel.fromName(entry.alertLevel)
                                ?: com.evansappstudio.doapp.alarm.AlertLevel.STANDARD
                            DoAlarmLog.quietId(
                                "id=$id level=${level.name} exempt=${QuietHours.exempt(level, config)} " +
                                    "dueAt=${entry.dueAt} nextFireAt=${entry.nextFireAt?.toString() ?: "null"} " +
                                    "pendingNag=${entry.pendingNag()} dndHeld=${entry.dndHeld}",
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun setTitle(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        val title = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty()
        if (id == null || title.isEmpty()) {
            Log.e(DoAlarmLog.TAG, "DoAlarm set-title bad args")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        app.container.scope.launch {
            try {
                val stored = app.container.commands.setTitle(DEBUG, id, title)
                if (stored == null) {
                    Log.e(DoAlarmLog.TAG, "DoAlarm set-title missing id=$id")
                } else {
                    Log.i(DoAlarmLog.TAG, "DoAlarm set-title id=${stored.id} uuid=${stored.uuid} title=${stored.title}")
                }
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id, error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun dumpMeta(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        if (id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm meta missing id")
            return
        }
        unlockedLaunch(context, "meta", id) { app ->
            val row = app.container.reminderDao().getById(id)
            if (row == null) {
                Log.e(DoAlarmLog.TAG, "DoAlarm meta missing id=$id")
            } else {
                DoAlarmLog.meta(row)
            }
        }
    }

    private fun dumpEvents(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        val limit = if (intent.hasExtra(EXTRA_LIMIT)) intent.getIntExtra(EXTRA_LIMIT, DEFAULT_EVENT_LIMIT) else DEFAULT_EVENT_LIMIT
        if (id == null || limit !in 1..EventLog.MAX_PER_REMINDER) {
            Log.e(DoAlarmLog.TAG, "DoAlarm events bad args")
            return
        }
        unlockedLaunch(context, "events", id) { app ->
            val row = app.container.reminderDao().getById(id)
            if (row == null) {
                Log.e(DoAlarmLog.TAG, "DoAlarm events missing id=$id")
                return@unlockedLaunch
            }
            val events = app.container.reminderEventDao().forReminder(row.uuid, limit)
            events.forEach { DoAlarmLog.event(id, it) }
            DoAlarmLog.eventsEnd(events.size)
        }
    }

    private fun delete(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        if (id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm delete missing id")
            return
        }
        unlockedLaunch(context, "delete", id) { app ->
            app.container.commands.delete(DEBUG, id)
            val deletedAt = app.container.reminderDao().getById(id)?.deletedAt
            if (deletedAt == null) {
                Log.e(DoAlarmLog.TAG, "DoAlarm delete missing id=$id")
            } else {
                DoAlarmLog.deleted(id, deletedAt)
            }
        }
    }

    private fun restore(context: Context, intent: Intent) {
        val id = DoneReceiver.longExtra(intent)
        if (id == null) {
            Log.e(DoAlarmLog.TAG, "DoAlarm restore missing id")
            return
        }
        unlockedLaunch(context, "restore", id) { app ->
            when (app.container.commands.restore(DEBUG, id)) {
                RestoreResult.RESTORED -> DoAlarmLog.restored(id)
                RestoreResult.BLOCKED_BY_CAP -> Log.e(DoAlarmLog.TAG, "DoAlarm restore blocked id=$id cap=${FreeTier.capLabel()}")
                RestoreResult.REFUSED -> Log.e(DoAlarmLog.TAG, "DoAlarm restore refused id=$id")
            }
        }
    }

    private fun purgeNow(context: Context, intent: Intent) {
        val days = if (intent.hasExtra(EXTRA_DAYS)) {
            intent.getIntExtra(EXTRA_DAYS, SoftDelete.PURGE_AFTER_DAYS)
        } else {
            SoftDelete.PURGE_AFTER_DAYS
        }
        if (days < 0) {
            Log.e(DoAlarmLog.TAG, "DoAlarm purge bad days")
            return
        }
        unlockedLaunch(context, "purge", -1) { app ->
            DoAlarmLog.purge(app.container.scheduler.purgeDeleted(days))
        }
    }

    /** Runs [block] off the main thread once the user is unlocked, as the other Room hooks do. */
    private fun unlockedLaunch(context: Context, label: String, id: Long, block: suspend (DoApp) -> Unit) {
        val pending = goAsync()
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            Log.e(DoAlarmLog.TAG, "DoAlarm $label user locked")
            pending.finish()
            return
        }
        app.container.scope.launch {
            try {
                block(app)
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
        const val ACTION_SET_REPEAT = "com.evansappstudio.doapp.debug.SET_REPEAT"
        const val ACTION_DUMP_REPEAT = "com.evansappstudio.doapp.debug.DUMP_REPEAT"
        const val ACTION_SHIFT_DUE = "com.evansappstudio.doapp.debug.SHIFT_DUE"
        const val ACTION_CONFIG_REPEAT_PROMPT = "com.evansappstudio.doapp.debug.CONFIG_REPEAT_PROMPT"
        const val ACTION_PARSE = "com.evansappstudio.doapp.debug.PARSE"
        const val ACTION_SET_ALERT_LEVEL = "com.evansappstudio.doapp.debug.SET_ALERT_LEVEL"
        const val ACTION_CONFIG_PARSE = "com.evansappstudio.doapp.debug.CONFIG_PARSE"
        const val ACTION_CONFIG_ALERT_DEFAULT = "com.evansappstudio.doapp.debug.CONFIG_ALERT_DEFAULT"
        const val ACTION_DUMP_ALERT = "com.evansappstudio.doapp.debug.DUMP_ALERT"
        const val ACTION_DUMP_HEALTH = "com.evansappstudio.doapp.debug.DUMP_HEALTH"
        const val ACTION_TEST_HEALTH = "com.evansappstudio.doapp.debug.TEST_HEALTH"
        const val ACTION_NOTE_LATE = "com.evansappstudio.doapp.debug.NOTE_LATE"
        const val ACTION_FAKE_PURCHASE = "com.evansappstudio.doapp.debug.FAKE_PURCHASE"
        const val ACTION_DUMP_UNLOCK = "com.evansappstudio.doapp.debug.DUMP_UNLOCK"
        const val ACTION_EXPORT = "com.evansappstudio.doapp.debug.EXPORT"
        const val ACTION_IMPORT = "com.evansappstudio.doapp.debug.IMPORT"
        const val ACTION_DUMP_BACKUP = "com.evansappstudio.doapp.debug.DUMP_BACKUP"
        const val ACTION_CONFIG_BACKUP_FOLDER = "com.evansappstudio.doapp.debug.CONFIG_BACKUP_FOLDER"
        const val ACTION_AUTO_BACKUP = "com.evansappstudio.doapp.debug.AUTO_BACKUP"
        const val ACTION_SET_TITLE = "com.evansappstudio.doapp.debug.SET_TITLE"
        const val ACTION_SET_QUIET_HOURS = "com.evansappstudio.doapp.debug.SET_QUIET_HOURS"
        const val ACTION_DUMP_QUIET = "com.evansappstudio.doapp.debug.DUMP_QUIET"
        const val ACTION_DUMP_META = "com.evansappstudio.doapp.debug.DUMP_META"
        const val ACTION_DUMP_EVENTS = "com.evansappstudio.doapp.debug.DUMP_EVENTS"
        const val ACTION_DELETE = "com.evansappstudio.doapp.debug.DELETE"
        const val ACTION_RESTORE = "com.evansappstudio.doapp.debug.RESTORE"
        const val ACTION_PURGE_NOW = "com.evansappstudio.doapp.debug.PURGE_NOW"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_ID = "sourceId"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_TEMPLATE_ID = "templateId"
        const val EXTRA_TEMPLATE_VERSION = "templateVersion"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_DAYS = "days"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_FOLLOW_DND = "followDnd"
        const val EXTRA_CRITICAL_PAUSES = "criticalPauses"
        const val EXTRA_SIM_DND = "simDnd"
        const val EXTRA_PATH = "path"
        const val EXTRA_UUIDS = "uuids"
        const val EXTRA_STATE = "state"
        const val EXTRA_INTRO = "intro"
        const val EXTRA_CLEAR_SUCCESS = "clearSuccess"
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
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_WEEK_DAYS = "weekDays"
        const val EXTRA_MONTH_ON = "monthOn"
        const val EXTRA_DUE_AT = "dueAt"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOW = "now"
        const val EXTRA_DRAFT_DUE = "draftDue"
        const val EXTRA_PARSE = "parse"
        const val EXTRA_ALERT = "alert"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_LAG_MS = "lagMs"
        private const val MAX_APPLY_COUNT = 1_000
        private const val MAX_DUMP_COUNT = 24
        private const val DEFAULT_EVENT_LIMIT = 20
        private val DEBUG = Origin(Actor.DEBUG)
    }
}

private fun boolExtra(intent: Intent, name: String): Boolean {
    if (!intent.hasExtra(name)) return false
    val text = intent.getStringExtra(name)
    if (text != null) return text.equals("true", ignoreCase = true) || text == "1"
    return intent.getBooleanExtra(name, false)
}

private fun explicitAlert(intent: Intent): AlertLevel? {
    if (!intent.hasExtra(DebugReceiver.EXTRA_ALERT)) return null
    return AlertLevel.fromName(intent.getStringExtra(DebugReceiver.EXTRA_ALERT))
}

private fun repeatUnitIsOff(intent: Intent): Boolean {
    val unit = intent.getStringExtra(DebugReceiver.EXTRA_UNIT)?.trim()
    return unit.isNullOrEmpty() || unit.equals("OFF", ignoreCase = true)
}

private fun repeatFromIntent(intent: Intent): RepeatRule? {
    if (repeatUnitIsOff(intent)) return null
    val unit = RepeatUnit.fromName(intent.getStringExtra(DebugReceiver.EXTRA_UNIT)?.trim()?.uppercase())
        ?: return null
    val interval = if (intent.hasExtra(DebugReceiver.EXTRA_INTERVAL)) {
        intent.getIntExtra(DebugReceiver.EXTRA_INTERVAL, 1)
    } else {
        1
    }
    val fromRaw = intent.getStringExtra(DebugReceiver.EXTRA_FROM)
    val from = if (fromRaw.isNullOrBlank()) {
        RepeatFrom.DUE_DATE
    } else {
        RepeatFrom.fromName(fromRaw.trim().uppercase()) ?: return null
    }
    val days = WeekDay.parseList(intent.getStringExtra(DebugReceiver.EXTRA_WEEK_DAYS)) ?: return null
    val monthRaw = intent.getStringExtra(DebugReceiver.EXTRA_MONTH_ON)
    val month = if (monthRaw.isNullOrBlank()) null else MonthOn.parse(monthRaw) ?: return null
    return RepeatRule.of(interval, unit, from, days, month)
}
