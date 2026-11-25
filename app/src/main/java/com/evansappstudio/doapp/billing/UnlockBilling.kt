package com.evansappstudio.doapp.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Paid-unlock billing. Release talks to Play. Debug uses a fake that never connects.
 * Do not name an implementation UserUnlock: that name is the device-unlock check.
 */
interface UnlockBilling {
    val ui: StateFlow<UnlockUiState>

    fun start()

    fun onResume()

    fun buy(activity: Activity)

    fun restore()

    fun formattedPrice(): String?

    /** Dump source: fake, play, or cache. */
    fun dumpSource(): String

    fun clearCelebrate()
}

data class UnlockUiState(
    val unlocked: Boolean,
    val pending: Boolean,
    val price: String?,
    val failed: Boolean,
    val celebrate: Boolean,
) {
    val buyEnabled: Boolean = price != null && !unlocked && !pending

    companion object {
        fun from(record: EntitlementRecord, price: String?, failed: Boolean, celebrate: Boolean) = UnlockUiState(
            unlocked = record.unlocked,
            pending = record.pending,
            price = price,
            failed = failed,
            celebrate = celebrate,
        )
    }
}
