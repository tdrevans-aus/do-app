package com.evansappstudio.doapp.billing

/**
 * Free-tier cap. This is the only place the number lives.
 *
 * [ACTIVE_REMINDER_CAP] is how many not-done reminders can exist before unlock.
 * It counts every not-done reminder, repeating or not, whatever created it.
 * It is the only free-tier limit (decision of 27 Nov 2026: there is no
 * separate repeating cap). Null or 0 turns the cap off. Unlock always removes
 * it. There is no time trial.
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
