package com.evansappstudio.doapp.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.QuietHours
import java.util.Calendar
import com.evansappstudio.doapp.alarm.AlertPermissions
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.WeekStart
import com.evansappstudio.doapp.alarm.SnoozeLabels
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.billing.PaywallCopy
import com.evansappstudio.doapp.billing.SubscriptionUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSettingsScreen(
    viewModel: ReminderViewModel,
    unlock: SubscriptionUiState,
    onBack: () -> Unit,
    onOpenHealth: () -> Unit,
    onUnlock: () -> Unit,
    /** Spec 11: the way back from "Stop suggesting". */
    suggestionsOn: () -> Boolean = { true },
    onSuggestionsOn: (Boolean) -> Unit = {},
    /** Spec 13: the Assistants screen. Null when the build has no Do Cloud (the row is hidden). */
    onOpenAssistants: (() -> Unit)? = null,
) {
    var suggestions by remember { mutableStateOf(suggestionsOn()) }
    val options by viewModel.snoozeOptions.collectAsStateWithLifecycle()
    val quiet by viewModel.quietHours.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    var quietPick by remember { mutableStateOf<String?>(null) }
    var quietRejected by remember { mutableStateOf(false) }
    var weekStart by remember { mutableStateOf(viewModel.weekStart()) }
    var parseDates by remember { mutableStateOf(viewModel.parseDatesEnabled()) }
    var alertDefault by remember { mutableStateOf(viewModel.defaultAlertLevel()) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val chooseAlert = rememberAlertChoice(viewModel)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                parseDates = viewModel.parseDatesEnabled()
                alertDefault = viewModel.defaultAlertLevel()
                viewModel.reloadQuiet()
                permissionTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_snooze)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUnlock() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val copy = PaywallCopy.build(unlock, paywallTemplates())
                    Text(text = stringResource(R.string.sub_name))
                    // The renewal price always sits beside an intro price (priceDetail), as on the paywall.
                    Text(text = copy.statusLine ?: copy.priceHeadline)
                    if (copy.statusLine == null) copy.priceDetail?.let { Text(text = it) }
                }
            }
            itemsIndexed(options) { index, option ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = index }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = stringResource(R.string.snooze_option_row, index + 1))
                    Text(text = SnoozeLabels.format(option))
                    Text(
                        text = if (option.kind == SnoozeKind.FROM_NOW) {
                            stringResource(R.string.snooze_from_now)
                        } else {
                            stringResource(R.string.snooze_at_time)
                        },
                    )
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(text = stringResource(R.string.week_starts_on))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WeekStart.entries.forEach { start ->
                            FilterChip(
                                selected = weekStart == start,
                                onClick = {
                                    viewModel.setWeekStart(start)
                                    weekStart = start
                                },
                                label = {
                                    Text(
                                        when (start) {
                                            WeekStart.MONDAY -> stringResource(R.string.week_start_monday)
                                            WeekStart.SATURDAY -> stringResource(R.string.week_start_saturday)
                                            WeekStart.SUNDAY -> stringResource(R.string.week_start_sunday)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sysd_suggestions_setting))
                            Text(
                                stringResource(R.string.sysd_suggestions_setting_subtitle),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = suggestions, onCheckedChange = {
                            onSuggestionsOn(it)
                            suggestions = it
                        })
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.parse_dates), modifier = Modifier.weight(1f))
                        Switch(checked = parseDates, onCheckedChange = {
                            viewModel.setParseDatesEnabled(it)
                            parseDates = it
                        })
                    }
                    Text(stringResource(R.string.alert_default))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertLevel.entries.forEach { level ->
                            FilterChip(
                                selected = alertDefault == level,
                                onClick = {
                                    chooseAlert(level) {
                                        viewModel.setDefaultAlertLevel(level)
                                        alertDefault = level
                                    }
                                },
                                label = { Text(alertName(level)) },
                            )
                        }
                    }
                    val fsi = permissionTick >= 0 && AlertPermissions.canUseFullScreenIntent(context)
                    val dnd = AlertPermissions.hasDndAccess(context)
                    Text(
                        stringResource(R.string.alert_fsi) + ": " +
                            stringResource(if (fsi) R.string.alert_allowed else R.string.alert_missing),
                    )
                    TextButton(onClick = {
                        openSettings(context, AlertPermissions.fullScreenSettings(context))
                    }) { Text(stringResource(R.string.alert_open)) }
                    Text(
                        stringResource(R.string.alert_dnd) + ": " +
                            stringResource(if (dnd) R.string.alert_allowed else R.string.alert_missing),
                    )
                    TextButton(onClick = {
                        openSettings(context, AlertPermissions.dndSettings())
                    }) { Text(stringResource(R.string.alert_open)) }
                    Text(stringResource(R.string.quiet_hours))
                    Text(stringResource(R.string.quiet_caption))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.quiet_hours), modifier = Modifier.weight(1f))
                        Switch(
                            checked = quiet.enabled,
                            onCheckedChange = { viewModel.setQuietEnabled(it) },
                        )
                    }
                    TextButton(onClick = { quietPick = "start" }) {
                        Text(stringResource(R.string.quiet_starts) + " " + formatQuietMinute(context, quiet.startMinute))
                    }
                    TextButton(onClick = { quietPick = "end" }) {
                        Text(stringResource(R.string.quiet_ends) + " " + formatQuietMinute(context, quiet.endMinute))
                    }
                    if (quietRejected) {
                        Text(stringResource(R.string.quiet_same))
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.quiet_follow_dnd))
                            Text(stringResource(R.string.quiet_follow_dnd_subtitle))
                        }
                        Switch(
                            checked = quiet.followDnd,
                            onCheckedChange = { viewModel.setQuietFollowDnd(it) },
                        )
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.quiet_critical))
                            Text(stringResource(R.string.quiet_critical_subtitle))
                        }
                        Switch(
                            checked = quiet.criticalPauses,
                            onCheckedChange = { viewModel.setQuietCriticalPauses(it) },
                        )
                    }
                    TextButton(onClick = onOpenHealth) {
                        Text(stringResource(R.string.health_check))
                    }
                    if (onOpenAssistants != null) {
                        TextButton(onClick = onOpenAssistants) {
                            Text(stringResource(R.string.assistants_settings_entry))
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = { viewModel.setRepeatPromptMode(RepeatPromptMode.PROMPT) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.restore_prompts))
                }
            }
        }
    }
    val picking = quietPick
    if (picking != null) {
        val initial = if (picking == "start") quiet.startMinute else quiet.endMinute
        val is24Hour = rememberIs24Hour()
        QuietMinuteDialog(
            title = stringResource(if (picking == "start") R.string.quiet_starts else R.string.quiet_ends),
            minute = initial,
            is24Hour = is24Hour,
            onDismiss = { quietPick = null },
            onSave = { minute ->
                val accepted = if (picking == "start") {
                    viewModel.setQuietStart(minute)
                } else {
                    viewModel.setQuietEnd(minute)
                }
                quietRejected = !accepted
                quietPick = null
            },
        )
    }
    val index = editing
    if (index != null && index in options.indices) {
        SnoozeOptionDialog(
            option = options[index],
            onDismiss = { editing = null },
            onSave = { option ->
                viewModel.setSnoozeOption(index, option)
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeOptionDialog(
    option: SnoozeOption,
    onDismiss: () -> Unit,
    onSave: (SnoozeOption) -> Unit,
) {
    var kind by remember(option) { mutableStateOf(option.kind) }
    var minutesText by remember(option) {
        mutableStateOf(if (option.kind == SnoozeKind.FROM_NOW) option.value.toString() else "60")
    }
    var atHour by remember(option) {
        mutableIntStateOf(if (option.kind == SnoozeKind.AT_TIME) option.value / 60 else 9)
    }
    var atMinute by remember(option) {
        mutableIntStateOf(if (option.kind == SnoozeKind.AT_TIME) option.value % 60 else 0)
    }
    var showTime by remember { mutableStateOf(false) }
    val minutes = minutesText.toIntOrNull()
    val fromNowMinutes = minutes?.takeIf { it in 0..999_999 }
    val draft = when (kind) {
        SnoozeKind.FROM_NOW -> fromNowMinutes?.let { SnoozeOption(kind, it) }
        SnoozeKind.AT_TIME -> SnoozeOption(kind, atHour * 60 + atMinute)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notification_snooze)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == SnoozeKind.FROM_NOW,
                        onClick = { kind = SnoozeKind.FROM_NOW },
                        label = { Text(stringResource(R.string.snooze_from_now)) },
                    )
                    FilterChip(
                        selected = kind == SnoozeKind.AT_TIME,
                        onClick = { kind = SnoozeKind.AT_TIME },
                        label = { Text(stringResource(R.string.snooze_at_time)) },
                    )
                }
                if (kind == SnoozeKind.FROM_NOW) {
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it.filter { char -> char.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.snooze_minutes_label)) },
                        supportingText = { Text(stringResource(R.string.snooze_minutes_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                } else {
                    TextButton(onClick = { showTime = true }) {
                        Text(SnoozeLabels.atTime(atHour * 60 + atMinute))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { draft?.let(onSave) },
                enabled = draft != null && draft.isValid(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (showTime) {
        val state = rememberTimePickerState(initialHour = atHour, initialMinute = atMinute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    atHour = state.hour
                    atMinute = state.minute
                    showTime = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.cancel)) }
            },
            text = { TimePicker(state = state) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietMinuteDialog(
    title: String,
    minute: Int,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = minute / 60,
        initialMinute = minute % 60,
        is24Hour = is24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onSave(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun formatQuietMinute(context: android.content.Context, minute: Int): String {
    val safe = minute.coerceIn(0, QuietHours.MINUTES_PER_DAY - 1)
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.HOUR_OF_DAY, safe / 60)
    calendar.set(Calendar.MINUTE, safe % 60)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

private fun openSettings(context: android.content.Context, intent: Intent) {
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (error: Exception) {
        DoAlarmLog.handlingFailed(id = -1, error)
    }
}
