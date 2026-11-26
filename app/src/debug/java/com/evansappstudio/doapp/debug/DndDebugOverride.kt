package com.evansappstudio.doapp.debug

import android.app.NotificationManager
import com.evansappstudio.doapp.alarm.DndState

/**
 * In-memory Do Not Disturb stand-in for the debug build and JVM tests.
 * `real` (the state after every process start) reads the live filter.
 * Release builds do not contain this class.
 */
object DndDebugOverride {
    const val ON = "on"
    const val OFF = "off"
    const val REAL = "real"

    @Volatile
    var mode: String = REAL
        private set

    fun install() {
        DndState.debugFilter = {
            when (mode) {
                ON -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
                else -> null
            }
        }
    }

    /** False when [raw] is not on, off or real. The previous mode stays. */
    fun set(raw: String): Boolean {
        val next = when (raw.trim().lowercase()) {
            ON -> ON
            OFF -> OFF
            REAL -> REAL
            else -> return false
        }
        mode = next
        install()
        return true
    }

    fun source(): String = if (mode == REAL) "real" else "sim"

    fun reset() {
        mode = REAL
        install()
    }
}
