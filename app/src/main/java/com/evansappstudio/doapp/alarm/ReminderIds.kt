package com.evansappstudio.doapp.alarm

/** Notification and PendingIntent request codes are ints. Room ids start at 1. */
object ReminderIds {
<<<<<<< HEAD
    /** Summary notification. Room ids start at 1, and [asInt] never returns 0. */
    const val SUMMARY_NOTIFICATION_ID = 0

    /** Snooze-all action. Does not collide with per-reminder snooze codes. */
    const val SNOOZE_ALL_REQUEST_CODE = 0x6000_0001

=======
>>>>>>> origin/main
    fun asInt(id: Long): Int {
        val narrowed = (id and 0x7FFF_FFFFL).toInt()
        return if (narrowed == 0) 1 else narrowed
    }
<<<<<<< HEAD

    /**
     * Distinct from [asInt] and from the other two options of the same reminder.
     * The low 28 bits hold the reminder id; the option lives in the bits above that.
     */
    fun snoozeRequestCode(id: Long, option: Int): Int {
        require(option in 0..2) { "option must be 0, 1 or 2" }
        val base = asInt(id) and 0x0FFF_FFFF
        return base or ((option + 1) shl 28)
    }
=======
>>>>>>> origin/main
}
