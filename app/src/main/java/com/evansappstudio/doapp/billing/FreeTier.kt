package com.evansappstudio.doapp.billing

/**
 * Free-tier caps. These constants are the only place the numbers live.
 *
 * [ACTIVE_REMINDER_CAP] is how many not-done reminders can exist before unlock.
 * It counts every not-done reminder. [REPEATING_REMINDER_CAP] is how many of
 * those can have Repeat not Off. Null or 0 turns a cap off. Unlock always
 * removes both. There is no time trial.
 */
object FreeTier {
    val ACTIVE_REMINDER_CAP: Int? = 5

    val REPEATING_REMINDER_CAP: Int? = 2

    const val PRODUCT_ID = "do_unlock"

    fun blocksAdd(activeCount: Int, unlocked: Boolean, cap: Int? = ACTIVE_REMINDER_CAP): Boolean {
        if (unlocked) return false
        if (cap == null || cap <= 0) return false
        return activeCount >= cap
    }

    fun blocksRepeat(repeatingCount: Int, unlocked: Boolean, cap: Int? = REPEATING_REMINDER_CAP): Boolean {
        if (unlocked) return false
        if (cap == null || cap <= 0) return false
        return repeatingCount >= cap
    }

    fun capLabel(cap: Int? = ACTIVE_REMINDER_CAP): String = when (cap) {
        null, 0 -> "none"
        else -> cap.toString()
    }

    fun repeatCapLabel(cap: Int? = REPEATING_REMINDER_CAP): String = when (cap) {
        null, 0 -> "none"
        else -> cap.toString()
    }
}
