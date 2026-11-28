package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.billing.EntitlementRecord

// Spec 13 pure purchase rules. No Android imports (CorePurityTest).

/**
 * How Do Cloud's verifyPurchase verdict meets Spec 10's local entitlement.
 * The local Play result stays the offline fast path; when a server verdict is
 * present and fresh it wins, because the server reads Play's own records
 * (grace period, account hold, revoked) and acknowledges the purchase.
 */
object CloudEntitlement {
    const val SOURCE_CLOUD = "cloud"

    /** Verify again on resume at most this often (a purchase always verifies at once). */
    const val VERIFY_EVERY_MS = 60L * 60L * 1000L

    /** A verdict older than this no longer overrides Play's local answer. */
    const val FRESH_FOR_MS = 24L * 60L * 60L * 1000L

    fun shouldVerify(justBought: Boolean, lastVerifiedAt: Long, now: Long): Boolean =
        justBought || lastVerifiedAt <= 0L || now - lastVerifiedAt >= VERIFY_EVERY_MS || now < lastVerifiedAt

    /** The cached verdict while it may still override the local record, else null. */
    fun fresh(verdict: VerifiedEntitlement?, verifiedAt: Long, now: Long): VerifiedEntitlement? {
        if (verdict == null || verifiedAt <= 0L) return null
        if (now < verifiedAt || now - verifiedAt >= FRESH_FOR_MS) return null
        val expiry = verdict.expiryTime
        // An entitled verdict past its expiry is not trusted offline: Play's local answer decides.
        if (verdict.entitled && expiry != null && expiry > 0L && now >= expiry) return null
        return verdict
    }

    /** The record to cache once the server has answered. [EntitlementRecord.everSubscribed] is never cleared. */
    fun apply(local: EntitlementRecord, verdict: VerifiedEntitlement, now: Long): EntitlementRecord = EntitlementRecord(
        active = verdict.entitled,
        pending = !verdict.entitled && verdict.state == "pending",
        lastQueryAt = now,
        grantSource = SOURCE_CLOUD,
        lastQueryOk = true,
        everSubscribed = local.everSubscribed || local.active || verdict.entitled,
    )
}
