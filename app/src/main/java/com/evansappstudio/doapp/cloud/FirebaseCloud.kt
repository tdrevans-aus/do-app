package com.evansappstudio.doapp.cloud

import android.content.Context
import com.evansappstudio.doapp.backup.JsonValue
import com.evansappstudio.doapp.backup.parseJson
import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.functions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [CloudPort] on Firebase: Anonymous Auth, callables in australia-southeast1
 * and FCM. Firebase is set up here, in code, from [config] (no
 * google-services.json). Nothing runs until the first call: DoCloud builds this
 * lazily and never when [CloudConfig.enabled] is false.
 *
 * Connected assistants are read from Firestore over its REST API with the
 * device's ID token (the rules let a device read its own documents), so the
 * Firestore SDK is not needed. Every failure is a [CloudException].
 */
class FirebaseCloud(context: Context, private val config: CloudConfig) : CloudPort {
    private val appContext = context.applicationContext

    private val app: FirebaseApp by lazy {
        check(config.enabled) { "Do Cloud is off in this build" }
        FirebaseApp.getApps(appContext).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(appContext, options())
    }

    private val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance(app).also { auth ->
            if (config.usesEmulator) auth.useEmulator(config.emulatorHost, CloudConfig.AUTH_EMULATOR_PORT)
        }
    }

    private val functions: FirebaseFunctions by lazy {
        app // Firebase.functions uses the default app: make sure it exists.
        Firebase.functions(CloudConfig.REGION).also { functions ->
            if (config.usesEmulator) functions.useEmulator(config.emulatorHost, CloudConfig.FUNCTIONS_EMULATOR_PORT)
        }
    }

    private fun options(): FirebaseOptions {
        val builder = FirebaseOptions.Builder()
            .setProjectId(config.projectId)
            .setApiKey(config.apiKey)
            .setApplicationId(config.appId)
        config.senderId?.let { builder.setGcmSenderId(it) }
        return builder.build()
    }

    override fun currentIdentity(): CloudIdentity? = try {
        auth.currentUser?.uid?.let(::CloudIdentity)
    } catch (error: Exception) {
        null
    }

    override suspend fun signIn(): CloudIdentity = wrap {
        auth.currentUser?.uid?.let { return@wrap CloudIdentity(it) }
        val user = auth.signInAnonymously().await().user ?: throw CloudException(CloudCallCode.UNAUTHENTICATED, "Anonymous sign-in returned no user")
        CloudIdentity(user.uid)
    }

    override suspend fun messagingToken(): String? = try {
        app
        val messaging = FirebaseMessaging.getInstance()
        if (!messaging.isAutoInitEnabled) messaging.isAutoInitEnabled = true
        messaging.token.await().takeIf { it.isNotBlank() }
    } catch (error: Exception) {
        // No Play services, or the emulator's demo project (FCM has no emulator).
        null
    }

    override suspend fun registerDevice(request: RegisterDevice) {
        call("registerDevice", CloudJson.registerDevice(request))
    }

    override suspend fun setAssistantsEnabled(enabled: Boolean): Boolean =
        CloudJson.enabled(call("setAssistantsEnabled", CloudJson.setAssistantsEnabled(enabled)))

    override suspend fun createPairingCode(): PairingCode = CloudJson.pairingCode(call("createPairingCode", null))

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

    override suspend fun listAgents(): List<CloudAgent> = wrap {
        val user = auth.currentUser ?: throw CloudException(CloudCallCode.UNAUTHENTICATED, "No cloud identity")
        val idToken = user.getIdToken(false).await().token
            ?: throw CloudException(CloudCallCode.UNAUTHENTICATED, "No ID token")
        val agents = ArrayList<CloudAgent>()
        var page: String? = null
        do {
            val body = withContext(Dispatchers.IO) { getJson(agentsUrl(user.uid, page), idToken) }
            agents += CloudJson.agentsFromFirestore(body)
            page = CloudJson.nextPageToken(body)
        } while (page != null && agents.size < MAX_AGENTS)
        agents
    }

    private fun agentsUrl(uid: String, page: String?): String {
        val base = if (config.usesEmulator) {
            "http://${config.emulatorHost}:${CloudConfig.FIRESTORE_EMULATOR_PORT}"
        } else {
            "https://firestore.googleapis.com"
        }
        val path = "/v1/projects/${enc(config.projectId)}/databases/(default)/documents/devices/${enc(uid)}/agents"
        val query = "?pageSize=100" + (page?.let { "&pageToken=" + enc(it) } ?: "")
        return base + path + query
    }

    private fun getJson(url: String, idToken: String): JsonValue {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return when {
                status in 200..299 -> if (text.isBlank()) JsonValue.Obj(emptyMap()) else parseJson(text, allowFractions = true)
                status == 401 -> throw CloudException(CloudCallCode.UNAUTHENTICATED, "Firestore refused the ID token")
                status == 403 -> throw CloudException(CloudCallCode.UNAUTHENTICATED, "Firestore rules refused the read")
                status == 404 -> JsonValue.Obj(emptyMap())
                else -> throw CloudException(CloudCallCode.UNAVAILABLE, "Firestore answered $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun call(name: String, data: JsonValue?): JsonValue = wrap {
        if (auth.currentUser == null) throw CloudException(CloudCallCode.UNAUTHENTICATED, "No cloud identity")
        val callable = functions.getHttpsCallable(name)
        callable.setTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val result = if (data == null) callable.call().await() else callable.call(CloudJson.toPlain(data)).await()
        CloudJson.fromPlain(result.getData())
    }

    private suspend fun <T> wrap(block: suspend () -> T): T = try {
        block()
    } catch (error: CloudException) {
        throw error
    } catch (error: FirebaseFunctionsException) {
        throw CloudException(CloudCallCode.fromWire(error.code.name), error.message ?: error.code.name, error)
    } catch (error: IOException) {
        throw CloudException(CloudCallCode.UNAVAILABLE, error.message ?: "Network error", error)
    } catch (error: IllegalArgumentException) {
        throw CloudException(CloudCallCode.INTERNAL, error.message ?: "Malformed answer", error)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        // FirebaseNetworkException, FirebaseAuthException and others: try again later.
        throw CloudException(CloudCallCode.UNAVAILABLE, error.message ?: error::class.java.simpleName, error)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val CALL_TIMEOUT_SECONDS = 20L
        const val MAX_AGENTS = 500
    }
}

/** Waits for a Play services Task without blocking a thread. */
internal suspend fun <T> Task<T>.await(): T {
    if (isComplete) {
        exception?.let { throw it }
        if (isCanceled) throw kotlinx.coroutines.CancellationException("Task was cancelled")
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    return suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            when {
                error != null -> continuation.resumeWithException(error)
                task.isCanceled -> continuation.cancel()
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(task.result as T)
                }
            }
        }
    }
}
