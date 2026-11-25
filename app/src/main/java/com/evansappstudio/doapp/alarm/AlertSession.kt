package com.evansappstudio.doapp.alarm

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tells a ringing Prominent screen that this reminder was stopped, done or snoozed.
 * Snooze-all only reaches a screen whose own reminder was included.
 */
object AlertSession {
    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()

    fun listen(listener: (Long) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun attended(id: Long) {
        listeners.forEach { listener -> listener(id) }
    }
}
