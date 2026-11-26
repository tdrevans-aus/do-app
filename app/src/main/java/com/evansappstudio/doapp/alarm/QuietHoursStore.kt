package com.evansappstudio.doapp.alarm

import android.annotation.SuppressLint
import android.content.Context

/**
 * Device-protected quiet-hours settings. Fires before the first unlock cannot
 * read credential storage, so this file lives next to the mirror. A missing
 * key reads as its default. An unreadable store means quiet hours off.
 */
class QuietHoursStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun read(): QuietConfig {
        return try {
            synchronized(lock) { readLocked() }
        } catch (error: Throwable) {
            QuietConfig.DEFAULT
        }
    }

    /** False when the window would start and end on the same minute, or a minute is out of range. */
    @SuppressLint("ApplySharedPref")
    fun write(config: QuietConfig): Boolean {
        if (!QuietHours.minuteInRange(config.startMinute) || !QuietHours.minuteInRange(config.endMinute)) {
            return false
        }
        if (config.startMinute == config.endMinute) return false
        return try {
            synchronized(lock) {
                prefs.edit()
                    .putBoolean(KEY_ENABLED, config.enabled)
                    .putInt(KEY_START, config.startMinute)
                    .putInt(KEY_END, config.endMinute)
                    .putBoolean(KEY_FOLLOW, config.followDnd)
                    .putBoolean(KEY_CRITICAL, config.criticalPauses)
                    .commit()
            }
        } catch (error: Throwable) {
            false
        }
    }

    fun apply(patch: QuietPatch) {
        write(QuietHours.merge(read(), patch))
    }

    private fun readLocked(): QuietConfig {
        val start = storedMinute(KEY_START, QuietConfig.DEFAULT_START_MINUTE)
        val end = storedMinute(KEY_END, QuietConfig.DEFAULT_END_MINUTE)
        return QuietConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            startMinute = start,
            endMinute = end,
            followDnd = prefs.getBoolean(KEY_FOLLOW, false),
            criticalPauses = prefs.getBoolean(KEY_CRITICAL, false),
        )
    }

    private fun storedMinute(key: String, default: Int): Int {
        if (!prefs.contains(key)) return default
        val stored = prefs.getInt(key, default)
        return if (QuietHours.minuteInRange(stored)) stored else default
    }

    companion object {
        const val PREFS = "do_quiet"
        const val KEY_ENABLED = "quiet_enabled"
        const val KEY_START = "quiet_start_min"
        const val KEY_END = "quiet_end_min"
        const val KEY_FOLLOW = "quiet_follow_dnd"
        const val KEY_CRITICAL = "quiet_critical_pauses"
    }
}
