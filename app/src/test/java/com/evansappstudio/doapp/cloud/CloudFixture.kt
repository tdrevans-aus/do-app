package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Fixture
import com.evansappstudio.doapp.alarm.schedulerFixture
import com.evansappstudio.doapp.billing.EntitlementRecord
import com.evansappstudio.doapp.billing.MemoryPrefs
import com.evansappstudio.doapp.capture.AgentFunctions
import com.evansappstudio.doapp.capture.PERTH
import java.io.File

internal const val AGENT_A = "ag_0123456789abcdef"
internal const val AGENT_B = "ag_fedcba9876543210"

/** A CloudPort that records every call. Set [inbox] and the fail flags per test. */
internal class FakeCloudPort : CloudPort {
    var identity: CloudIdentity? = null
    var token: String? = "fcm-token-1"
    val inbox = ArrayList<InboxRequest>()
    val completed = ArrayList<Pair<String, RequestResult>>()
    val synced = ArrayList<MirrorBatch>()
    val registered = ArrayList<RegisterDevice>()
    val enabledCalls = ArrayList<Boolean>()
    val verifyCalls = ArrayList<PurchaseCheck>()
    val calls = ArrayList<String>()
    /** The assistants Do Cloud says are paired. Both test ids by default; connect() narrows it. */
    var agents = mutableListOf(
        CloudAgent(AGENT_A, "Agent A", null, 1L, null, null),
        CloudAgent(AGENT_B, "Agent B", null, 2L, null, null),
    )
    var failComplete: CloudCallCode? = null
    var failSync: Map<String, CloudCallCode> = emptyMap()
    var failVerify: CloudCallCode? = null
    var failPull: CloudCallCode? = null
    var failList: CloudCallCode? = null
    var verdict = VerifiedEntitlement("active", true, null, true, "do_unlimited", 1L)
    var code = PairingCode("ABCD2345", 1_700_000_600_000L)

    private fun note(name: String) {
        calls += name
    }

    override fun currentIdentity(): CloudIdentity? {
        note("currentIdentity")
        return identity
    }

    override suspend fun signIn(): CloudIdentity {
        note("signIn")
        return identity ?: CloudIdentity("uid-1").also { identity = it }
    }

    override suspend fun messagingToken(): String? {
        note("messagingToken")
        return token
    }

    override suspend fun registerDevice(request: RegisterDevice) {
        note("registerDevice")
        registered += request
    }

    override suspend fun setAssistantsEnabled(enabled: Boolean): Boolean {
        note("setAssistantsEnabled")
        enabledCalls += enabled
        return enabled
    }

    override suspend fun createPairingCode(): PairingCode {
        note("createPairingCode")
        return code
    }

    override suspend fun listAgents(): List<CloudAgent> {
        note("listAgents")
        failList?.let { throw CloudException(it, "list failed") }
        return agents.toList()
    }

    override suspend fun disconnectAgent(agentId: String) {
        note("disconnectAgent")
        if (agents.none { it.agentId == agentId }) throw CloudException(CloudCallCode.NOT_FOUND, "No such assistant")
        agents.removeAll { it.agentId == agentId }
    }

    override suspend fun pullInbox(): List<InboxRequest> {
        note("pullInbox")
        failPull?.let { throw CloudException(it, "pull failed") }
        val done = completed.map { it.first }.toSet()
        return inbox.filter { it.requestId !in done }
    }

    override suspend fun completeRequest(requestId: String, result: RequestResult): Boolean {
        note("completeRequest")
        failComplete?.let { throw CloudException(it, "complete failed") }
        val duplicate = completed.any { it.first == requestId }
        if (!duplicate) completed += requestId to result
        return duplicate
    }

    override suspend fun syncAgentMirror(batch: MirrorBatch): MirrorAck {
        note("syncAgentMirror")
        failSync[batch.agentId]?.let { throw CloudException(it, "sync failed") }
        synced += batch
        return MirrorAck(batch.reminders.size, batch.events.size, 0)
    }

    override suspend fun verifyPurchase(request: PurchaseCheck): VerifiedEntitlement {
        note("verifyPurchase")
        verifyCalls += request
        failVerify?.let { throw CloudException(it, "verify failed") }
        return verdict
    }
}

