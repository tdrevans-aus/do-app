package com.evansappstudio.doapp.alarm

/** Notification and PendingIntent request codes are ints. Room ids start at 1. */
object ReminderIds {
    fun asInt(id: Long): Int {
        val narrowed = (id and 0x7FFF_FFFFL).toInt()
        return if (narrowed == 0) 1 else narrowed
    }
}
