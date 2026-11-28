package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.encodeJson
import com.evansappstudio.doapp.backup.parseJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Spec 13 end to end on the JVM: DoCloud, CloudInbox and CloudMirror against the
 * real callables in the Firebase Emulator Suite, over HTTP, with the callable
 * protocol FirebaseCloud's SDK speaks. Runs only when DO_CLOUD_E2E=1 (see the
 * Build notes for the command); otherwise it is skipped.
 *
 * The assistant side is seeded straight into the Firestore emulator as the
 * server would write it (an agent record and inbox requests), with the
 * emulator's owner token; the OAuth and MCP side is the server's own AT-2 and AT-3.
 */
class CloudEmulatorE2ETest {
    private val project = "demo-do-cloud"
    private val host = System.getenv("DO_CLOUD_EMULATOR_HOST") ?: "127.0.0.1"
    private val firestore = "http://$host:${CloudConfig.FIRESTORE_EMULATOR_PORT}/v1/projects/$project/databases/(default)/documents"

    @Test
    fun thePhoneSideAgainstTheRealCallables() = runBlocking {
        assumeTrue("set DO_CLOUD_E2E=1 inside firebase emulators:exec", System.getenv("DO_CLOUD_E2E") == "1")
        val port = HttpCloudPort(host, project)
        val cx = CloudFixture(real = port)
        cx.fx.clockMillis = System.currentTimeMillis()

        // Pairing: anonymous sign-in, registerDevice, createPairingCode.
        val code = cx.cloud.connectAssistant()
        assertNotNull("connectAssistant failed: ${cx.cloud.ui.value}", code)
        assertEquals(8, code!!.code.length)
        assertTrue(code.code.all { it in PairingCode.ALPHABET })
        assertTrue(code.expiresAt > System.currentTimeMillis() + 9 * 60_000L)
        val uid = cx.store.identityUid()!!
        assertEquals("e2e-token", field(get("devices/$uid"), "fcmToken"))

        // Two assistants, as the authorise page writes them; read back through the rules with the device token.
        val a = "ag_" + hex(16)
        val b = "ag_" + hex(16)
        for ((id, name) in listOf(a to "Claude", b to "ChatGPT")) {
            create("devices/$uid/agents", id, mapOf("clientName" to name, "clientId" to "e2e", "createdAt" to System.currentTimeMillis(), "lastUsedAt" to null, "eventSeq" to 0L))
        }
        val agents = cx.cloud.refreshAgents()!!
        assertEquals(setOf(a, b), agents.map { it.agentId }.toSet())
        assertEquals("Claude", agents.first { it.agentId == a }.clientName)

        // Requests in the inbox, as sendToPhone writes them.
        val normal = inbox(uid, a, "create_reminder", mapOf("text" to "E2E normal", "dueAt" to System.currentTimeMillis() + 3_600_000L, "repeat" to null, "alertLevel" to "normal"))
        val alarm = inbox(uid, a, "create_reminder", mapOf("text" to "E2E alarm in 2 hours", "dueAt" to null, "repeat" to null, "alertLevel" to "alarm"))
        val stranger = inbox(uid, b, "complete_reminder", mapOf("reminderUuid" to UUID.randomUUID().toString()))
        val run = cx.cloud.runPull()
        assertEquals(PullRun(3, 3, 0, 3, 0), run)
        assertEquals("done", field(get("devices/$uid/inbox/$normal"), "status"))
        assertEquals("done", field(get("devices/$uid/inbox/$alarm"), "status"))
        assertEquals("failed", field(get("devices/$uid/inbox/$stranger"), "status"))
        val normalRow = cx.fx.dao.all().single { it.title == "E2E normal" }
        val alarmRow = cx.fx.dao.all().single { it.title.startsWith("E2E alarm") }
        assertEquals(a, normalRow.sourceId)
        // The server took the result into the assistant's mirror; the held one reads as pending.
        val held = get("devices/$uid/agents/$a/reminders/${alarmRow.uuid}")
        assertEquals("pending", field(held, "approval"))
        assertEquals("alarm", field(held, "requestedAlertLevel"))
        assertEquals("normal", field(held, "alertLevel"))
        // Nothing left to pull.
        assertEquals(0, cx.cloud.runPull().pulled)

        // Done on the phone reaches the mirror (AT-14's last step, minus the assistant).
        cx.fx.commands.markDone(Origin(Actor.USER), normalRow.id)
        val mirror = cx.cloud.runMirror()!!
        assertTrue(mirror.batches >= 1)
        val done = get("devices/$uid/agents/$a/reminders/${normalRow.uuid}")
        assertEquals("done", field(done, "status"))
        assertEquals(cx.fx.dao.getById(normalRow.id)!!.version.toString(), field(done, "version"))
        // B never received A's reminders.
        assertNull(getOrNull("devices/$uid/agents/$b/reminders/${normalRow.uuid}"))

        // The switch and Disconnect.
        cx.cloud.setAssistantsEnabled(false)
        assertEquals("false", field(get("devices/$uid"), "assistantsEnabled"))
        assertTrue(cx.cloud.disconnect(b))
        assertNull(getOrNull("devices/$uid/agents/$b"))
        assertEquals(listOf(a), cx.cloud.refreshAgents()!!.map { it.agentId })

        // A token Play does not know: not-found, so the local Spec 10 result stands.
        assertEquals(VerifyOutcome.KEPT_LOCAL, cx.cloud.onPurchase("do_unlimited", "unknown-token", justBought = true, packageName = "com.evansappstudio.doapp"))
    }

