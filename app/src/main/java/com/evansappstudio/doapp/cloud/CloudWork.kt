package com.evansappstudio.doapp.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Spec 13 background work, on WorkManager. Pulls run as expedited jobs after a
 * push on Android 12 and later (JobScheduler expedited jobs, no foreground
 * service; older versions get a plain job, because Do declares no foreground
 * service). Every job needs a network and retries with backoff; a failure is
 * logged and never touches an alarm.
 */
class WorkManagerCloudJobs(context: Context) : CloudJobs {
    private val appContext = context.applicationContext

    override fun pull(expedited: Boolean) {
        val builder = OneTimeWorkRequestBuilder<CloudPullWorker>()
            .setConstraints(networked())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
        if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        enqueue(PULL, builder.build(), if (expedited) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    override fun syncMirror() {
        val request = OneTimeWorkRequestBuilder<CloudMirrorWorker>()
            .setConstraints(networked())
            .setInitialDelay(MIRROR_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // REPLACE restarts the delay: a burst of changes becomes one sync.
        enqueue(MIRROR, request, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(name: String, request: androidx.work.OneTimeWorkRequest, policy: ExistingWorkPolicy) {
        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(name, policy, request)
        } catch (error: Throwable) {
            DoAlarmLog.cloud("enqueue failed work=$name error=${error::class.java.simpleName}")
        }
    }

    private fun networked() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val TAG = "do-cloud"
        const val PULL = "do-cloud-pull"
        const val MIRROR = "do-cloud-mirror"
        const val MIRROR_DEBOUNCE_SECONDS = 10L
        const val BACKOFF_SECONDS = 30L
        const val MAX_ATTEMPTS = 8
    }
}

class CloudPullWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cloud = cloudOrNull(applicationContext) ?: return Result.success()
        return try {
            val run = cloud.runPull()
            DoAlarmLog.cloud(
                "pull pulled=${run.pulled} applied=${run.applied} replayed=${run.replayed} completed=${run.completed} deferred=${run.deferred}",
            )
            Result.success()
        } catch (error: Throwable) {
            DoAlarmLog.cloud("pull failed attempt=$runAttemptCount error=${describe(error)}")
            if (runAttemptCount < WorkManagerCloudJobs.MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}

class CloudMirrorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cloud = cloudOrNull(applicationContext) ?: return Result.success()
        return try {
            val run = cloud.runMirror()
            if (run != null) {
                DoAlarmLog.cloud("mirror batches=${run.batches} reminders=${run.reminders} events=${run.events} gone=${run.goneAgents.size}")
            }
            Result.success()
        } catch (error: Throwable) {
            DoAlarmLog.cloud("mirror failed attempt=$runAttemptCount error=${describe(error)}")
            if (runAttemptCount < WorkManagerCloudJobs.MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}

/**
 * FCM. The only push Do Cloud sends is the data message `{ kind: inbox }`; it
 * enqueues the expedited pull and returns at once. A new token is registered
 * when the device already has a cloud identity.
 */
class DoMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val cloud = cloudOrNull(applicationContext) ?: return
        cloud.onPush(message.data)
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as? DoApp ?: return
        val cloud = app.container.cloud ?: return
        app.container.scope.launchCatching { cloud.onNewToken(token) }
    }
}

/** Pulls when the network comes back (WorkManager's network constraint covers jobs already waiting). */
class CloudNetworkWatcher(private val context: Context, private val onAvailable: () -> Unit) {
    private var registered = false
    private var first = true

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // The first call is the network already up at start; app start pulls anyway.
            if (first) {
                first = false
                return
            }
            onAvailable()
        }

        override fun onLost(network: Network) {
            first = false
        }
    }

    fun start() {
        if (registered) return
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (error: Throwable) {
            DoAlarmLog.cloud("network watch failed error=${error::class.java.simpleName}")
        }
    }
}

private fun cloudOrNull(context: Context): DoCloud? {
    if (!UserUnlock.isUnlocked(context)) return null
    val app = context.applicationContext as? DoApp ?: return null
    return app.container.cloud
}

private fun describe(error: Throwable): String = when (error) {
    is CloudException -> "cloud:${error.code.wire}"
    else -> error::class.java.simpleName
}

private fun CoroutineScope.launchCatching(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (error: Throwable) {
            DoAlarmLog.cloud("token refresh failed error=${describe(error)}")
        }
    }
}
