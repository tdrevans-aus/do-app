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
import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.backup.PortableSettings
import com.evansappstudio.doapp.catalogue.Region
import com.evansappstudio.doapp.catalogue.RegionResolver
import com.evansappstudio.doapp.catalogue.RegionSetting
import com.evansappstudio.doapp.catalogue.RegionSource
import com.evansappstudio.doapp.catalogue.SuggestionState
import java.time.LocalDate

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
        sysdRegion = prefs.getString(KEY_SYSD_REGION, null),
        sysdRegionSource = prefs.getString(KEY_SYSD_REGION_SOURCE, null),
        sysdAreas = sysdAreas().toList(),
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
        // Spec 11 optional keys: a file without them leaves the current values.
        if (portable.sysdRegionSource != null) {
            editor.putString(KEY_SYSD_REGION_SOURCE, portable.sysdRegionSource)
            if (portable.sysdRegion != null) {
                editor.putString(KEY_SYSD_REGION, portable.sysdRegion)
            } else {
                editor.remove(KEY_SYSD_REGION)
            }
        }
        portable.sysdAreas?.let { editor.putString(KEY_SYSD_AREAS, encodeAreas(it)) }
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

    /** The catalogue region in force: a USER choice, or worked out from the time zone. */
    fun sysdRegion(zoneId: String): RegionSetting = RegionSetting.effective(
        prefs.getString(KEY_SYSD_REGION, null),
        prefs.getString(KEY_SYSD_REGION_SOURCE, null),
        zoneId,
    )

    /** A null [region] goes back to AUTO (from the time zone). */
    @SuppressLint("ApplySharedPref")
    fun setSysdRegion(region: Region?) {
        val editor = prefs.edit()
        if (region == null) {
            editor.remove(KEY_SYSD_REGION).putString(KEY_SYSD_REGION_SOURCE, RegionSource.AUTO.name)
        } else {
            editor.putString(KEY_SYSD_REGION, RegionResolver.token(region))
                .putString(KEY_SYSD_REGION_SOURCE, RegionSource.USER.name)
        }
        editor.commit()
    }

    /** The onboarding profile: the life areas picked in first run step 3. */
    fun sysdAreas(): Set<String> = decodeAreas(prefs.getString(KEY_SYSD_AREAS, null))

    @SuppressLint("ApplySharedPref")
    fun setSysdAreas(areas: Set<String>) {
        prefs.edit().putString(KEY_SYSD_AREAS, encodeAreas(areas.toList())).commit()
    }

    /** First run steps 3 and 4: [ONBOARDING_PENDING], [ONBOARDING_STARTED], [ONBOARDING_DONE] or [ONBOARDING_SKIPPED]. */
    fun sysdOnboarding(): String = prefs.getString(KEY_SYSD_ONBOARDING, null) ?: ONBOARDING_PENDING

    @SuppressLint("ApplySharedPref")
    fun setSysdOnboarding(state: String) {
        prefs.edit().putString(KEY_SYSD_ONBOARDING, state).commit()
    }

    fun suggestionState(): SuggestionState = SuggestionState.decode(prefs.getString(KEY_SYSD_SUGGESTIONS, null))

    @SuppressLint("ApplySharedPref")
    fun setSuggestionState(state: SuggestionState) {
        prefs.edit().putString(KEY_SYSD_SUGGESTIONS, state.encode()).commit()
    }

    /**
     * Debug override for the date SuggestionPolicy uses, so QA never changes the phone clock.
     * Only the debug receiver writes it; nothing else reads it.
     */
    fun suggestionDateOverride(): LocalDate? = try {
        prefs.getString(KEY_SYSD_TODAY, null)?.let { LocalDate.parse(it) }
    } catch (error: RuntimeException) {
        null
    }

    @SuppressLint("ApplySharedPref")
    fun setSuggestionDateOverride(date: LocalDate?) {
        val editor = prefs.edit()
        if (date == null) editor.remove(KEY_SYSD_TODAY) else editor.putString(KEY_SYSD_TODAY, date.toString())
        editor.commit()
    }

    private fun encodeAreas(areas: List<String>): String =
        areas.mapNotNull { ReminderCategory.normalize(it) }.distinct().joinToString(",")

    private fun decodeAreas(raw: String?): Set<String> =
        raw.orEmpty().split(",").mapNotNull { ReminderCategory.normalize(it) }.toCollection(LinkedHashSet())

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

    companion object {
        const val ONBOARDING_PENDING = "PENDING"
        const val ONBOARDING_STARTED = "STARTED"
        const val ONBOARDING_DONE = "DONE"
        const val ONBOARDING_SKIPPED = "SKIPPED"
        private const val KEY_SYSD_REGION = "sysd_region"
        private const val KEY_SYSD_REGION_SOURCE = "sysd_region_source"
        private const val KEY_SYSD_AREAS = "sysd_areas"
        private const val KEY_SYSD_ONBOARDING = "sysd_onboarding"
        private const val KEY_SYSD_SUGGESTIONS = "sysd_suggestions"
        private const val KEY_SYSD_TODAY = "sysd_debug_today"
        private const val PREFS = "do_settings"
        private const val KEY_DEFAULT_NAG = "default_nag_minutes"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
        private const val KEY_QAT_POSTPONE = "qat_postpone"
        private const val KEY_REPEAT_PROMPT = "repeat_prompt"
        private const val KEY_WEEK_START = "week_start"
        private const val KEY_PARSE_DATES = "parse_dates"
        private const val KEY_ALERT_DEFAULT = "alert_default"
        private const val DEFAULT_NAG_MINUTES = 5
    }
}
