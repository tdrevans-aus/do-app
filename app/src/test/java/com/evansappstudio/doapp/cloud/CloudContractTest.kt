package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.EventType
import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.encodeJson
import com.evansappstudio.doapp.backup.parseJson
import com.evansappstudio.doapp.capture.AgentError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 13: the JSON the client produces and parses matches cloud/CONTRACT.md.
 * The examples are copied into src/test/resources/cloud; the first test checks
 * the copies still match the contract, so a contract change fails here.
 */
class CloudContractTest {
    private fun fixture(name: String): JsonValue {
        val stream = javaClass.classLoader!!.getResourceAsStream("cloud/$name") ?: error("missing fixture $name")
        return parseJson(stream.bufferedReader().use { it.readText() })
    }

    private val contract: String by lazy { repoFile("cloud/CONTRACT.md").readText() }

    /** Every ```json block in CONTRACT.md that is plain JSON (the ones with <Reminder> placeholders are not). */
    private val contractExamples: List<JsonValue> by lazy {
        Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(contract).mapNotNull { match ->
            try {
                parseJson(match.groupValues[1])
            } catch (error: IllegalArgumentException) {
                null
            }
        }.toList()
    }

    @Test
    fun theFixturesAreTheContractsOwnExamples() {
        for (name in listOf("push.json", "pull-inbox.json", "reminder.json", "complete-failed.json", "mirror-event.json", "verify-purchase-result.json")) {
            assertTrue("$name is not an example in CONTRACT.md", fixture(name) in contractExamples)
        }
        assertTrue(contract.contains("Firebase.functions(\"australia-southeast1\")"))
        assertEquals("australia-southeast1", CloudConfig.REGION)
    }

    @Test
    fun pullInboxExampleParses() {
        val requests = CloudJson.inbox(fixture("pull-inbox.json"))
        assertEquals(
            listOf(
                InboxRequest(
                    requestId = "rq_0123456789abcdef01234567",
                    agentId = "ag_0123456789abcdef",
                    clientName = "Claude",
                    kind = "create_reminder",
                    args = InboxArgs.Create(text = "call Mum tomorrow 6pm", dueAt = null, repeat = null, alertLevel = "normal"),
                    createdAt = 1764300000000,
                    expiresAt = 1764386400000,
                ),
            ),
            requests,
        )
        assertTrue(CloudAgent.isAgentId(requests.single().agentId))
        val complete = CloudJson.inboxRequest(
            parseJson("""{"requestId":"rq_1","agentId":"ag_0123456789abcdef","kind":"complete_reminder","args":{"reminderUuid":"9b2f6c1e-3a0d-4c55-9d8e-6f1a2b3c4d5e"},"createdAt":1,"expiresAt":2}"""),
        )!!
        assertEquals(InboxArgs.Complete("9b2f6c1e-3a0d-4c55-9d8e-6f1a2b3c4d5e"), complete.args)
        val unknown = CloudJson.inboxRequest(parseJson("""{"requestId":"rq_2","agentId":"ag_0123456789abcdef","kind":"later_thing","args":{}}"""))!!
        assertEquals(InboxArgs.Unknown, unknown.args)
        assertNull(CloudJson.inboxRequest(parseJson("""{"agentId":"ag_0123456789abcdef"}""")))
        assertEquals(emptyList<InboxRequest>(), CloudJson.inbox(parseJson("""{"requests":[]}""")))
    }

    @Test
    fun theReminderIsWrittenExactlyAsTheExample() {
        val example = WireReminder(
            uuid = "9b2f6c1e-3a0d-4c55-9d8e-6f1a2b3c4d5e",
            title = "Call Mum",
            dueAt = 1764324000000,
            status = "active",
            alertLevel = "STANDARD",
            requestedAlertLevel = "CRITICAL",
            approval = "PENDING",
            repeat = "DAILY",
            version = 3,
            updatedAt = 1764300000000,
        )
        assertEquals(fixture("reminder.json"), CloudJson.reminder(example))
        assertEquals(example, CloudJson.wireReminder(fixture("reminder.json")))
        val done = CloudJson.completeRequest("rq_0123456789abcdef01234567", RequestResult.Done(example))
        assertEquals(
            JsonValue.Obj(
                mapOf(
                    "requestId" to JsonValue.Str("rq_0123456789abcdef01234567"),
                    "result" to JsonValue.Obj(mapOf("status" to JsonValue.Str("done"), "reminder" to fixture("reminder.json"))),
                ),
            ),
            done,
        )
        // null fields are sent as null, as in the contract's table.
        val plain = CloudJson.reminder(example.copy(requestedAlertLevel = null, repeat = null)) as JsonValue.Obj
        assertEquals(JsonValue.Null, plain.fields["requestedAlertLevel"])
        assertEquals(JsonValue.Null, plain.fields["repeat"])
    }

