package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.backup.JsonValue

// Spec 13 pure JSON for the Do Cloud callables, exactly as cloud/CONTRACT.md
// shows it. No Android or Firebase imports (CorePurityTest). FirebaseCloud turns
// these JsonValues into the plain maps and lists the Functions SDK sends
// ([toPlain], [fromPlain]); the contract tests compare them with the examples.

internal object CloudJson {
    // Requests the phone sends.

    fun registerDevice(request: RegisterDevice): JsonValue = obj(
        "fcmToken" to str(request.fcmToken),
        "platform" to str(request.platform),
        "appVersion" to (request.appVersion?.let { str(it.take(MAX_APP_VERSION)) } ?: JsonValue.Null),
    )

    fun setAssistantsEnabled(enabled: Boolean): JsonValue = obj("enabled" to JsonValue.Bool(enabled))

    fun disconnectAgent(agentId: String): JsonValue = obj("agentId" to str(agentId))

    fun completeRequest(requestId: String, result: RequestResult): JsonValue =
        obj("requestId" to str(requestId), "result" to result(result))

    fun result(result: RequestResult): JsonValue = when (result) {
        is RequestResult.Done -> obj("status" to str("done"), "reminder" to reminder(result.reminder))
        is RequestResult.Failed -> {
            val message = result.message?.trim()?.take(PhoneErrorCode.MAX_MESSAGE)?.takeIf { it.isNotEmpty() }
            val error = if (message == null) {
                obj("code" to str(result.code.wire))
            } else {
                obj("code" to str(result.code.wire), "message" to str(message))
            }
            obj("status" to str("failed"), "error" to error)
        }
    }

    fun reminder(r: WireReminder): JsonValue = obj(
        "uuid" to str(r.uuid),
        "title" to str(r.title),
        "dueAt" to JsonValue.Num(r.dueAt),
        "status" to str(r.status),
        "alertLevel" to str(r.alertLevel),
        "requestedAlertLevel" to (r.requestedAlertLevel?.let(::str) ?: JsonValue.Null),
        "approval" to str(r.approval),
        "repeat" to (r.repeat?.let(::str) ?: JsonValue.Null),
        "version" to JsonValue.Num(r.version.toLong()),
        "updatedAt" to JsonValue.Num(r.updatedAt),
    )

    fun mirror(batch: MirrorBatch): JsonValue = obj(
        "agentId" to str(batch.agentId),
        "reminders" to JsonValue.Arr(batch.reminders.map(::reminder)),
        "events" to JsonValue.Arr(batch.events.map(::event)),
    )

    fun event(e: MirrorEvent): JsonValue = obj(
        "id" to JsonValue.Num(e.id),
        "reminderUuid" to str(e.reminderUuid),
        "type" to str(e.type),
        "at" to JsonValue.Num(e.at),
    )

    fun verifyPurchase(check: PurchaseCheck): JsonValue = obj(
        "platform" to str(check.platform),
        "packageName" to str(check.packageName),
        "productId" to str(check.productId),
        "purchaseToken" to str(check.purchaseToken),
    )

    // Answers the phone reads. A malformed answer is a CloudException(INTERNAL).

    fun pairingCode(value: JsonValue): PairingCode = PairingCode(
        code = string(value, "code") ?: bad("code"),
        expiresAt = long(value, "expiresAt") ?: bad("expiresAt"),
    )

    fun enabled(value: JsonValue): Boolean = (value.at("enabled") as? JsonValue.Bool)?.value ?: bad("enabled")

    fun inbox(value: JsonValue): List<InboxRequest> {
        val list = value.at("requests") as? JsonValue.Arr ?: bad("requests")
        return list.items.mapNotNull(::inboxRequest)
    }

    /** One request, or null when it is not readable at all (no id). The phone answers what it can. */
    fun inboxRequest(value: JsonValue): InboxRequest? {
        val requestId = string(value, "requestId") ?: return null
        val agentId = string(value, "agentId") ?: return null
        val kind = string(value, "kind").orEmpty()
        val args = value.at("args")
        val parsed = when (kind) {
            InboxRequest.KIND_CREATE -> InboxArgs.Create(
                text = args?.let { string(it, "text") },
                dueAt = args?.let { long(it, "dueAt") },
                repeat = args?.let { string(it, "repeat") },
                alertLevel = args?.let { string(it, "alertLevel") },
            )
            InboxRequest.KIND_COMPLETE -> InboxArgs.Complete(args?.let { string(it, "reminderUuid") })
            else -> InboxArgs.Unknown
        }
        return InboxRequest(
            requestId = requestId,
            agentId = agentId,
            clientName = string(value, "clientName").orEmpty(),
            kind = kind,
            args = parsed,
            createdAt = long(value, "createdAt") ?: 0L,
            expiresAt = long(value, "expiresAt") ?: 0L,
        )
    }

    /** A `<Reminder>` read back (the applied-request log and the contract tests). Null when not usable. */
    fun wireReminder(value: JsonValue?): WireReminder? {
        if (value !is JsonValue.Obj) return null
        return WireReminder(
            uuid = string(value, "uuid") ?: return null,
            title = string(value, "title") ?: return null,
            dueAt = long(value, "dueAt") ?: return null,
            status = string(value, "status") ?: return null,
            alertLevel = string(value, "alertLevel") ?: return null,
            requestedAlertLevel = string(value, "requestedAlertLevel"),
            approval = string(value, "approval") ?: return null,
            repeat = string(value, "repeat"),
            version = (long(value, "version") ?: return null).toInt(),
            updatedAt = long(value, "updatedAt") ?: return null,
        )
    }

