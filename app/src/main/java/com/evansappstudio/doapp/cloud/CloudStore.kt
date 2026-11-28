package com.evansappstudio.doapp.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.encodeJson
import com.evansappstudio.doapp.backup.parseJson

/**
 * Spec 13 local cloud state, in its own credential-protected prefs file. It is not
 * in Do's backup rules on purpose: a restored phone must not reuse another
 * device's cloud identity, request log or mirror cursor. No reminder titles are
 * kept here except inside an applied result, which is dropped after [APPLIED_KEEP_MS].
 */
class CloudStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    /** The anonymous uid once this device has signed in. Firebase keeps the session itself; this is the flag that one exists. */
    fun identityUid(): String? = synchronized(lock) { prefs.getString(KEY_UID, null)?.takeIf { it.isNotBlank() } }

    fun setIdentityUid(uid: String?) = edit { if (uid == null) remove(KEY_UID) else putString(KEY_UID, uid) }

    /** The assistants on/off switch, as last chosen on this phone. Default on. */
    fun assistantsEnabled(): Boolean = synchronized(lock) { prefs.getBoolean(KEY_ENABLED, true) }

    /** True while the server has not yet heard the latest switch value. */
    fun enabledDirty(): Boolean = synchronized(lock) { prefs.getBoolean(KEY_ENABLED_DIRTY, false) }

    fun setAssistantsEnabled(enabled: Boolean, dirty: Boolean) = edit {
        putBoolean(KEY_ENABLED, enabled)
        putBoolean(KEY_ENABLED_DIRTY, dirty)
    }

    /** "Ask me before it can ring through". Default on. */
    fun askBeforeRingThrough(): Boolean = synchronized(lock) { prefs.getBoolean(KEY_ASK, true) }

    fun setAskBeforeRingThrough(ask: Boolean) = edit { putBoolean(KEY_ASK, ask) }

    /** The FCM token last sent to registerDevice. */
    fun registeredToken(): String? = synchronized(lock) { prefs.getString(KEY_TOKEN, null) }

    fun setRegisteredToken(token: String?) = edit { if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token) }

    // Applied requests: so a request pulled twice (completeRequest failed) is never applied twice.

    fun appliedResult(requestId: String): RequestResult? = synchronized(lock) {
        val entry = applied()[requestId] ?: return null
        decodeResult(entry.field("result"))
    }

    fun recordApplied(requestId: String, result: RequestResult, now: Long) = synchronized(lock) {
        val kept = applied().filterValues { entry ->
            val at = (entry.field("at") as? JsonValue.Num)?.value ?: 0L
            now - at < APPLIED_KEEP_MS
        }.toList().takeLast(APPLIED_MAX - 1).toMap(LinkedHashMap())
        kept[requestId] = JsonValue.Obj(linkedMapOf("at" to JsonValue.Num(now), "result" to CloudJson.result(result)))
        write { putString(KEY_APPLIED, encodeJson(JsonValue.Obj(kept))) }
    }

    fun appliedCount(): Int = synchronized(lock) { applied().size }

    private fun applied(): Map<String, JsonValue> = readObj(KEY_APPLIED)

    // Mirror sync state.

    fun mirrorCursor(): Long = synchronized(lock) { prefs.getLong(KEY_CURSOR, 0L) }

    fun mirrorVersions(): Map<String, Int> = synchronized(lock) {
        readObj(KEY_VERSIONS).mapNotNull { (uuid, value) -> (value as? JsonValue.Num)?.let { uuid to it.value.toInt() } }.toMap()
    }

    fun saveMirror(cursor: Long, versions: Map<String, Int>) = edit {
        putLong(KEY_CURSOR, cursor)
        putString(KEY_VERSIONS, encodeJson(JsonValue.Obj(versions.mapValuesTo(LinkedHashMap()) { JsonValue.Num(it.value.toLong()) })))
    }

    fun noteSynced(uuid: String, version: Int) = synchronized(lock) {
        val versions = mirrorVersions().toMutableMap()
        if ((versions[uuid] ?: 0) < version) versions[uuid] = version
        saveMirror(mirrorCursor(), versions)
    }

    // Connected assistants, cached from the last successful read.

    fun agents(): List<CloudAgent> = synchronized(lock) {
        val list = prefs.getString(KEY_AGENTS, null) ?: return emptyList()
        try {
            val items = (parseJson(list) as? JsonValue.Arr)?.items.orEmpty()
            items.mapNotNull { item ->
                val id = (item.field("agentId") as? JsonValue.Str)?.value ?: return@mapNotNull null
                CloudAgent(
                    agentId = id,
                    clientName = (item.field("clientName") as? JsonValue.Str)?.value.orEmpty(),
                    clientId = (item.field("clientId") as? JsonValue.Str)?.value,
                    createdAt = (item.field("createdAt") as? JsonValue.Num)?.value,
                    lastUsedAt = (item.field("lastUsedAt") as? JsonValue.Num)?.value,
                    grantRevokedAt = (item.field("grantRevokedAt") as? JsonValue.Num)?.value,
                )
            }
        } catch (error: IllegalArgumentException) {
            emptyList()
        }
    }

    fun setAgents(agents: List<CloudAgent>) = edit {
        val items = agents.map { agent ->
            JsonValue.Obj(
                linkedMapOf(
                    "agentId" to JsonValue.Str(agent.agentId),
                    "clientName" to JsonValue.Str(agent.clientName),
                    "clientId" to (agent.clientId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                    "createdAt" to (agent.createdAt?.let { JsonValue.Num(it) } ?: JsonValue.Null),
                    "lastUsedAt" to (agent.lastUsedAt?.let { JsonValue.Num(it) } ?: JsonValue.Null),
                    "grantRevokedAt" to (agent.grantRevokedAt?.let { JsonValue.Num(it) } ?: JsonValue.Null),
                ),
            )
        }
        putString(KEY_AGENTS, encodeJson(JsonValue.Arr(items)))
    }

    // The last server purchase verdict (it wins over the local Play result while fresh).

    fun serverEntitlement(): VerifiedEntitlement? = synchronized(lock) {
        val text = prefs.getString(KEY_ENTITLEMENT, null) ?: return null
        try {
            CloudJson.entitlement(parseJson(text))
        } catch (error: Exception) {
            null
        }
    }

    fun serverEntitlementAt(): Long = synchronized(lock) { prefs.getLong(KEY_ENTITLEMENT_AT, 0L) }

    fun setServerEntitlement(value: VerifiedEntitlement, at: Long) = edit {
        val json = JsonValue.Obj(
            linkedMapOf(
                "state" to JsonValue.Str(value.state),
                "entitled" to JsonValue.Bool(value.entitled),
                "expiryTime" to (value.expiryTime?.let { JsonValue.Num(it) } ?: JsonValue.Null),
                "autoRenewing" to JsonValue.Bool(value.autoRenewing),
                "productId" to (value.productId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                "lastCheckedAt" to (value.lastCheckedAt?.let { JsonValue.Num(it) } ?: JsonValue.Null),
            ),
        )
        putString(KEY_ENTITLEMENT, encodeJson(json))
        putLong(KEY_ENTITLEMENT_AT, at)
    }

    /** When verifyPurchase last answered (any verdict, other_device included). */
    fun verifiedAt(): Long = synchronized(lock) { prefs.getLong(KEY_VERIFIED_AT, 0L) }

    fun setVerifiedAt(at: Long) = edit { putLong(KEY_VERIFIED_AT, at) }

    fun clearServerEntitlement() = edit {
        remove(KEY_ENTITLEMENT)
        remove(KEY_ENTITLEMENT_AT)
    }

    private fun readObj(key: String): Map<String, JsonValue> {
        val text = prefs.getString(key, null) ?: return emptyMap()
        return try {
            (parseJson(text) as? JsonValue.Obj)?.fields.orEmpty()
        } catch (error: IllegalArgumentException) {
            emptyMap()
        }
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) = synchronized(lock) { write(block) }

    @SuppressLint("ApplySharedPref")
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
    }

    private fun JsonValue.field(name: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(name)

    private fun decodeResult(value: JsonValue?): RequestResult? {
        val status = (value?.field("status") as? JsonValue.Str)?.value
        return when (status) {
            "done" -> CloudJson.wireReminder(value.field("reminder"))?.let { RequestResult.Done(it) }
            "failed" -> {
                val error = value.field("error")
                val code = PhoneErrorCode.fromWire((error?.field("code") as? JsonValue.Str)?.value) ?: return null
                RequestResult.Failed(code, (error?.field("message") as? JsonValue.Str)?.value)
            }
            else -> null
        }
    }

    companion object {
        const val PREFS = "do_cloud"
        const val APPLIED_KEEP_MS = 8L * 24L * 60L * 60L * 1000L
        const val APPLIED_MAX = 500
        private const val KEY_UID = "identity_uid"
        private const val KEY_ENABLED = "assistants_enabled"
        private const val KEY_ENABLED_DIRTY = "assistants_enabled_dirty"
        private const val KEY_ASK = "ask_before_ring_through"
        private const val KEY_TOKEN = "registered_token"
        private const val KEY_APPLIED = "applied_requests"
        private const val KEY_CURSOR = "mirror_cursor"
        private const val KEY_VERSIONS = "mirror_versions"
        private const val KEY_AGENTS = "agents"
        private const val KEY_ENTITLEMENT = "server_entitlement"
        private const val KEY_ENTITLEMENT_AT = "server_entitlement_at"
        private const val KEY_VERIFIED_AT = "verified_at"
    }
}