    private fun hex(n: Int) = UUID.randomUUID().toString().replace("-", "").take(n)

    private fun inbox(uid: String, agentId: String, kind: String, args: Map<String, Any?>): String {
        val id = "rq_" + hex(24).padEnd(24, '0')
        val now = System.currentTimeMillis()
        create(
            "devices/$uid/inbox",
            id,
            mapOf(
                "agentId" to agentId, "clientName" to "E2E", "kind" to kind, "args" to args, "status" to "pending",
                "createdAt" to now, "expiresAt" to now + 86_400_000L, "finishedAt" to null, "result" to null,
            ),
        )
        return id
    }

    private fun typed(value: Any?): JsonValue = when (value) {
        null -> obj("nullValue" to JsonValue.Null)
        is String -> obj("stringValue" to JsonValue.Str(value))
        is Boolean -> obj("booleanValue" to JsonValue.Bool(value))
        is Int, is Long -> obj("integerValue" to JsonValue.Str(value.toString()))
        is Map<*, *> -> obj("mapValue" to obj("fields" to JsonValue.Obj(value.entries.associate { it.key.toString() to typed(it.value) })))
        else -> error("unsupported $value")
    }

    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))

    private fun create(collection: String, id: String, fields: Map<String, Any?>) {
        val body = encodeJson(obj("fields" to JsonValue.Obj(fields.mapValues { typed(it.value) })))
        val (status, text) = http("POST", "$firestore/$collection?documentId=$id", body, "owner")
        check(status in 200..299) { "create $collection/$id failed $status $text" }
    }

    private fun getOrNull(path: String): JsonValue? {
        val (status, text) = http("GET", "$firestore/$path", null, "owner")
        if (status == 404) return null
        check(status in 200..299) { "get $path failed $status $text" }
        return parseJson(text, allowFractions = true)
    }

    private fun get(path: String): JsonValue = getOrNull(path) ?: error("missing $path")

    /** A top-level field of a Firestore REST document, as text. */
    private fun field(doc: JsonValue, name: String): String? {
        val typed = ((doc as JsonValue.Obj).fields["fields"] as? JsonValue.Obj)?.fields?.get(name) as? JsonValue.Obj ?: return null
        return when (val v = typed.fields.values.first()) {
            is JsonValue.Str -> v.value
            is JsonValue.Bool -> v.value.toString()
            is JsonValue.Num -> v.value.toString()
            JsonValue.Null -> null
            else -> encodeJson(v)
        }
    }
}

/**
 * A JVM CloudPort for the emulator: the Auth emulator's signUp, the callable
 * protocol ({"data": ...} in, {"result": ...} or {"error": {status, message}} out)
 * and Firestore REST for the agents, the same calls FirebaseCloud makes through the SDK.
 */
