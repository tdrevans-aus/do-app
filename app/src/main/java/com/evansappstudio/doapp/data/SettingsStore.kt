package com.evansappstudio.doapp.data

import android.annotation.SuppressLint
import android.content.Context
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.NagSchedule
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimeUnit
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.backup.PortableSettings

/**
 * App settings. The default nag for a new reminder is 5 minutes.
 * Preferences live in credential storage, so callers must be unlocked.
 */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun defaultNagMinutes(): Int {
        if (!prefs.contains(KEY_DEFAULT_NAG)) {
            prefs.edit().putInt(KEY_DEFAULT_NAG, DEFAULT_NAG_MINUTES).apply()
        }
        val stored = prefs.getInt(KEY_DEFAULT_NAG, DEFAULT_NAG_MINUTES)
        return NagSchedule.normalize(stored) ?: DEFAULT_NAG_MINUTES
    }

    /** Keys that travel in a backup. Entitlement, purchase tokens and one-shot flags stay out. */
    fun portable(): PortableSettings = PortableSettings(
        defaultNagMinutes = defaultNagMinutes(),
        snooze = snoozeOptions(),
        quickTimes = quickTimes(),
        weekStart = weekStart(),
        parseDates = parseDatesEnabled(),
        defaultAlertLevel = defaultAlertLevel(),
        repeatPromptMode = repeatPromptMode(),
    )

    @SuppressLint("ApplySharedPref")
    fun replacePortable(portable: PortableSettings) {
        require(portable.snooze.size == SnoozeDue.DEFAULTS.size) { "snooze options" }
        require(portable.quickTimes.slots.size == QuickTimes.SLOT_COUNT) { "quick times" }
        val editor = prefs.edit()
        val nag = NagSchedule.normalize(portable.defaultNagMinutes) ?: DEFAULT_NAG_MINUTES
        editor.putInt(KEY_DEFAULT_NAG, nag)
        portable.snooze.forEachIndexed { index, option ->
            val stored = SnoozeOption.orNull(option.kind, option.value) ?: SnoozeDue.DEFAULTS[index]
            editor.putString(snoozeKindKey(index), stored.kind.name)
            editor.putInt(snoozeValueKey(index), stored.value)
        }
        editor.putString(KEY_QAT_POSTPONE, portable.quickTimes.postpone.name)
        portable.quickTimes.slots.forEachIndexed { index, slot ->
            val stored = QuickTimeSlot.orNull(slot.kind, slot.amount, slot.unit) ?: QuickTimes.FACTORY[index]
            val normalized = if (stored.kind == SnoozeKind.AT_TIME) {
                stored.copy(unit = QuickTimeUnit.MINUTE)
            } else {
                stored
            }
            editor.putString(qatKindKey(index), normalized.kind.name)
            editor.putInt(qatAmountKey(index), normalized.amount)
            editor.putString(qatUnitKey(index), normalized.unit.name)
        }
        editor.putString(KEY_WEEK_START, portable.weekStart.name)
        editor.putBoolean(KEY_PARSE_DATES, portable.parseDates)
        editor.putString(KEY_ALERT_DEFAULT, portable.defaultAlertLevel.name)
        editor.putString(KEY_REPEAT_PROMPT, portable.repeatPromptMode.name)
        editor.commit()
    }

    fun hasRequestedNotificationPermission(): Boolean =
        prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)

    fun markNotificationPermissionRequested() {
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
    }

    /** Three one-tap snoozes. Missing keys stay on the factory defaults. */
    fun snoozeOptions(): List<SnoozeOption> = List(SnoozeDue.DEFAULTS.size) { index ->
        val kind = SnoozeKind.fromName(prefs.getString(snoozeKindKey(index), null))
        val value = prefs.getInt(snoozeValueKey(index), Int.MIN_VALUE)
        if (kind == null) {
            SnoozeDue.DEFAULTS[index]
        } else {
            SnoozeOption.orNull(kind, value) ?: SnoozeDue.DEFAULTS[index]
        }
    }

    @SuppressLint("ApplySharedPref")
    fun setSnoozeOption(index: Int, option: SnoozeOption) {
        require(index in SnoozeDue.DEFAULTS.indices) { "snooze option index $index" }
        require(option.isValid()) { "invalid snooze option" }
        // commit, not apply: QA kills the process immediately after CONFIG_SNOOZE.
        prefs.edit()
            .putString(snoozeKindKey(index), option.kind.name)
            .putInt(snoozeValueKey(index), option.value)
            .commit()
    }

    /** Twelve Quick Times slots and postpone behaviour. Missing keys stay on the factory defaults. */
    fun quickTimes(): QuickTimesSettings {
        val postpone = PostponeMode.fromName(prefs.getString(KEY_QAT_POSTPONE, null))
            ?: QuickTimes.DEFAULT_POSTPONE
        val slots = List(QuickTimes.SLOT_COUNT) { index -> quickTimeSlot(index) }
        return QuickTimesSettings(slots, postpone)
    }

    @SuppressLint("ApplySharedPref")
    fun setQuickTimeSlot(index: Int, slot: QuickTimeSlot) {
        require(index in QuickTimes.FACTORY.indices) { "quick time slot $index" }
        require(slot.isValid()) { "invalid quick time slot" }
        val stored = if (slot.kind == SnoozeKind.AT_TIME) {
            slot.copy(unit = QuickTimeUnit.MINUTE)
        } else {
            slot
        }
        // commit, not apply: QA kills the process immediately after CONFIG_QAT.
        prefs.edit()
            .putString(qatKindKey(index), stored.kind.name)
            .putInt(qatAmountKey(index), stored.amount)
            .putString(qatUnitKey(index), stored.unit.name)
            .commit()
    }

    /** Factory is prompt each time. Always-this is opt-in. */
    fun repeatPromptMode(): RepeatPromptMode =
        RepeatPromptMode.fromName(prefs.getString(KEY_REPEAT_PROMPT, null)) ?: RepeatPromptMode.PROMPT

    fun weekStart(): WeekStart =
        WeekStart.fromName(prefs.getString(KEY_WEEK_START, null)) ?: WeekStart.DEFAULT

    @SuppressLint("ApplySharedPref")
    fun setWeekStart(start: WeekStart) {
        prefs.edit().putString(KEY_WEEK_START, start.name).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun setRepeatPromptMode(mode: RepeatPromptMode) {
        // commit, not apply: QA kills the process immediately after CONFIG_REPEAT_PROMPT.
        prefs.edit().putString(KEY_REPEAT_PROMPT, mode.name).commit()
    }

    /** Factory is on. Due's Reminder Editor toggle, stored with commit like the other QA settings. */
    fun parseDatesEnabled(): Boolean = prefs.getBoolean(KEY_PARSE_DATES, true)

    @SuppressLint("ApplySharedPref")
    fun setParseDatesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PARSE_DATES, enabled).commit()
    }

    /** Factory is Standard. Changing it does not rewrite reminders that already exist. */
    fun defaultAlertLevel(): AlertLevel =
        AlertLevel.fromName(prefs.getString(KEY_ALERT_DEFAULT, null)) ?: AlertLevel.DEFAULT

    @SuppressLint("ApplySharedPref")
    fun setDefaultAlertLevel(level: AlertLevel) {
        prefs.edit().putString(KEY_ALERT_DEFAULT, level.name).commit()
    }

    fun hasExplainedAlert(level: AlertLevel): Boolean =
        prefs.getBoolean(explainedKey(level), false)

    @SuppressLint("ApplySharedPref")
    fun markAlertExplained(level: AlertLevel) {
        prefs.edit().putBoolean(explainedKey(level), true).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun setPostponeMode(mode: PostponeMode) {
        // commit, not apply: QA kills the process immediately after CONFIG_POSTPONE.
        prefs.edit().putString(KEY_QAT_POSTPONE, mode.name).commit()
    }

    private fun quickTimeSlot(index: Int): QuickTimeSlot {
        val factory = QuickTimes.FACTORY[index]
        val kind = SnoozeKind.fromName(prefs.getString(qatKindKey(index), null)) ?: return factory
        if (!prefs.contains(qatAmountKey(index))) return factory
        val amount = prefs.getInt(qatAmountKey(index), 0)
        val unit = when (kind) {
            SnoozeKind.AT_TIME -> QuickTimeUnit.fromName(prefs.getString(qatUnitKey(index), null))
                ?: QuickTimeUnit.MINUTE
            SnoozeKind.FROM_NOW -> QuickTimeUnit.fromName(prefs.getString(qatUnitKey(index), null))
                ?: return factory
        }
        return QuickTimeSlot.orNull(kind, amount, unit) ?: factory
    }

    private fun snoozeKindKey(index: Int) = "snooze_${index}_kind"

    private fun snoozeValueKey(index: Int) = "snooze_${index}_value"

    private fun qatKindKey(index: Int) = "qat_${index}_kind"

    private fun qatAmountKey(index: Int) = "qat_${index}_amount"

    private fun qatUnitKey(index: Int) = "qat_${index}_unit"

    private fun explainedKey(level: AlertLevel) = "explained_alert_${level.name}"

    private companion object {
        const val PREFS = "do_settings"
        const val KEY_DEFAULT_NAG = "default_nag_minutes"
        const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
        const val KEY_QAT_POSTPONE = "qat_postpone"
        const val KEY_REPEAT_PROMPT = "repeat_prompt"
        const val KEY_WEEK_START = "week_start"
        const val KEY_PARSE_DATES = "parse_dates"
        const val KEY_ALERT_DEFAULT = "alert_default"
        const val DEFAULT_NAG_MINUTES = 5
    }
}
