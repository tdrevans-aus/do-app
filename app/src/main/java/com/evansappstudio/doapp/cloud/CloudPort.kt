package com.evansappstudio.doapp.cloud

// Spec 13 pure cloud types. No Android or Firebase imports: this file is in
// CorePurityTest's list, so the host behind CloudPort can be swapped (Firebase
// now, Cloudflare possibly later) and the types can be shared with an iPhone app.
// The JSON shapes are in cloud/CONTRACT.md; CloudJson.kt reads and writes them.

/**
 * The phone's side of Do Cloud. Every call needs the device's cloud identity
 * (Anonymous Auth): [signIn] makes one, the others fail with
 * [CloudCallCode.UNAUTHENTICATED] without it. Failures are [CloudException].
 */
interface CloudPort {
    /** The identity already on this device, or null. Starts nothing. */
    fun currentIdentity(): CloudIdentity?

    /** Signs in anonymously if there is no identity yet. */
    suspend fun signIn(): CloudIdentity

    /** The current FCM registration token, or null when Messaging can't make one (no Play services, the emulator project). */
    suspend fun messagingToken(): String?

    suspend fun registerDevice(request: RegisterDevice)

    suspend fun setAssistantsEnabled(enabled: Boolean): Boolean

    suspend fun createPairingCode(): PairingCode

    /** devices/{uid}/agents, read directly (the rules let a device read its own documents). */
    suspend fun listAgents(): List<CloudAgent>

    suspend fun disconnectAgent(agentId: String)

    suspend fun pullInbox(): List<InboxRequest>

    /** True when the server had already recorded a result for this request (a harmless retry). */
    suspend fun completeRequest(requestId: String, result: RequestResult): Boolean

    suspend fun syncAgentMirror(batch: MirrorBatch): MirrorAck

    suspend fun verifyPurchase(request: PurchaseCheck): VerifiedEntitlement
}

/**
 * Build config for Do Cloud. Blank [projectId], [apiKey] or [appId] means the
 * cloud is off: every cloud feature is hidden and nothing Firebase starts.
 * [emulatorHost] (debug only) points Auth, Functions and Firestore at the
 * Emulator Suite on that host. [siteUrl] is the public site; blank means
 * https://<projectId>.web.app, or the Hosting emulator when [emulatorHost] is set.
 */
data class CloudConfig(
    val projectId: String,
    val apiKey: String,
    val appId: String,
    val emulatorHost: String = "",
    val siteUrl: String = "",
) {
    val enabled: Boolean
        get() = projectId.isNotBlank() && apiKey.isNotBlank() && appId.isNotBlank()

    val usesEmulator: Boolean get() = enabled && emulatorHost.isNotBlank()

    /** The project number inside a Firebase app id (1:<number>:android:<hash>), which FCM uses as sender id. */
    val senderId: String?
        get() = appId.split(':').getOrNull(1)?.takeIf { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }

    /** The address assistants connect to, or null when the cloud is off. */
    fun mcpUrl(): String? {
        if (!enabled) return null
        val site = siteUrl.trim().trimEnd('/')
        val base = when {
            site.isNotEmpty() -> site
            usesEmulator -> "http://${emulatorHost.trim()}:$HOSTING_EMULATOR_PORT"
            else -> "https://${projectId.trim()}.web.app"
        }
        return "$base/mcp"
    }

    companion object {
        /** Ports from cloud/firebase.json. */
        const val AUTH_EMULATOR_PORT = 9099
        const val FUNCTIONS_EMULATOR_PORT = 5001
        const val FIRESTORE_EMULATOR_PORT = 8080
        const val HOSTING_EMULATOR_PORT = 5002

        /** Every Do Cloud function runs in Sydney (cloud/CONTRACT.md). */
        const val REGION = "australia-southeast1"

        val OFF = CloudConfig(projectId = "", apiKey = "", appId = "")
    }
}

data class CloudIdentity(val uid: String)

data class RegisterDevice(val fcmToken: String, val platform: String = PLATFORM_ANDROID, val appVersion: String? = null) {
    companion object {
        const val PLATFORM_ANDROID = "android"
    }
}

/** An 8-character code from [ALPHABET], valid for 10 minutes, single use. */
data class PairingCode(val code: String, val expiresAt: Long) {
    /** "ABCD EFGH": the authorise page ignores spaces. */
    fun display(): String = if (code.length == 8) code.substring(0, 4) + " " + code.substring(4) else code

    /** Whole seconds left, never below zero. */
    fun secondsLeft(now: Long): Long = maxOf(0L, (expiresAt - now + 999L) / 1000L)

