package com.evansappstudio.doapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.evansappstudio.doapp.capture.CaptureEntry
import com.evansappstudio.doapp.capture.LaunchRequest
import com.evansappstudio.doapp.capture.LaunchRequests
import com.evansappstudio.doapp.ui.ReminderApp

class MainActivity : ComponentActivity() {
    private var pendingOpenId by mutableStateOf<Long?>(null)
    private var pendingLaunch by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pendingOpenId = parseOpenId(intent)
            pendingLaunch = parseLaunch(intent)
        }
        val container = (application as DoApp).container
        setContent {
            MaterialTheme {
                ReminderApp(
                    container = container,
                    openRequest = pendingOpenId,
                    onOpenRequestConsumed = { pendingOpenId = null },
                    launchRequest = pendingLaunch,
                    onLaunchRequestConsumed = { pendingLaunch = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = (application as DoApp).container
        container.startCloud()
        container.billing.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenId = parseOpenId(intent)
        pendingLaunch = parseLaunch(intent)
    }

    companion object {
        /** Opens Add from a capture surface. The add saves with [entry]'s Origin. */
        /** Non-exported alias of this activity: only Do itself (widgets, tile) can start it. */
        const val INTERNAL_ALIAS = "com.evansappstudio.doapp.InternalAdd"

        fun addIntent(context: Context, entry: CaptureEntry, text: String? = null): Intent =
            Intent().setClassName(context, INTERNAL_ALIAS)
                .setAction(LaunchRequests.ACTION_ADD)
                .putExtra(LaunchRequests.EXTRA_ENTRY, entry.name)
                .apply { if (text != null) putExtra(LaunchRequests.EXTRA_TEXT, text) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun parseLaunch(intent: Intent?): LaunchRequest? {
            if (intent == null) return null
            return LaunchRequests.from(
                action = intent.action,
                data = intent.dataString,
                entryName = intent.getStringExtra(LaunchRequests.EXTRA_ENTRY),
                text = intent.getStringExtra(LaunchRequests.EXTRA_TEXT),
                trusted = intent.component?.className == INTERNAL_ALIAS,
            )
        }

        fun parseOpenId(intent: Intent?): Long? {
            val data = intent?.data ?: return null
            if (data.scheme != "doapp" || data.host != "open") return null
            return data.lastPathSegment?.toLongOrNull()
        }
    }
}