    fun duplicate(value: JsonValue): Boolean = (value.at("duplicate") as? JsonValue.Bool)?.value ?: false

    fun mirrorAck(value: JsonValue): MirrorAck = MirrorAck(
        reminders = (long(value, "reminders") ?: 0L).toInt(),
        events = (long(value, "events") ?: 0L).toInt(),
        skipped = (long(value, "skipped") ?: 0L).toInt(),
    )

    fun entitlement(value: JsonValue): VerifiedEntitlement = VerifiedEntitlement(
        state = string(value, "state") ?: bad("state"),
        entitled = (value.at("entitled") as? JsonValue.Bool)?.value ?: bad("entitled"),
        expiryTime = long(value, "expiryTime"),
        autoRenewing = (value.at("autoRenewing") as? JsonValue.Bool)?.value ?: false,
        productId = string(value, "productId"),
        lastCheckedAt = long(value, "lastCheckedAt"),
    )

    /**
     * The Firestore REST answer for devices/{uid}/agents (a list of documents
     * with typed fields). Documents whose id is not an agent id are skipped.
     */
    fun agentsFromFirestore(value: JsonValue): List<CloudAgent> {
        val docs = value.at("documents") as? JsonValue.Arr ?: return emptyList()
        return docs.items.mapNotNull { doc ->
            val id = string(doc, "name")?.substringAfterLast('/') ?: return@mapNotNull null
            if (!CloudAgent.isAgentId(id)) return@mapNotNull null
            val fields = doc.at("fields")
            CloudAgent(
                agentId = id,
                clientName = fields?.let { firestoreString(it, "clientName") }.orEmpty(),
                clientId = fields?.let { firestoreString(it, "clientId") },
                createdAt = fields?.let { firestoreLong(it, "createdAt") },
                lastUsedAt = fields?.let { firestoreLong(it, "lastUsedAt") },
                grantRevokedAt = fields?.let { firestoreLong(it, "grantRevokedAt") },
            )
        }
    }

    fun nextPageToken(value: JsonValue): String? = string(value, "nextPageToken")?.takeIf { it.isNotEmpty() }

    // The plain values the Functions SDK sends and returns.

    /** Maps, lists, strings, numbers, booleans and null, as the callable SDK serialises them. */
    fun toPlain(value: JsonValue): Any? = when (value) {
        is JsonValue.Null -> null
        is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Frac -> value.text.toDoubleOrNull()
        is JsonValue.Str -> value.value
        is JsonValue.Arr -> value.items.map(::toPlain)
        is JsonValue.Obj -> LinkedHashMap<String, Any?>().apply { value.fields.forEach { (k, v) -> put(k, toPlain(v)) } }
    }

    /** What the callable SDK hands back. A whole number in a Double reads as a Long. */
    fun fromPlain(raw: Any?): JsonValue = when (raw) {
        null -> JsonValue.Null
        is JsonValue -> raw
        is Boolean -> JsonValue.Bool(raw)
        is String -> JsonValue.Str(raw)
        is Int -> JsonValue.Num(raw.toLong())
        is Long -> JsonValue.Num(raw)
        is Short -> JsonValue.Num(raw.toLong())
        is Byte -> JsonValue.Num(raw.toLong())
        is Double -> wholeOrFrac(raw)
        is Float -> wholeOrFrac(raw.toDouble())
        is Number -> wholeOrFrac(raw.toDouble())
        is Map<*, *> -> JsonValue.Obj(
            LinkedHashMap<String, JsonValue>().apply { raw.forEach { (k, v) -> if (k != null) put(k.toString(), fromPlain(v)) } },
        )
        is Iterable<*> -> JsonValue.Arr(raw.map(::fromPlain))
        is Array<*> -> JsonValue.Arr(raw.map(::fromPlain))
        else -> if (raw.toString() == "null") JsonValue.Null else JsonValue.Str(raw.toString())
    }

    private fun wholeOrFrac(value: Double): JsonValue =
        if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 9.0e15) {
            JsonValue.Num(value.toLong())
        } else {
            JsonValue.Frac(value.toString())
        }

    private const val MAX_APP_VERSION = 40

    private fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = JsonValue.Obj(linkedMapOf(*pairs))

    private fun str(value: String): JsonValue = JsonValue.Str(value)

    private fun JsonValue.at(name: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(name)

    private fun string(value: JsonValue, name: String): String? = (value.at(name) as? JsonValue.Str)?.value

    private fun long(value: JsonValue, name: String): Long? = when (val field = value.at(name)) {
        is JsonValue.Num -> field.value
        is JsonValue.Frac -> field.text.toDoubleOrNull()?.toLong()
        else -> null
    }

    private fun firestoreString(fields: JsonValue, name: String): String? =
        fields.at(name)?.let { string(it, "stringValue") }

    private fun firestoreLong(fields: JsonValue, name: String): Long? {
        val field = fields.at(name) ?: return null
        string(field, "integerValue")?.toLongOrNull()?.let { return it }
        return when (val number = field.at("doubleValue")) {
            is JsonValue.Num -> number.value
            is JsonValue.Frac -> number.text.toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    private fun bad(field: String): Nothing = throw CloudException(CloudCallCode.INTERNAL, "Do Cloud answer is missing $field")
}
