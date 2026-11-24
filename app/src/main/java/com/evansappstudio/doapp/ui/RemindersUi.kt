package com.evansappstudio.doapp.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evansappstudio.doapp.AppContainer
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.alarm.AlarmPermissions
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.data.Reminder
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val EXACT_ALARM_PERMISSION_CHANGED =
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

private val nagChoices = listOf(1, 5, 10, 15, 30, 60, 0)

@Composable
fun ReminderApp(container: AppContainer) {
    val context = LocalContext.current
    val viewModel: ReminderViewModel = viewModel(factory = ReminderViewModelFactory(container))
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var exactAllowed by remember { mutableStateOf(AlarmPermissions.canScheduleExact(context)) }
    var notificationsAllowed by remember { mutableStateOf(AlarmPermissions.canPostNotifications(context)) }

    fun refreshPermissions() {
        exactAllowed = AlarmPermissions.canScheduleExact(context)
        notificationsAllowed = AlarmPermissions.canPostNotifications(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
                container.scope.launch {
                    if (UserUnlock.isUnlocked(context)) {
                        container.scheduler.rescheduleAll()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshPermissions()
                container.scope.launch {
                    if (UserUnlock.isUnlocked(context)) {
                        container.scheduler.rescheduleAll()
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.registerReceiver(
                context,
                permissionReceiver,
                IntentFilter(EXACT_ALARM_PERMISSION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.unregisterReceiver(permissionReceiver)
            }
        }
    }

    if (adding) {
        AddReminderScreen(
            defaultNagMinutes = viewModel.defaultNagMinutes,
            onBack = { adding = false },
            onSave = { title, dueAt, nagMinutes ->
                viewModel.add(title, dueAt, nagMinutes)
                adding = false
            },
            requestNotificationsFirst = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !AlarmPermissions.canPostNotifications(context) &&
                    !viewModel.hasRequestedNotificationPermission()
            },
            onNotificationPrompt = { viewModel.markNotificationPermissionRequested() },
            onPermissionResult = { refreshPermissions() },
        )
    } else {
        ReminderListScreen(
            reminders = reminders,
            showExactAlarmBanner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAllowed,
            showNotificationBanner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationsAllowed,
            onDone = viewModel::markDone,
            onDelete = viewModel::delete,
            onAdd = { adding = true },
            onAllowExactAlarms = { openExactAlarmSettings(context) },
            onOpenNotificationSettings = { openNotificationSettings(context) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderListScreen(
    reminders: List<Reminder>,
    showExactAlarmBanner: Boolean,
    showNotificationBanner: Boolean,
    onDone: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Text(stringResource(R.string.add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showExactAlarmBanner) {
                item {
                    PermissionBanner(
                        message = stringResource(R.string.banner_exact_alarm),
                        button = stringResource(R.string.banner_exact_alarm_button),
                        onClick = onAllowExactAlarms,
                    )
                }
            }
            if (showNotificationBanner) {
                item {
                    PermissionBanner(
                        message = stringResource(R.string.banner_notifications),
                        button = stringResource(R.string.banner_notifications_button),
                        onClick = onOpenNotificationSettings,
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.delete_hint),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (reminders.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.empty_reminders),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(reminders, key = { it.id }) { reminder ->
                ReminderRow(
                    reminder = reminder,
                    onDone = { onDone(reminder.id) },
                    onDelete = { onDelete(reminder.id) },
                )
            }
        }
    }
}

@Composable
private fun PermissionBanner(message: String, button: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun ReminderRow(reminder: Reminder, onDone: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onDelete)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reminder.title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (reminder.isDone) TextDecoration.LineThrough else null,
            )
            Text(text = formatDue(reminder.dueAt), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.list_nag, formatNag(reminder.nagMinutes)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Checkbox(
            checked = reminder.isDone,
            onCheckedChange = if (reminder.isDone) {
                null
            } else {
                { checked -> if (checked) onDone() }
            },
            enabled = !reminder.isDone,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderScreen(
    defaultNagMinutes: Int,
    onBack: () -> Unit,
    onSave: (title: String, dueAt: Long, nagMinutes: Int?) -> Unit,
    requestNotificationsFirst: () -> Boolean,
    onNotificationPrompt: () -> Unit,
    onPermissionResult: () -> Unit,
) {
    val initial = remember { ZonedDateTime.now().plusMinutes(5) }
    var title by rememberSaveable { mutableStateOf("") }
    var dateMillis by rememberSaveable {
        mutableLongStateOf(
            initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
    }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var nagMinutes by rememberSaveable { mutableIntStateOf(defaultNagMinutes) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var saveAfterPermission by rememberSaveable { mutableStateOf(false) }

    val dueAt = remember(dateMillis, hour, minute) { combineDue(dateMillis, hour, minute) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        onPermissionResult()
        if (saveAfterPermission && title.isNotBlank()) {
            onSave(title, dueAt, nagMinutes.takeIf { it != 0 })
        }
        saveAfterPermission = false
    }

    fun commit() {
        if (title.isBlank()) return
        if (requestNotificationsFirst()) {
            saveAfterPermission = true
            onNotificationPrompt()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onSave(title, dueAt, nagMinutes.takeIf { it != 0 })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_reminder)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(text = stringResource(R.string.due_preview, formatDue(dueAt)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showDate = true }) { Text(stringResource(R.string.pick_date)) }
                Button(onClick = { showTime = true }) { Text(stringResource(R.string.pick_time)) }
            }
            Text(text = stringResource(R.string.nag_label))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                nagChoices.forEach { choice ->
                    FilterChip(
                        selected = nagMinutes == choice,
                        onClick = { nagMinutes = choice },
                        label = {
                            Text(
                                if (choice == 0) {
                                    stringResource(R.string.nag_off)
                                } else {
                                    stringResource(R.string.nag_minutes, choice)
                                },
                            )
                        },
                    )
                }
            }
            Button(onClick = { commit() }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dateMillis = it }
                    showDate = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    hour = state.hour
                    minute = state.minute
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

private fun combineDue(dateMillis: Long, hour: Int, minute: Int): Long {
    val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return ZonedDateTime.of(date, LocalTime.of(hour, minute), ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

private fun formatDue(dueAt: Long): String {
    return DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(dueAt))
}

private fun formatNag(nagMinutes: Int?): String = when (nagMinutes) {
    null -> "Off"
    else -> "$nagMinutes min"
}

private fun openExactAlarmSettings(context: Context) {
    val intent = Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM").apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(intent)
    } catch (error: Exception) {
        DoAlarmLog.handlingFailed(id = -1, error)
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    try {
        context.startActivity(intent)
    } catch (error: Exception) {
        DoAlarmLog.handlingFailed(id = -1, error)
    }
}