    @Test
    fun theFailureIsWrittenExactlyAsTheExample() {
        assertEquals(
            fixture("complete-failed.json"),
            CloudJson.result(RequestResult.Failed(PhoneErrorCode.LIMIT_REACHED, "optional, at most 300 characters")),
        )
        val long = CloudJson.result(RequestResult.Failed(PhoneErrorCode.NOT_FOUND, "x".repeat(400))) as JsonValue.Obj
        val message = ((long.fields["error"] as JsonValue.Obj).fields["message"] as JsonValue.Str).value
        assertEquals(PhoneErrorCode.MAX_MESSAGE, message.length)
        // No message at all is allowed ("optional").
        assertEquals(
            parseJson("""{"status":"failed","error":{"code":"disabled"}}"""),
            CloudJson.result(RequestResult.Failed(PhoneErrorCode.DISABLED, "  ")),
        )
    }

    @Test
    fun errorCodesMapOneToOneAsTheContractTableSays() {
        val table = Regex("""\| `(\w+)` \| `(\w+)` \|""").findAll(contract).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(mapOf("LimitReached" to "limit_reached", "NotFound" to "not_found", "InvalidArgument" to "invalid_argument", "Disabled" to "disabled"), table)
        val mapped = mapOf(
            "LimitReached" to CloudInbox.failure(AgentError.LimitReached(5)).code.wire,
            "NotFound" to CloudInbox.failure(AgentError.NotFound(3)).code.wire,
            "InvalidArgument" to CloudInbox.failure(AgentError.InvalidArgument("bad")).code.wire,
            "Disabled" to CloudInbox.failure(AgentError.Disabled()).code.wire,
        )
        assertEquals(table, mapped)
        // The server accepts exactly these from the phone.
        val server = repoFile("cloud/functions/src/core/errors.ts").readText()
        val accepted = Regex("""PHONE_ERROR_CODES = \[(.*?)]""").find(server)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.toSet()
        assertEquals(accepted, PhoneErrorCode.entries.map { it.wire }.toSet())
        // The adapter's limit message goes through as it is.
        assertEquals(AgentError.LimitReached(5).message, CloudInbox.failure(AgentError.LimitReached(5)).message)
    }

    @Test
    fun mirrorEventsAndBatchesMatchTheContract() {
        val event = MirrorEvent(id = 4711, reminderUuid = "9b2f6c1e-...", type = "FIRED", at = 1764324000000)
        assertEquals(fixture("mirror-event.json"), CloudJson.event(event))
        val batch = CloudJson.mirror(MirrorBatch(AGENT_A, emptyList(), listOf(event))) as JsonValue.Obj
        assertEquals(setOf("agentId", "reminders", "events"), batch.fields.keys)
        // Every EventType the phone can send is one the server accepts.
        val server = repoFile("cloud/functions/src/agent/events.ts").readText()
        val accepted = Regex("""PHONE_EVENT_TYPES = new Set\(\[(.*?)]\)""", RegexOption.DOT_MATCHES_ALL).find(server)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        assertTrue(accepted.containsAll(EventType.entries.map { it.name }))
        val listed = Regex("""`([A-Z_]+)`""").findAll(contract.substringAfter("`type` is an `EventType` name").substringBefore("Never send")).map { it.groupValues[1] }.toSet()
        assertEquals(EventType.entries.map { it.name }.toSet(), listed)
        assertEquals(MirrorAck(2, 3, 1), CloudJson.mirrorAck(parseJson("""{"ok":true,"reminders":2,"events":3,"skipped":1}""")))
        assertTrue(contract.contains("`reminders`: up to ${MirrorBatch.MAX_REMINDERS}"))
        assertTrue(contract.contains("`events`: up to ${MirrorBatch.MAX_EVENTS}"))
    }

