package com.evansappstudio.doapp.alarm

import android.app.NotificationManager
import android.content.Context

/**
 * Live Do Not Disturb, from [NotificationManager.getCurrentInterruptionFilter] only.
 * PRIORITY, NONE and ALARMS count as on. ALL and UNKNOWN count as off, so an
 * unreadable filter never pauses a nag. Reading the filter needs no notification
 * policy access.
 *
 * [debugFilter] is installed only by the debug source set. Release leaves it
 * null and always reads the real filter.
 */
object DndState {
    @Volatile
    var debugFilter: (() -> Int?)? = null

    fun filter(context: Context): Int {
        val overridden = debugFilter?.invoke()
        if (overridden != null) return overridden
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (error: Throwable) {
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
    }

    fun isActive(filter: Int): Boolean = isActive(
        filter,
        priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        none = NotificationManager.INTERRUPTION_FILTER_NONE,
        alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS,
    )

    /**
     * PRIORITY, NONE and ALARMS are on. ALL and UNKNOWN are off, so an
     * unreadable filter never pauses a nag. The codes are arguments so a JVM
     * test can pass the documented values. The android.jar stubs leave the
     * NotificationManager fields without constant values.
     */
    internal fun isActive(filter: Int, priority: Int, none: Int, alarms: Int): Boolean {
        return filter == priority || filter == none || filter == alarms
    }

    fun isActive(context: Context): Boolean = isActive(filter(context))
}