    fun expired(now: Long): Boolean = now >= expiresAt

    companion object {
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    }
}

/** devices/{uid}/agents/{agentId}. [clientName] is untrusted: show it as plain text. */
data class CloudAgent(
    val agentId: String,
    val clientName: String,
    val clientId: String?,
    val createdAt: Long?,
    val lastUsedAt: Long?,
    val grantRevokedAt: Long?,
) {
    val revoked: Boolean get() = grantRevokedAt != null

    companion object {
        /** ag_ plus 16 hex digits. */
        val ID_PATTERN = Regex("^ag_[0-9a-f]{16}$")

        fun isAgentId(raw: String?): Boolean = raw != null && ID_PATTERN.matches(raw)
    }
}

/** One assistant request from pullInbox. */
data class InboxRequest(
    val requestId: String,
    val agentId: String,
    val clientName: String,
    val kind: String,
    val args: InboxArgs,
    val createdAt: Long,
    val expiresAt: Long,
) {
    companion object {
        const val KIND_CREATE = "create_reminder"
        const val KIND_COMPLETE = "complete_reminder"
    }
}

sealed interface InboxArgs {
    data class Create(val text: String?, val dueAt: Long?, val repeat: String?, val alertLevel: String?) : InboxArgs

    data class Complete(val reminderUuid: String?) : InboxArgs

    /** A kind this phone does not know. Answered invalid_argument. */
    data object Unknown : InboxArgs
}

/** The `<Reminder>` of CONTRACT.md section 3. */
data class WireReminder(
    val uuid: String,
    val title: String,
    val dueAt: Long,
    val status: String,
    val alertLevel: String,
    val requestedAlertLevel: String?,
    val approval: String,
    val repeat: String?,
    val version: Int,
    val updatedAt: Long,
) {
    companion object {
        const val ACTIVE = "active"
        const val DONE = "done"
        const val DELETED = "deleted"
        const val MAX_TITLE = 1000
        const val MAX_REPEAT = 200
    }
}

/** What the phone sends to completeRequest. Exactly one per request. */
sealed interface RequestResult {
    data class Done(val reminder: WireReminder) : RequestResult

    data class Failed(val code: PhoneErrorCode, val message: String?) : RequestResult
}

/** The error codes the phone may send, one to one from Spec 12's AgentError. */
enum class PhoneErrorCode(val wire: String) {
    LIMIT_REACHED("limit_reached"),
    NOT_FOUND("not_found"),
    INVALID_ARGUMENT("invalid_argument"),
    DISABLED("disabled"),
    ;

    companion object {
        fun fromWire(raw: String?): PhoneErrorCode? = entries.firstOrNull { it.wire == raw }

        /** completeRequest messages are at most this long. */
        const val MAX_MESSAGE = 300
    }
}

/** One event for syncAgentMirror. Never the detail, the actor or anything with the title. */
data class MirrorEvent(val id: Long, val reminderUuid: String, val type: String, val at: Long)

data class MirrorBatch(val agentId: String, val reminders: List<WireReminder>, val events: List<MirrorEvent>) {
    companion object {
        const val MAX_REMINDERS = 200
        const val MAX_EVENTS = 500
    }
}

data class MirrorAck(val reminders: Int, val events: Int, val skipped: Int)

data class PurchaseCheck(
    val packageName: String,
    val productId: String,
    val purchaseToken: String,
    val platform: String = "play",
)

/** devices/{uid}/entitlement/current, as verifyPurchase returns it. Use [entitled]. */
data class VerifiedEntitlement(
    val state: String,
    val entitled: Boolean,
    val expiryTime: Long?,
    val autoRenewing: Boolean,
    val productId: String?,
    val lastCheckedAt: Long?,
)

/** The standard callable error codes (CONTRACT.md), plus [OTHER] for anything else. */
enum class CloudCallCode(val wire: String) {
    INVALID_ARGUMENT("invalid-argument"),
    NOT_FOUND("not-found"),
    UNAUTHENTICATED("unauthenticated"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal"),
    OTHER("other"),
    ;

    /** Worth trying again later (network, server busy, sign-in lost). */
    val retryable: Boolean get() = this == UNAVAILABLE || this == INTERNAL || this == UNAUTHENTICATED || this == OTHER

    companion object {
        fun fromWire(raw: String?): CloudCallCode {
            val clean = raw?.trim()?.lowercase()?.replace('_', '-')
            return entries.firstOrNull { it.wire == clean } ?: OTHER
        }
    }
}

class CloudException(val code: CloudCallCode, message: String, cause: Throwable? = null) : Exception(message, cause)
