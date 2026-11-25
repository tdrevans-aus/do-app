package com.evansappstudio.doapp.billing

/**
 * Free-tier cap. This is the only place the number lives.
 *
 * [ACTIVE_REMINDER_CAP] is how many not-done reminders can exist before unlock.
 * Null or 0 turns the cap off. Unlock always removes it. Tim chooses the real
 * split and the number later; 5 is the build default. There is no time trial.
 */
object FreeTier {
    val ACTIVE_REMINDER_CAP: Int? = 5

    const val PRODUCT_ID = "do_unlock"

    fun blocksAdd(activeCount: Int, unlocked: Boolean, cap: Int? = ACTIVE_REMINDER_CAP): Boolean {
        if (unlocked) return false
        if (cap == null || cap <= 0) return false
        return activeCount >= cap
    }

    fun capLabel(cap: Int? = ACTIVE_REMINDER_CAP): String = when (cap) {
        null, 0 -> "none"
        else -> cap.toString()
    }
}
