package com.evansappstudio.doapp.alarm

/**
 * Nag fire times are anchored at [dueAt]: dueAt, then dueAt + k * interval.
 * A late delivery does not move the anchor, so the cadence does not drift.
 * A missed fire is delivered at "now"; the following fires return to the anchor.
 * Nag off alerts once.
 */
object NagSchedule {
    const val MINUTE_MILLIS: Long = 60_000L

    val ALLOWED_MINUTES: Set<Int> = setOf(1, 5, 10, 15, 30, 60)

    /** Null and 0 mean nag off. Any other value outside [ALLOWED_MINUTES] is off. */
    fun normalize(minutes: Int?): Int? = when (minutes) {
        null, 0 -> null
        in ALLOWED_MINUTES -> minutes
        else -> null
    }

    fun intervalMillis(nagMinutes: Int): Long = nagMinutes.toLong() * MINUTE_MILLIS

    /** The k-th fire instant. k = 0 is [dueAt]. */
    fun anchorAt(dueAt: Long, nagMinutes: Int, k: Long): Long {
        require(k >= 0) { "k must be >= 0" }
        require(nagMinutes > 0) { "nagMinutes must be positive" }
        return dueAt + k * intervalMillis(nagMinutes)
    }

    /**
     * First schedule, or a catch-up when no alarm is outstanding.
     * A future due time stays put. A due time already passed fires now.
     */
    fun catchUp(dueAt: Long, now: Long): Long = if (now < dueAt) dueAt else now

    /**
     * Smallest anchor strictly after [now]. Used after a delivery so the fire
     * that just happened is not scheduled again.
     */
    fun nextAnchorStrictlyAfter(dueAt: Long, nagMinutes: Int, now: Long): Long {
        val interval = intervalMillis(nagMinutes)
        if (now < dueAt) return dueAt
        val elapsed = now - dueAt
        val steps = elapsed / interval
        val boundary = dueAt + steps * interval
        return if (boundary > now) boundary else boundary + interval
    }

    /**
     * Next alarm after a notification has been posted at [now].
     * Nag off returns null: the reminder alerts once and does not reschedule.
     */
    fun afterDelivery(dueAt: Long, nagMinutes: Int?, now: Long): Long? {
        val minutes = nagMinutes ?: return null
        if (minutes <= 0) return null
        return nextAnchorStrictlyAfter(dueAt, minutes, now)
    }
}
