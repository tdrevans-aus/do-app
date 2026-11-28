package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.ApprovalDecision
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderCommands
import com.evansappstudio.doapp.billing.EntitlementRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Background work for Do Cloud. Android: WorkManager (CloudWork). Tests: a recorder. */
interface CloudJobs {
    /** Pull the inbox and apply it. [expedited] after a push, so it runs within seconds. */
    fun pull(expedited: Boolean)

    /** Debounced mirror sync. */
    fun syncMirror()
}

/** What the Assistants screen shows. */
data class AssistantsUi(
    val available: Boolean,
    val hasIdentity: Boolean = false,
    val assistantsEnabled: Boolean = true,
    val askBeforeRingThrough: Boolean = true,
    val agents: List<CloudAgent> = emptyList(),
    val pairing: PairingCode? = null,
    val mcpUrl: String? = null,
    val busy: Boolean = false,
    /** The last call failed (no network, Do Cloud unreachable). Cleared by the next success. */
    val failed: Boolean = false,
)

/** What one inbox pull did, for the worker and CLOUD_PULL. */
data class PullRun(val pulled: Int, val applied: Int, val replayed: Int, val completed: Int, val deferred: Int)

/** What the cloud did with a Play purchase token. */
enum class VerifyOutcome { OFF, NO_IDENTITY, CACHED, VERIFIED, KEPT_LOCAL }

/**
 * Spec 13: the phone's side of Do Cloud. Android-free so it runs on the JVM with a
 * fake [CloudPort]. When [config] is off, every method returns at once, [port] is
 * never built and nothing Firebase starts; the app behaves exactly as Spec 12.
 *
 * A cloud failure never touches alarms: the inbox is applied through
 * [ReminderCommands] like any other change, and errors here are logged by the
 * caller and retried by [jobs], never shown as a reminder failure.
 */
