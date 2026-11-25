package com.evansappstudio.doapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
<<<<<<< HEAD
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
=======
>>>>>>> origin/main
import com.evansappstudio.doapp.ui.ReminderApp

class MainActivity : ComponentActivity() {
    private var pendingOpenId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
<<<<<<< HEAD
        if (savedInstanceState == null) {
            pendingOpenId = parseOpenId(intent)
        }
        val container = (application as DoApp).container
        setContent {
            MaterialTheme {
                ReminderApp(
                    container = container,
                    openRequest = pendingOpenId,
                    onOpenRequestConsumed = { pendingOpenId = null },
                )
=======
        val container = (application as DoApp).container
        setContent {
            MaterialTheme {
                ReminderApp(container)
>>>>>>> origin/main
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenId = parseOpenId(intent)
    }

    companion object {
        fun parseOpenId(intent: Intent?): Long? {
            val data = intent?.data ?: return null
            if (data.scheme != "doapp" || data.host != "open") return null
            return data.lastPathSegment?.toLongOrNull()
        }
    }
}
