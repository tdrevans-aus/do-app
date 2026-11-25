package com.evansappstudio.doapp.ui

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.AlertPermissions
import com.evansappstudio.doapp.alarm.DoAlarmLog

/**
 * First-time explanation for Critical and Prominent.
 * The chosen level is kept even when the system permission is refused.
 */
@Composable
fun rememberAlertChoice(viewModel: ReminderViewModel): (AlertLevel, () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingApply by androidx.compose.runtime.remember { mutableStateOf<(() -> Unit)?>(null) }
    if (pending != null) {
        val level = AlertLevel.fromName(pending) ?: AlertLevel.STANDARD
        AlertDialog(
            onDismissRequest = {
                pending = null
                pendingApply = null
            },
            title = { Text(alertName(level)) },
            text = {
                Text(
                    if (level == AlertLevel.PROMINENT) {
                        stringResource(R.string.alert_explain_prominent)
                    } else {
                        stringResource(R.string.alert_explain_critical)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markAlertExplained(level)
                    pendingApply?.invoke()
                    openMissingPermission(context, level)
                    pending = null
                    pendingApply = null
                }) { Text(stringResource(R.string.alert_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    pendingApply = null
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    return { level, apply ->
        if (level == AlertLevel.STANDARD || viewModel.hasExplainedAlert(level)) {
            apply()
        } else {
            pendingApply = apply
            pending = level.name
        }
    }
}

fun alertName(level: AlertLevel): String = when (level) {
    AlertLevel.STANDARD -> "Standard Alert"
    AlertLevel.CRITICAL -> "Critical Alert !!!"
    AlertLevel.PROMINENT -> "Prominent Alert ⏰"
}

fun openMissingPermission(context: Context, level: AlertLevel) {
    val intent = when (level) {
        AlertLevel.PROMINENT -> {
            if (!AlertPermissions.canUseFullScreenIntent(context)) {
                AlertPermissions.fullScreenSettings(context)
            } else if (!AlertPermissions.hasDndAccess(context)) {
                AlertPermissions.dndSettings()
            } else {
                null
            }
        }
        AlertLevel.CRITICAL -> {
            if (!AlertPermissions.hasDndAccess(context)) AlertPermissions.dndSettings() else null
        }
        AlertLevel.STANDARD -> null
    } ?: return
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (error: Exception) {
        DoAlarmLog.handlingFailed(id = -1, error)
    }
}