internal class HttpCloudPort(private val host: String, private val project: String) : CloudPort {
    private var uid: String? = null
    private var idToken: String? = null

    override fun currentIdentity(): CloudIdentity? = uid?.let(::CloudIdentity)

    override suspend fun signIn(): CloudIdentity {
        uid?.let { return CloudIdentity(it) }
        val (status, text) = http(
            "POST",
            "http://$host:${CloudConfig.AUTH_EMULATOR_PORT}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-api-key",
            """{"returnSecureToken":true}""",
            null,
        )
        if (status !in 200..299) throw CloudException(CloudCallCode.UNAUTHENTICATED, "signUp $status")
        val body = parseJson(text, allowFractions = true) as JsonValue.Obj
        idToken = (body.fields["idToken"] as JsonValue.Str).value
        uid = (body.fields["localId"] as JsonValue.Str).value
        return CloudIdentity(uid!!)
    }

    override suspend fun messagingToken(): String = "e2e-token"

    private fun call(name: String, data: JsonValue?): JsonValue {
        val token = idToken ?: throw CloudException(CloudCallCode.UNAUTHENTICATED, "no identity")
        val body = encodeJson(JsonValue.Obj(mapOf("data" to (data ?: JsonValue.Null))))
        val url = "http://$host:${CloudConfig.FUNCTIONS_EMULATOR_PORT}/$project/${CloudConfig.REGION}/$name"
        val (status, text) = http("POST", url, body, token)
        val answer = try {
            parseJson(text, allowFractions = true) as? JsonValue.Obj
        } catch (error: IllegalArgumentException) {
            null
        } ?: throw CloudException(CloudCallCode.INTERNAL, "$name answered $status")
        val error = answer.fields["error"] as? JsonValue.Obj
        if (error != null || status !in 200..299) {
            val code = (error?.fields?.get("status") as? JsonValue.Str)?.value
            throw CloudException(CloudCallCode.fromWire(code), (error?.fields?.get("message") as? JsonValue.Str)?.value ?: "$status")
        }
        return answer.fields["result"] ?: JsonValue.Null
    }

    override suspend fun registerDevice(request: RegisterDevice) {
        call("registerDevice", CloudJson.registerDevice(request))
    }

    override suspend fun setAssistantsEnabled(enabled: Boolean): Boolean =
        CloudJson.enabled(call("setAssistantsEnabled", CloudJson.setAssistantsEnabled(enabled)))

    override suspend fun createPairingCode(): PairingCode = CloudJson.pairingCode(call("createPairingCode", null))

    override suspend fun listAgents(): List<CloudAgent> {
        val url = "http://$host:${CloudConfig.FIRESTORE_EMULATOR_PORT}/v1/projects/$project/databases/(default)/documents/devices/$uid/agents?pageSize=100"
        val (status, text) = http("GET", url, null, idToken)
        if (status !in 200..299) throw CloudException(CloudCallCode.UNAUTHENTICATED, "agents $status $text")
        return CloudJson.agentsFromFirestore(parseJson(text, allowFractions = true))
    }

    override suspend fun disconnectAgent(agentId: String) {
        call("disconnectAgent", CloudJson.disconnectAgent(agentId))
    }

    override suspend fun pullInbox(): List<InboxRequest> = CloudJson.inbox(call("pullInbox", null))

    override suspend fun completeRequest(requestId: String, result: RequestResult): Boolean =
        CloudJson.duplicate(call("completeRequest", CloudJson.completeRequest(requestId, result)))

    override suspend fun syncAgentMirror(batch: MirrorBatch): MirrorAck =
        CloudJson.mirrorAck(call("syncAgentMirror", CloudJson.mirror(batch)))

    override suspend fun verifyPurchase(request: PurchaseCheck): VerifiedEntitlement =
        CloudJson.entitlement(call("verifyPurchase", CloudJson.verifyPurchase(request)))
}

private fun http(method: String, url: String, body: String?, bearer: String?): Pair<Int, String> {
    val connection = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Content-Type", "application/json")
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
    } finally {
        connection.disconnect()
    }
}