    @Test
    fun theOtherCallablesMatchTheContract() {
        assertEquals(
            parseJson("""{"fcmToken":"tok","platform":"android","appVersion":"1.0"}"""),
            CloudJson.registerDevice(RegisterDevice("tok", appVersion = "1.0")),
        )
        assertEquals(parseJson("""{"enabled":false}"""), CloudJson.setAssistantsEnabled(false))
        assertFalse(CloudJson.enabled(parseJson("""{"ok":true,"enabled":false}""")))
        assertEquals(parseJson("""{"agentId":"ag_0123456789abcdef"}"""), CloudJson.disconnectAgent("ag_0123456789abcdef"))
        val code = CloudJson.pairingCode(parseJson("""{"code":"ABCD2345","expiresAt":1764300600000}"""))
        assertEquals(PairingCode("ABCD2345", 1764300600000), code)
        assertTrue(code.code.all { it in PairingCode.ALPHABET })
        assertTrue(contract.contains(PairingCode.ALPHABET))
        assertEquals("ABCD 2345", code.display())
        assertEquals(600L, code.secondsLeft(1764300000000))
        assertEquals(0L, code.secondsLeft(1764300700000))
        assertTrue(CloudJson.duplicate(parseJson("""{"ok":true,"duplicate":true}""")))
        assertFalse(CloudJson.duplicate(parseJson("""{"ok":true,"duplicate":false}""")))
        assertEquals(
            parseJson("""{"platform":"play","packageName":"com.evansappstudio.doapp","productId":"do_unlimited","purchaseToken":"tok"}"""),
            CloudJson.verifyPurchase(PurchaseCheck("com.evansappstudio.doapp", "do_unlimited", "tok")),
        )
        assertEquals(
            VerifiedEntitlement("active", true, 1766900000000, true, "do_unlimited", 1764300000000),
            CloudJson.entitlement(fixture("verify-purchase-result.json")),
        )
        assertEquals("inbox", ((fixture("push.json") as JsonValue.Obj).fields[DoCloud.PUSH_KIND] as JsonValue.Str).value)
        assertEquals(DoCloud.PUSH_INBOX, "inbox")
    }

    @Test
    fun malformedAnswersAreInternalErrors() {
        for (bad in listOf("""{}""", """{"code":"ABCD2345"}""")) {
            try {
                CloudJson.pairingCode(parseJson(bad))
                error("expected a failure for $bad")
            } catch (error: CloudException) {
                assertEquals(CloudCallCode.INTERNAL, error.code)
            }
        }
    }

    @Test
    fun theAgentsAreReadFromFirestoreDocuments() {
        val agents = CloudJson.agentsFromFirestore(fixture("firestore-agents.json"))
        assertEquals(
            listOf(
                CloudAgent(AGENT_A, "Claude", "https://claude.ai/oauth/client.json", 1764300000000, null, null),
                CloudAgent(AGENT_B, "ChatGPT", null, 1764300100000, 1764300200000, 1764300300000),
            ),
            agents,
        )
        assertTrue(agents[1].revoked)
        assertEquals(emptyList<CloudAgent>(), CloudJson.agentsFromFirestore(parseJson("{}")))
        // The contract's field table is what is read.
        for (field in listOf("clientName", "clientId", "createdAt", "lastUsedAt", "grantRevokedAt")) {
            assertTrue(field, contract.contains("| `$field` |"))
        }
    }

    @Test
    fun plainValuesRoundTripAsTheCallableSdkSendsThem() {
        val sent = CloudJson.completeRequest("rq_0123456789abcdef01234567", RequestResult.Done(CloudJson.wireReminder(fixture("reminder.json"))!!))
        val plain = CloudJson.toPlain(sent)
        assertTrue(plain is Map<*, *>)
        assertEquals(sent, CloudJson.fromPlain(plain))
        // The SDK hands numbers back as Integer, Long or Double.
        val back = CloudJson.fromPlain(mapOf("a" to 1, "b" to 2L, "c" to 1.764324E12, "d" to 0.5, "e" to listOf(true, null)))
        assertEquals(
            parseJson("""{"a":1,"b":2,"c":1764324000000,"d":0.5,"e":[true,null]}""", allowFractions = true),
            back,
        )
        assertEquals(CloudCallCode.INVALID_ARGUMENT, CloudCallCode.fromWire("INVALID_ARGUMENT"))
        assertEquals(CloudCallCode.UNAVAILABLE, CloudCallCode.fromWire("unavailable"))
        assertEquals(CloudCallCode.OTHER, CloudCallCode.fromWire("DEADLINE_EXCEEDED"))
        assertTrue(encodeJson(sent).contains("\"status\": \"done\""))
    }
}
