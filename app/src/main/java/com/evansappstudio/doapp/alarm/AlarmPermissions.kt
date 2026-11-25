package com.evansappstudio.doapp.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object AlarmPermissions {
    /**
     * API 26-30 can set an alarm clock without a special permission.
     * API 31-32 use SCHEDULE_EXACT_ALARM (maxSdk 32 on the USE build): this
     * returns false until the user grants it, and the list then shows the banner.
     * API 33+ on a USE build is covered by USE_EXACT_ALARM, which is granted
     * at install, so this returns true unless the platform says otherwise.
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
