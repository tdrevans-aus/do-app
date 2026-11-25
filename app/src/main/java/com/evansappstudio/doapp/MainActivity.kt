package com.evansappstudio.doapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.evansappstudio.doapp.ui.ReminderApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DoApp).container
        setContent {
            MaterialTheme {
                ReminderApp(container)
            }
        }
    }
}