class DoCloud(
    val config: CloudConfig,
    portFactory: () -> CloudPort,
    private val store: CloudStore,
    private val inbox: () -> CloudInbox,
    private val mirror: (CloudPort) -> CloudMirror,
    private val commands: () -> ReminderCommands,
    private val jobs: CloudJobs,
    private val entitlement: EntitlementAccess,
    private val appVersion: String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Reads and writes the Spec 10 cache, and tells billing to re-read it. */
    interface EntitlementAccess {
        fun read(): EntitlementRecord

        fun write(record: EntitlementRecord)

        fun changed()
    }

    val enabled: Boolean = config.enabled
    private val port: CloudPort by lazy(portFactory)
    private val pullGate = Mutex()
    private val identityGate = Mutex()
    private val state = MutableStateFlow(initialUi())
    val ui: StateFlow<AssistantsUi> = state.asStateFlow()

    private fun initialUi(): AssistantsUi {
        if (!enabled) return AssistantsUi(available = false)
        return AssistantsUi(
            available = true,
            hasIdentity = store.identityUid() != null,
            assistantsEnabled = store.assistantsEnabled(),
            askBeforeRingThrough = store.askBeforeRingThrough(),
            agents = store.agents(),
            mcpUrl = config.mcpUrl(),
        )
    }

    fun hasIdentity(): Boolean = enabled && store.identityUid() != null

    // Triggers. Each is cheap and safe to call from any thread.

    /** App start and network regained: pull and sync when this device already has an identity. */
    fun onAppStart() {
        if (!hasIdentity()) return
        jobs.pull(expedited = false)
        jobs.syncMirror()
    }

    fun onNetworkAvailable() = onAppStart()

    /** An FCM data message. Only `{ kind: inbox }` means anything. */
    fun onPush(data: Map<String, String>) {
        if (!hasIdentity()) return
        if (data[PUSH_KIND] == PUSH_INBOX) jobs.pull(expedited = true)
    }

    /** After any reminder change (ReminderCommands listener) or fire. Only when an assistant could care. */
    fun onRemindersChanged() {
        if (!hasIdentity() || store.agents().isEmpty()) return
        jobs.syncMirror()
    }

    // Work bodies (run by CloudWork).

    /** Pulls the inbox, applies each request once and reports each result. Throws CloudException to retry. */
    suspend fun runPull(): PullRun = pullGate.withLock {
        if (!hasIdentity()) return PullRun(0, 0, 0, 0, 0)
        registerIfNeeded()
        pushEnabledIfDirty()
        val requests = port.pullInbox()
        var applied = 0
        var replayed = 0
        var completed = 0
        var deferred = 0
        var failure: Throwable? = null
        for (request in requests) {
            val earlier = store.appliedResult(request.requestId)
            val result = if (earlier != null) {
                replayed++
                earlier
            } else {
                try {
                    inbox().apply(request).also {
                        store.recordApplied(request.requestId, it, clock())
                        applied++
                    }
                } catch (error: Throwable) {
                    // Left in the inbox: pulled and tried again later. Others still go ahead.
                    deferred++
                    failure = failure ?: error
                    continue
                }
            }
            try {
                port.completeRequest(request.requestId, result)
                completed++
                if (result is RequestResult.Done) store.noteSynced(result.reminder.uuid, result.reminder.version)
            } catch (error: CloudException) {
                if (error.code == CloudCallCode.NOT_FOUND) continue // the assistant was disconnected meanwhile
                failure = failure ?: error
            }
        }
        if (applied > 0 || replayed > 0) jobs.syncMirror()
        failure?.let { throw it }
        PullRun(requests.size, applied, replayed, completed, deferred)
    }

    suspend fun runMirror(): CloudMirror.Run? {
        if (!hasIdentity()) return null
        val agents = refreshAgentsQuietly()
        val run = mirror(port).sync(agents.map { it.agentId })
        if (run.goneAgents.isNotEmpty()) {
            val left = store.agents().filter { it.agentId !in run.goneAgents }
            store.setAgents(left)
            state.value = state.value.copy(agents = left)
        }
        return run
    }

    // Assistants screen.

    /** Connect another assistant: signs in on first use, then makes a pairing code. */
    suspend fun connectAssistant(): PairingCode? = screenCall {
        ensureIdentity()
        val code = port.createPairingCode()
        state.value = state.value.copy(pairing = code)
        code
    }

    fun clearPairing() {
        state.value = state.value.copy(pairing = null)
    }

    suspend fun refreshAgents(): List<CloudAgent>? = screenCall {
        if (!hasIdentity()) return@screenCall emptyList()
        loadAgents()
    }

    suspend fun disconnect(agentId: String): Boolean = screenCall {
        try {
            port.disconnectAgent(agentId)
        } catch (error: CloudException) {
            if (error.code != CloudCallCode.NOT_FOUND) throw error
        }
        val left = store.agents().filter { it.agentId != agentId }
        store.setAgents(left)
        state.value = state.value.copy(agents = left)
        true
    } ?: false

    /** The assistants on/off switch. Kept on the phone at once; the server hears it now or on the next pull. */
    suspend fun setAssistantsEnabled(enabled: Boolean) {
        if (!this.enabled) return
        store.setAssistantsEnabled(enabled, dirty = true)
        state.value = state.value.copy(assistantsEnabled = enabled)
        if (!hasIdentity()) {
            store.setAssistantsEnabled(enabled, dirty = false)
            return
        }
        screenCall { pushEnabledIfDirty() }
    }

    fun setAskBeforeRingThrough(ask: Boolean) {
        if (!enabled) return
        store.setAskBeforeRingThrough(ask)
        state.value = state.value.copy(askBeforeRingThrough = ask)
    }

    /** The approval card. The person decides, through ReminderCommands; the mirror sync follows from its listener. */
    suspend fun resolveApproval(id: Long, decision: ApprovalDecision): Boolean =
        commands().resolveApproval(Origin(Actor.USER), id, decision)

    // Purchases.

    /**
     * A Do Unlimited token Play reported (after a purchase, or on resume). The first purchase
     * makes a cloud identity; otherwise only a device that already has one asks the server.
     */
    suspend fun onPurchase(productId: String, token: String, justBought: Boolean, packageName: String): VerifyOutcome {
        if (!enabled) return VerifyOutcome.OFF
        if (!hasIdentity() && !justBought) return VerifyOutcome.NO_IDENTITY
        val now = clock()
        val cached = CloudEntitlement.fresh(store.serverEntitlement(), store.serverEntitlementAt(), now)
        if (!CloudEntitlement.shouldVerify(justBought, store.serverEntitlementAt(), now)) {
            if (cached == null) return VerifyOutcome.KEPT_LOCAL
            applyVerdict(cached, now)
            return VerifyOutcome.CACHED
        }
        return try {
            ensureIdentity()
            val verdict = port.verifyPurchase(PurchaseCheck(packageName = packageName, productId = productId, purchaseToken = token))
            store.setServerEntitlement(verdict, now)
            applyVerdict(verdict, now)
            VerifyOutcome.VERIFIED
        } catch (error: CloudException) {
            // Unreachable, unknown token or wrong product: Spec 10's local result stands (a fresh verdict still wins).
            if (cached != null) {
                applyVerdict(cached, now)
                VerifyOutcome.CACHED
            } else {
                VerifyOutcome.KEPT_LOCAL
            }
        }
    }

    private fun applyVerdict(verdict: VerifiedEntitlement, now: Long) {
        val local = entitlement.read()
        val next = CloudEntitlement.apply(local, verdict, now)
        if (next != local) {
            entitlement.write(next)
            entitlement.changed()
        }
    }

    // Identity and registration.

    /** FCM token refresh. */
    suspend fun onNewToken(token: String) {
        if (!hasIdentity()) return
        try {
            port.registerDevice(RegisterDevice(fcmToken = token, appVersion = appVersion))
            store.setRegisteredToken(token)
        } catch (error: CloudException) {
            store.setRegisteredToken(null)
        }
    }

    private suspend fun ensureIdentity(): CloudIdentity = identityGate.withLock {
        val identity = port.currentIdentity() ?: port.signIn()
        if (store.identityUid() != identity.uid) {
            store.setIdentityUid(identity.uid)
            store.setRegisteredToken(null)
        }
        state.value = state.value.copy(hasIdentity = true)
        registerIfNeeded()
        identity
    }

    /** registerDevice when the FCM token is new. No token (no Play services, the emulator project): skipped, pulls still work. */
    private suspend fun registerIfNeeded() {
        val token = try {
            port.messagingToken()
        } catch (error: CloudException) {
            null
        } ?: return
        if (token == store.registeredToken()) return
        port.registerDevice(RegisterDevice(fcmToken = token, appVersion = appVersion))
        store.setRegisteredToken(token)
    }

    private suspend fun pushEnabledIfDirty() {
        if (!store.enabledDirty()) return
        val wanted = store.assistantsEnabled()
        port.setAssistantsEnabled(wanted)
        if (store.assistantsEnabled() == wanted) store.setAssistantsEnabled(wanted, dirty = false)
    }

    private suspend fun loadAgents(): List<CloudAgent> {
        val agents = port.listAgents().sortedBy { it.createdAt ?: 0L }
        store.setAgents(agents)
        state.value = state.value.copy(agents = agents)
        return agents
    }

    private suspend fun refreshAgentsQuietly(): List<CloudAgent> = try {
        loadAgents()
    } catch (error: CloudException) {
        store.agents()
    }

    /** Runs a screen action: busy while it runs, failed when it throws a CloudException. */
    private suspend fun <T> screenCall(block: suspend () -> T): T? {
        if (!enabled) return null
        state.value = state.value.copy(busy = true)
        return try {
            block().also { state.value = state.value.copy(busy = false, failed = false) }
        } catch (error: CloudException) {
            state.value = state.value.copy(busy = false, failed = true)
            null
        }
    }

    /** For CLOUD_DUMP. No titles. */
    fun dump(): String {
        if (!enabled) return "enabled=false"
        val ui = state.value
        return "enabled=true emulator=${config.usesEmulator} project=${config.projectId} identity=${store.identityUid() != null} " +
            "tokenRegistered=${store.registeredToken() != null} assistantsEnabled=${store.assistantsEnabled()} " +
            "ask=${store.askBeforeRingThrough()} agents=${store.agents().joinToString(",") { it.agentId }.ifEmpty { "none" }} " +
            "applied=${store.appliedCount()} mirrorCursor=${store.mirrorCursor()} pairing=${ui.pairing != null} mcp=${config.mcpUrl()}"
    }

    companion object {
        const val PUSH_KIND = "kind"
        const val PUSH_INBOX = "inbox"
    }
}
