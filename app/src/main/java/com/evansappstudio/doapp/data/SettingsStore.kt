package com.evansappstudio.doapp.data

<<<<<<< HEAD
import android.annotation.SuppressLint
import android.content.Context
import com.evansappstudio.doapp.alarm.NagSchedule
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeOption
=======
import android.content.Context
import com.evansappstudio.doapp.alarm.NagSchedule
>>>>>>> origin/main

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

    fun hasRequestedNotificationPermission(): Boolean =
        prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)

    fun markNotificationPermissionRequested() {
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
    }

<<<<<<< HEAD
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

    private fun snoozeKindKey(index: Int) = "snooze_${index}_kind"

    private fun snoozeValueKey(index: Int) = "snooze_${index}_value"

=======
>>>>>>> origin/main
    private companion object {
        const val PREFS = "do_settings"
        const val KEY_DEFAULT_NAG = "default_nag_minutes"
        const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
        const val DEFAULT_NAG_MINUTES = 5
    }
}
