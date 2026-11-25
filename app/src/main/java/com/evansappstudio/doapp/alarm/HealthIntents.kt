package com.evansappstudio.doapp.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Fix destinations. Battery optimisation opens the system exemption list.
 * The one-tap REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog is not used.
 */
object HealthIntents {
    const val ACTION_BATTERY_LIST = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val ACTION_BATTERY_REQUEST = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    const val ACTION_EXACT_ALARM = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"
    const val ACTION_FULL_SCREEN = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"
    const val SAMSUNG_LEARN_MORE = "https://dontkillmyapp.com/samsung"

    fun primaryAction(kind: HealthActionKind): String? = when (kind) {
        HealthActionKind.APP_NOTIFICATIONS -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
        HealthActionKind.CHANNEL -> Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
        HealthActionKind.EXACT_ALARM -> ACTION_EXACT_ALARM
        HealthActionKind.FULL_SCREEN -> ACTION_FULL_SCREEN
        HealthActionKind.DND -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
        HealthActionKind.BATTERY_LIST -> ACTION_BATTERY_LIST
        HealthActionKind.APP_DETAILS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        HealthActionKind.SAMSUNG_BATTERY -> null
        HealthActionKind.SYSTEM_SETTINGS -> Settings.ACTION_SETTINGS
        HealthActionKind.LEARN_MORE -> Intent.ACTION_VIEW
        HealthActionKind.RESTORE_ALARMS,
        HealthActionKind.REENABLE_BOOT,
        HealthActionKind.CLEAR_LATE,
        -> null
    }

    fun open(context: Context, fix: HealthFix) {
        val intents = candidates(context, fix)
        startFirst(context, intents)
    }

    fun candidates(context: Context, fix: HealthFix): List<Intent> {
        val packageName = context.packageName
        val primary = when (fix.kind) {
            HealthActionKind.APP_NOTIFICATIONS -> appNotificationSettings(packageName)
            HealthActionKind.CHANNEL -> channelSettings(packageName, fix.channelId)
            HealthActionKind.EXACT_ALARM -> exactAlarmSettings(packageName)
            HealthActionKind.FULL_SCREEN -> AlertPermissions.fullScreenSettings(context)
            HealthActionKind.DND -> AlertPermissions.dndSettings()
            HealthActionKind.BATTERY_LIST -> Intent(ACTION_BATTERY_LIST)
            HealthActionKind.APP_DETAILS -> appDetails(packageName)
            HealthActionKind.SAMSUNG_BATTERY -> null
            HealthActionKind.SYSTEM_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            HealthActionKind.LEARN_MORE -> Intent(Intent.ACTION_VIEW, Uri.parse(SAMSUNG_LEARN_MORE))
            HealthActionKind.RESTORE_ALARMS,
            HealthActionKind.REENABLE_BOOT,
            HealthActionKind.CLEAR_LATE,
            -> return emptyList()
        }
        val chain = when (fix.kind) {
            HealthActionKind.SAMSUNG_BATTERY -> samsungBatteryChain(packageName)
            HealthActionKind.CHANNEL -> listOfNotNull(primary, appNotificationSettings(packageName), appDetails(packageName))
            HealthActionKind.LEARN_MORE -> listOfNotNull(primary)
            else -> listOfNotNull(primary, appDetails(packageName), Intent(Settings.ACTION_SETTINGS))
        }
        return chain
    }

    private fun samsungBatteryChain(packageName: String): List<Intent> {
        val deviceCare = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            ),
            Intent("com.samsung.android.sm.ACTION_BATTERY"),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
            appDetails(packageName),
        )
        return deviceCare
    }

    private fun appNotificationSettings(packageName: String): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
    }

    private fun channelSettings(packageName: String, channelId: String?): Intent? {
        if (channelId.isNullOrBlank()) return appNotificationSettings(packageName)
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        }
    }

    private fun exactAlarmSettings(packageName: String): Intent {
        return Intent(ACTION_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    private fun appDetails(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    private fun startFirst(context: Context, intents: List<Intent>) {
        if (intents.isEmpty()) return
        val packageManager = context.packageManager
        for (intent in intents) {
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolves = try {
                launch.resolveActivity(packageManager) != null
            } catch (error: Throwable) {
                false
            }
            if (!resolves) continue
            try {
                context.startActivity(launch)
                return
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            }
        }
        DoAlarmLog.handlingFailed(id = -1, IllegalStateException("health fix intent did not resolve"))
    }
}
