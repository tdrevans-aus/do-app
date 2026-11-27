package com.evansappstudio.doapp.alarm

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.R
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Lock-screen alarm for a Prominent reminder.
 * Sound is a looping alarm-stream player in this activity, plus FLAG_INSISTENT
 * on the notification when the activity is not in front. No foreground service.
 */
class ProminentAlertActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var stopping = false
    private var unlisten: (() -> Unit)? = null
    private var reminderId: Long = Long.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        reminderId = intent.getLongExtra(EXTRA_ID, Long.MIN_VALUE)
        if (reminderId == Long.MIN_VALUE) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val dueAt = intent.getLongExtra(EXTRA_DUE, 0L)
        val app = application as DoApp
        unlisten = AlertSession.listen { id ->
            if (id == reminderId) {
                stopping = true
                finish()
            }
        }
        val options = app.container.snoozeConfig.get()
        setContent {
            MaterialTheme {
                ProminentScreen(
                    title = title.ifBlank { getString(R.string.app_name) },
                    due = formatDue(dueAt),
                    snoozes = options.map { SnoozeLabels.format(it) },
                    doneLabel = getString(R.string.action_done),
                    stopLabel = getString(R.string.action_stop),
                    onDone = { attend { app.container.commands.markDone(NOTIFICATION, reminderId) } },
                    onSnooze = { index ->
                        val option = options.getOrNull(index) ?: return@ProminentScreen
                        attend { app.container.commands.snooze(NOTIFICATION, reminderId, index, option) }
                    },
                    onStop = { attend { app.container.commands.stopAlert(NOTIFICATION, reminderId) } },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (startPlayer()) {
            (application as DoApp).container.notifications.setLoopAudible(reminderId, false)
        }
    }

    override fun onStop() {
        player?.pause()
        if (!stopping && !isFinishing) {
            (application as DoApp).container.notifications.setLoopAudible(reminderId, true)
        }
        super.onStop()
    }

    override fun onDestroy() {
        unlisten?.invoke()
        unlisten = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            attend { (application as DoApp).container.commands.stopAlert(NOTIFICATION, reminderId) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun attend(block: suspend () -> Unit) {
        stopping = true
        val app = application as DoApp
        app.container.scope.launch { 
            try {
                block()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(reminderId, error)
            }
        }
        finish()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startPlayer(): Boolean {
        return try {
            val created = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@ProminentAlertActivity, Settings.System.DEFAULT_ALARM_ALERT_URI)
                isLooping = true
                prepare()
                start()
            }
            player = created
            true
        } catch (error: Exception) {
            DoAlarmLog.handlingFailed(reminderId, error)
            false
        }
    }

    private fun formatDue(dueAt: Long): String {
        if (dueAt <= 0L) return ""
        return DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(dueAt))
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DUE = "due"
        private val NOTIFICATION = Origin(Actor.NOTIFICATION)
    }
}

@Composable
private fun ProminentScreen(
    title: String,
    due: String,
    snoozes: List<String>,
    doneLabel: String,
    stopLabel: String,
    onDone: () -> Unit,
    onSnooze: (Int) -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        if (due.isNotEmpty()) Text(text = due, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(doneLabel) }
        snoozes.forEachIndexed { index, label ->
            Button(onClick = { onSnooze(index) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stopLabel) }
    }
}
