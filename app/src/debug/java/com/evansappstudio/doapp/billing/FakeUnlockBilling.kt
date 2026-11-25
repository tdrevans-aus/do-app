package com.evansappstudio.doapp.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug billing. It never opens a BillingClient and never calls Play.
 * A fresh debug install is unlocked so the Spec 1 to 6 runners keep working.
 */
class FakeUnlockBilling(
    private val store: EntitlementStore,
) : UnlockBilling {
    private var failed = false
    private var celebrate = false
    private val uiState = MutableStateFlow(current())
    override val ui: StateFlow<UnlockUiState> = uiState.asStateFlow()

    override fun start() {
        if (!store.hasRecord()) {
            store.write(
                EntitlementRecord(
                    unlocked = true,
                    pending = false,
                    lastQueryAt = System.currentTimeMillis(),
                    grantSource = EntitlementPolicy.SOURCE_FAKE,
                    lastQueryOk = true,
                ),
            )
        }
        publish()
    }

    override fun onResume() {
        publish()
    }

    override fun buy(activity: Activity) {
        // Price stays unknown on a sideloaded debug build, so Buy stays disabled.
        // This method must not start a billing connection.
    }

    override fun restore() {
        publish()
    }

    override fun formattedPrice(): String? = null

    override fun dumpSource(): String = "fake"

    override fun clearCelebrate() {
        celebrate = false
        publish()
    }

    /** locked, pending, or unlocked. Writes the same cache fields a Play grant or revoke would. */
    fun applyState(state: String): EntitlementRecord? {
        val now = System.currentTimeMillis()
        val previous = store.read()
        val record = when (state) {
            "unlocked" -> EntitlementRecord(true, false, now, EntitlementPolicy.SOURCE_FAKE, true)
            "pending" -> EntitlementRecord(false, true, now, EntitlementPolicy.SOURCE_FAKE, true)
            "locked" -> EntitlementRecord(false, false, now, EntitlementPolicy.SOURCE_FAKE, true)
            else -> return null
        }
        celebrate = record.unlocked && !previous.unlocked
        failed = false
        store.write(record)
        publish()
        return record
    }

    private fun publish() {
        uiState.value = current()
    }

    private fun current(): UnlockUiState = UnlockUiState.from(
        record = store.read(),
        price = null,
        failed = failed,
        celebrate = celebrate,
    )
}

fun createUnlockBilling(context: android.content.Context, store: EntitlementStore): UnlockBilling {
    return FakeUnlockBilling(store)
}