internal class RecordingJobs : CloudJobs {
    val pulls = ArrayList<Boolean>()
    var mirrors = 0

    override fun pull(expedited: Boolean) {
        pulls += expedited
    }

    override fun syncMirror() {
        mirrors++
    }
}

internal class MemoryEntitlement(var record: EntitlementRecord = EntitlementRecord.EMPTY) : DoCloud.EntitlementAccess {
    var changes = 0

    override fun read(): EntitlementRecord = record

    override fun write(record: EntitlementRecord) {
        this.record = record
    }

    override fun changed() {
        changes++
    }
}

/** The Scheduler fixture with Do Cloud on top, as AppContainer wires it. */
internal class CloudFixture(
    val config: CloudConfig = CloudConfig("demo-do-cloud", "demo-api-key", "1:000000000000:android:0000000000000000", "10.0.2.2"),
    val fx: Fixture = schedulerFixture(),
    /** A real port (the emulator end-to-end test) in place of [port]. */
    private val real: CloudPort? = null,
) {
    val port = FakeCloudPort()
    val jobs = RecordingJobs()
    val prefs = MemoryPrefs()
    val store = CloudStore(prefs)
    val entitlement = MemoryEntitlement()
    var portBuilt = 0

    init {
        fx.zone = PERTH
    }

    val inbox = CloudInbox(
        agent = {
            AgentFunctions(
                enabled = store.assistantsEnabled(),
                commands = fx.commands,
                active = { fx.dao.active() },
                byId = { id -> fx.dao.getById(id) },
                defaultNagMinutes = { 5 },
                clock = { fx.clockMillis },
                zone = { fx.zone },
            )
        },
        commands = fx.commands,
        byId = { id -> fx.dao.getById(id) },
        byUuid = { uuid -> fx.dao.getByUuid(uuid) },
        askBeforeRingThrough = { store.askBeforeRingThrough() },
    )

    val cloud = DoCloud(
        config = config,
        portFactory = {
            portBuilt++
            real ?: port
        },
        store = store,
        inbox = { inbox },
        mirror = { p ->
            CloudMirror(
                port = p,
                store = store,
                reminders = { fx.dao.allIncludingDeleted() },
                eventsAfter = { after, limit -> fx.events.after(after, limit) },
                maxEventId = { fx.events.maxId() },
            )
        },
        commands = { fx.commands },
        jobs = jobs,
        entitlement = entitlement,
        appVersion = "1.0",
        clock = { fx.clockMillis },
    )

    val later: Long get() = fx.clockMillis + 60 * 60_000L

    /** A device that already paired once. */
    fun signedIn(): CloudFixture {
        port.identity = CloudIdentity("uid-1")
        store.setIdentityUid("uid-1")
        return this
    }

    fun connect(vararg ids: String): CloudFixture {
        port.agents = ids.map { CloudAgent(it, "Agent $it", null, 1L, null, null) }.toMutableList()
        store.setAgents(port.agents)
        return this
    }

    private var nextRequest = 0

    fun create(agentId: String, text: String, dueAt: Long? = later, alertLevel: String? = "normal", repeat: String? = null): InboxRequest =
        request(agentId, InboxRequest.KIND_CREATE, InboxArgs.Create(text, dueAt, repeat, alertLevel))

    fun complete(agentId: String, uuid: String?): InboxRequest =
        request(agentId, InboxRequest.KIND_COMPLETE, InboxArgs.Complete(uuid))

    fun request(agentId: String, kind: String, args: InboxArgs): InboxRequest {
        nextRequest++
        val id = "rq_" + nextRequest.toString(16).padStart(24, '0')
        return InboxRequest(id, agentId, "Agent", kind, args, fx.clockMillis, fx.clockMillis + 86_400_000L).also { port.inbox += it }
    }

    fun resultOf(request: InboxRequest): RequestResult = port.completed.single { it.first == request.requestId }.second
}

internal fun projectFile(path: String): File =
    listOf(File(path), File("app/$path")).firstOrNull { it.exists() } ?: error("missing $path from ${File(".").absolutePath}")

internal fun repoFile(path: String): File =
    listOf(File("../$path"), File(path)).firstOrNull { it.exists() } ?: error("missing $path from ${File(".").absolutePath}")
