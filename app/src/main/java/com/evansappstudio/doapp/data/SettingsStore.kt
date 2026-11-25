package com.evansappstudio.doapp.data

import android.content.Context
import com.evansappstudio.doapp.alarm.NagSchedule

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

    private companion object {
        const val PREFS = "do_settings"
        const val KEY_DEFAULT_NAG = "default_nag_minutes"
        const val KEY_ASKED_NOTIFICATIONS = "asked_post_notifications"
        const val DEFAULT_NAG_MINUTES = 5
    }
}
