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

    const val ACCOUNT_PREFIX = "do-account:"

    /**
     * CONTRACT.md section 5: base64url(SHA-256("do-account:" + uid)), no padding, 43 characters.
     * Passed to Play with the purchase (BillingFlowParams.setObfuscatedAccountId) and to
     * verifyPurchase, so the server entitles only the installation that bought it.
     */
    fun obfuscatedAccountId(uid: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest((ACCOUNT_PREFIX + uid).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** Whether the server's answer may change the local record at all. other_device: no opinion. */
    fun hasOpinion(verdict: VerifiedEntitlement): Boolean = !verdict.noOpinion

    fun shouldVerify(justBought: Boolean, lastVerifiedAt: Long, now: Long): Boolean =
        justBought || lastVerifiedAt <= 0L || now - lastVerifiedAt >= VERIFY_EVERY_MS || now < lastVerifiedAt

    /** The cached verdict while it may still override the local record, else null. */
    fun fresh(verdict: VerifiedEntitlement?, verifiedAt: Long, now: Long): VerifiedEntitlement? {
        if (verdict == null || verifiedAt <= 0L || verdict.noOpinion) return null
        if (now < verifiedAt || now - verifiedAt >= FRESH_FOR_MS) return null
        val expiry = verdict.expiryTime
        // An entitled verdict past its expiry is not trusted offline: Play's local answer decides.
        if (verdict.entitled && expiry != null && expiry > 0L && now >= expiry) return null
        return verdict
    }

    /** The record to cache once the server has answered. [EntitlementRecord.everSubscribed] is never cleared. */
    fun apply(local: EntitlementRecord, verdict: VerifiedEntitlement, now: Long): EntitlementRecord {
        // other_device never switches a real buyer off (or on): the local Play result stands.
        if (verdict.noOpinion) return local
        val entitled = verdict.entitled && verdict.state != VerifiedEntitlement.TRANSFERRED
        return record(local, verdict, entitled, now)
    }

    private fun record(local: EntitlementRecord, verdict: VerifiedEntitlement, entitled: Boolean, now: Long) = EntitlementRecord(
        active = entitled,
        pending = !entitled && verdict.state == "pending",
        lastQueryAt = now,
        grantSource = SOURCE_CLOUD,
        lastQueryOk = true,
        everSubscribed = local.everSubscribed || local.active || entitled,
    )
}
