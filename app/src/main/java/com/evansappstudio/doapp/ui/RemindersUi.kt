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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import com.evansappstudio.doapp.alarm.ExactAlarmPolicy
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeBase
import com.evansappstudio.doapp.alarm.QuickTimeDay
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.RepeatText
import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.RepeatChoice
import com.evansappstudio.doapp.data.RepeatPresets
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.RepeatRule
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val EXACT_ALARM_PERMISSION_CHANGED =
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

private const val SCREEN_LIST = "list"
private const val SCREEN_ADD = "add"
private const val SCREEN_EDIT = "edit"
private const val SCREEN_SNOOZE = "snooze"

private val nagChoices = listOf(1, 5, 10, 15, 30, 60, 0)

@Composable
fun ReminderApp(
    container: AppContainer,
    openRequest: Long?,
    onOpenRequestConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ReminderViewModel = viewModel(factory = ReminderViewModelFactory(container))
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val quickTimes by viewModel.quickTimes.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(SCREEN_LIST) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
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
                viewModel.reloadQuickTimes()
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

    LaunchedEffect(openRequest) {
        val id = openRequest ?: return@LaunchedEffect
        viewModel.reloadQuickTimes()
        editId = id
        screen = SCREEN_EDIT
        onOpenRequestConsumed()
    }

    when (screen) {
        SCREEN_ADD -> ReminderFormScreen(
            heading = stringResource(R.string.add_reminder),
            formKey = "add",
            initialTitle = "",
            initialDueAt = null,
            initialNagMinutes = viewModel.defaultNagMinutes,
            saveEnabled = true,
            onBack = { screen = SCREEN_LIST },
            onSave = { title, dueAt, nagMinutes, rule, _ ->
                viewModel.add(title, dueAt, nagMinutes, rule)
                screen = SCREEN_LIST
            },
            promptMode = viewModel::repeatPromptMode,
            onPromptMode = viewModel::setRepeatPromptMode,
            requestNotificationsFirst = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !AlarmPermissions.canPostNotifications(context) &&
                    !viewModel.hasRequestedNotificationPermission()
            },
            onNotificationPrompt = { viewModel.markNotificationPermissionRequested() },
            onPermissionResult = { refreshPermissions() },
            quickSlots = quickTimes.slots,
            postpone = quickTimes.postpone,
            onQuickSlotChange = viewModel::setQuickTimeSlot,
            onPostponeChange = viewModel::setPostponeMode,
        )
        SCREEN_EDIT -> EditReminderScreen(
            viewModel = viewModel,
            editId = editId,
            quickSlots = quickTimes.slots,
            postpone = quickTimes.postpone,
            onQuickSlotChange = viewModel::setQuickTimeSlot,
            onPostponeChange = viewModel::setPostponeMode,
            onBack = { screen = SCREEN_LIST },
        )
        SCREEN_SNOOZE -> SnoozeSettingsScreen(
            viewModel = viewModel,
            onBack = { screen = SCREEN_LIST },
        )
        else -> ReminderListScreen(
            reminders = reminders,
            showExactAlarmBanner = ExactAlarmPolicy.shouldShowExactAlarmBanner(
                Build.VERSION.SDK_INT,
                exactAllowed,
            ),
            showNotificationBanner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !notificationsAllowed,
            onDone = viewModel::markDone,
            onDelete = viewModel::delete,
            onOpen = { id ->
                viewModel.reloadQuickTimes()
                editId = id
                screen = SCREEN_EDIT
            },
            onAdd = {
                viewModel.reloadQuickTimes()
                screen = SCREEN_ADD
            },
            onSnoozeSettings = { screen = SCREEN_SNOOZE },
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
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onSnoozeSettings: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onSnoozeSettings) {
                        Text(stringResource(R.string.snooze_settings_button))
                    }
                },
            )
        },
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
                    onOpen = { onOpen(reminder.id) },
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
private fun ReminderRow(
    reminder: Reminder,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    val is24Hour = rememberIs24Hour()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
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
            val repeatLine = reminder.repeatRule()?.let { rule ->
                RepeatText.describe(
                    rule,
                    reminder.dueAt,
                    ZoneId.systemDefault(),
                    is24Hour,
                    deviceLocale(),
                )
            }
            if (repeatLine != null) {
                Text(text = repeatLine, style = MaterialTheme.typography.bodySmall)
            }
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

@Composable
private fun EditReminderScreen(
    viewModel: ReminderViewModel,
    editId: Long?,
    quickSlots: List<QuickTimeSlot>,
    postpone: PostponeMode,
    onQuickSlotChange: (Int, QuickTimeSlot) -> Unit,
    onPostponeChange: (PostponeMode) -> Unit,
    onBack: () -> Unit,
) {
    var reminder by remember(editId) { mutableStateOf<Reminder?>(null) }
    var checked by remember(editId) { mutableStateOf(false) }
    LaunchedEffect(editId) {
        val id = editId
        reminder = if (id == null) null else viewModel.reminderOrNull(id)
        checked = true
    }
    when {
        !checked -> Unit
        reminder == null -> MissingReminderScreen(onBack)
        else -> {
            val current = reminder!!
            ReminderFormScreen(
                heading = stringResource(R.string.edit_reminder),
                formKey = current.id,
                initialTitle = current.title,
                initialDueAt = current.dueAt,
                initialNagMinutes = current.nagMinutes ?: 0,
                initialRule = current.repeatRule(),
                initialPatternDueAt = current.patternDueAt,
                saveEnabled = !current.isDone,
                onBack = onBack,
                onSave = { title, dueAt, nagMinutes, rule, shift ->
                    viewModel.update(current.id, title, dueAt, nagMinutes, rule, shift)
                    onBack()
                },
                promptMode = viewModel::repeatPromptMode,
                onPromptMode = viewModel::setRepeatPromptMode,
                requestNotificationsFirst = { false },
                onNotificationPrompt = {},
                onPermissionResult = {},
                quickSlots = quickSlots,
                postpone = postpone,
                onQuickSlotChange = onQuickSlotChange,
                onPostponeChange = onPostponeChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingReminderScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_reminder)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Text(
            text = stringResource(R.string.missing_reminder),
            modifier = Modifier.padding(padding).padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderFormScreen(
    heading: String,
    formKey: Any,
    initialTitle: String,
    initialDueAt: Long?,
    initialNagMinutes: Int,
    initialRule: RepeatRule? = null,
    initialPatternDueAt: Long? = null,
    saveEnabled: Boolean,
    onBack: () -> Unit,
    onSave: (title: String, dueAt: Long, nagMinutes: Int?, rule: RepeatRule?, shift: DueShiftMode?) -> Unit,
    promptMode: () -> RepeatPromptMode,
    onPromptMode: (RepeatPromptMode) -> Unit,
    requestNotificationsFirst: () -> Boolean,
    onNotificationPrompt: () -> Unit,
    onPermissionResult: () -> Unit,
    quickSlots: List<QuickTimeSlot>,
    postpone: PostponeMode,
    onQuickSlotChange: (Int, QuickTimeSlot) -> Unit,
    onPostponeChange: (PostponeMode) -> Unit,
) {
    val initialDraft = remember(formKey) { startingDraft(initialDueAt) }
    var title by rememberSaveable(formKey) { mutableStateOf(initialTitle) }
    var draftDueAt by rememberSaveable(formKey) { mutableLongStateOf(initialDraft) }
    var draftExact by rememberSaveable(formKey) { mutableStateOf(false) }
    var nagMinutes by rememberSaveable(formKey) { mutableIntStateOf(initialNagMinutes) }
    var ruleText by rememberSaveable(formKey) { mutableStateOf(RepeatRule.encodeDraft(initialRule)) }
    var patternText by rememberSaveable(formKey) {
        mutableStateOf(initialPatternDueAt?.toString().orEmpty())
    }
    var showDate by rememberSaveable(formKey) { mutableStateOf(false) }
    var showTime by rememberSaveable(formKey) { mutableStateOf(false) }
    var openTimeAfterDate by rememberSaveable(formKey) { mutableStateOf(false) }
    var saveAfterPermission by rememberSaveable(formKey) { mutableStateOf(false) }
    var showRepeatMenu by rememberSaveable(formKey) { mutableStateOf(false) }
    var showCustom by rememberSaveable(formKey) { mutableStateOf(false) }
    var showDuePrompt by rememberSaveable(formKey) { mutableStateOf(false) }
    var showPatternWarning by rememberSaveable(formKey) { mutableStateOf(false) }
    val is24Hour = rememberIs24Hour()
    val locale = deviceLocale()
    val draftRule = RepeatRule.decodeDraft(ruleText)
    val patternDueAt = patternText.toLongOrNull()

    val dueAt = if (draftExact) draftDueAt else dueToSave(initialDueAt, draftDueAt)

    fun moveDraftDue(newDue: Long, exact: Boolean) {
        val zone = ZoneId.systemDefault()
        if (initialDueAt == null && draftRule != null) {
            val retargeted = RepeatPresets.retargetIfPreset(draftRule, draftDueAt, newDue, zone)
            if (retargeted != null) {
                ruleText = RepeatRule.encodeDraft(retargeted)
                patternText = newDue.toString()
            }
        }
        draftDueAt = newDue
        draftExact = exact
    }

    fun applyDate(dateMillis: Long) {
        val zone = ZoneId.systemDefault()
        val current = Instant.ofEpochMilli(draftDueAt).atZone(zone)
        val date = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val next = ZonedDateTime.of(date, LocalTime.of(current.hour, current.minute), zone)
            .toInstant()
            .toEpochMilli()
        moveDraftDue(next, exact = false)
    }

    fun applyClock(hour: Int, minute: Int) {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(draftDueAt).atZone(zone).toLocalDate()
        val next = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone)
            .toInstant()
            .toEpochMilli()
        moveDraftDue(next, exact = false)
    }

    fun applyQuick(index: Int, base: QuickTimeBase?, day: QuickTimeDay?) {
        val slot = quickSlots.getOrNull(index) ?: return
        val next = QuickTimes.apply(
            slot = slot,
            draftDueAt = draftDueAt,
            now = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            postpone = postpone,
            base = base,
            day = day,
        )
        moveDraftDue(next, exact = true)
    }

    fun persist(shift: DueShiftMode?) {
        onSave(title, dueAt, nagMinutes.takeIf { it != 0 }, draftRule, shift)
    }

    fun finishSave() {
        if (title.isBlank()) return
        if (saveNeedsPrompt(initialDueAt, dueAt, draftRule, promptMode() == RepeatPromptMode.PROMPT)) {
            showDuePrompt = true
            return
        }
        val shift = dueShiftForSave(
            initialDueAt,
            dueAt,
            draftRule,
            prompts = promptMode() == RepeatPromptMode.PROMPT,
        )
        persist(shift)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        onPermissionResult()
        if (saveAfterPermission) finishSave()
        saveAfterPermission = false
    }

    fun commit() {
        if (title.isBlank()) return
        if (requestNotificationsFirst()) {
            saveAfterPermission = true
            onNotificationPrompt()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finishSave()
        }
    }

    if (showCustom) {
        CustomRepeatScreen(
            initial = draftRule,
            draftDueAt = draftDueAt,
            patternDueAt = patternDueAt,
            is24Hour = is24Hour,
            onAccept = { accepted ->
                ruleText = RepeatRule.encodeDraft(accepted)
                patternText = if (accepted.from == com.evansappstudio.doapp.data.RepeatFrom.COMPLETION) {
                    ""
                } else {
                    patternText.ifBlank { draftDueAt.toString() }
                }
                showCustom = false
            },
            onBack = { showCustom = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(heading) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
                actions = {
                    val repeating = draftRule != null
                    TextButton(onClick = { showRepeatMenu = true }) {
                        Text(
                            text = stringResource(R.string.repeat_button),
                            color = if (repeating) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
            Text(
                text = stringResource(R.string.quick_times),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuickTimesBanner(
                    draftDueAt = draftDueAt,
                    is24Hour = is24Hour,
                    onClick = {
                        openTimeAfterDate = true
                        showDate = true
                    },
                    modifier = Modifier.weight(1f),
                )
                val offPattern = draftRule?.from == com.evansappstudio.doapp.data.RepeatFrom.DUE_DATE &&
                    patternDueAt != null &&
                    patternDueAt != dueAt
                if (offPattern) {
                    TextButton(onClick = { showPatternWarning = true }) {
                        Text(stringResource(R.string.repeat_warning))
                    }
                }
            }
            if (draftRule != null) {
                Text(
                    text = RepeatText.describe(
                        draftRule,
                        dueAt,
                        ZoneId.systemDefault(),
                        is24Hour,
                        locale,
                    ),
                )
            }
            QuickTimesGrid(
                slots = quickSlots,
                is24Hour = is24Hour,
                onApply = { index, base, day -> applyQuick(index, base, day) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    openTimeAfterDate = false
                    showDate = true
                }) { Text(stringResource(R.string.pick_date)) }
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
            Button(onClick = { commit() }, enabled = saveEnabled && title.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
            QuickTimesSettings(
                slots = quickSlots,
                postpone = postpone,
                is24Hour = is24Hour,
                onPostponeChange = onPostponeChange,
                onSlotChange = onQuickSlotChange,
            )
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = utcDateMillis(draftDueAt))
        DatePickerDialog(
            onDismissRequest = {
                showDate = false
                openTimeAfterDate = false
            },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { applyDate(it) }
                    showDate = false
                    if (openTimeAfterDate) {
                        openTimeAfterDate = false
                        showTime = true
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDate = false
                    openTimeAfterDate = false
                }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val zoned = Instant.ofEpochMilli(draftDueAt).atZone(ZoneId.systemDefault())
        val state = rememberTimePickerState(
            initialHour = zoned.hour,
            initialMinute = zoned.minute,
            is24Hour = is24Hour,
        )
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    applyClock(state.hour, state.minute)
                    showTime = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.cancel)) }
            },
            text = { TimePicker(state = state) },
        )
    }

    if (showRepeatMenu) {
        RepeatMenuDialog(
            dueAt = draftDueAt,
            rule = draftRule,
            patternDueAt = patternDueAt,
            is24Hour = is24Hour,
            onSelect = { choice ->
                showRepeatMenu = false
                if (choice == RepeatChoice.CUSTOM) {
                    showCustom = true
                } else if (choice == RepeatChoice.OFF) {
                    ruleText = ""
                    patternText = ""
                } else {
                    val selected = RepeatText.presetRule(choice, draftDueAt, ZoneId.systemDefault())
                    ruleText = RepeatRule.encodeDraft(selected)
                    patternText = draftDueAt.toString()
                }
            },
            onDismiss = { showRepeatMenu = false },
        )
    }
    if (showDuePrompt) {
        DueShiftDialog(
            onThis = {
                showDuePrompt = false
                persist(DueShiftMode.THIS)
            },
            onAll = {
                showDuePrompt = false
                persist(DueShiftMode.ALL)
            },
            onAlways = {
                showDuePrompt = false
                onPromptMode(RepeatPromptMode.ALWAYS_THIS)
                persist(DueShiftMode.THIS)
            },
            onDismiss = { showDuePrompt = false },
        )
    }
    if (showPatternWarning) {
        PatternWarningDialog(
            onApply = {
                showPatternWarning = false
                persist(DueShiftMode.ALL)
            },
            onDismiss = { showPatternWarning = false },
        )
    }
}

/**
 * Keeps the stored due when the form is still on that minute. Debug adds and
 * snoozes carry seconds the minute picker cannot show; saving a title or nag
 * change must not look like a new due time.
 */
private fun dueToSave(initialDueAt: Long?, edited: Long): Long {
    if (initialDueAt == null) return edited
    val zone = ZoneId.systemDefault()
    val original = Instant.ofEpochMilli(initialDueAt).atZone(zone)
    val next = Instant.ofEpochMilli(edited).atZone(zone)
    val sameMinute = original.toLocalDate() == next.toLocalDate() &&
        original.hour == next.hour &&
        original.minute == next.minute
    return if (sameMinute) initialDueAt else edited
}

/** New reminders start at now plus 5 minutes, truncated to the minute the picker can show. */
private fun startingDraft(initialDueAt: Long?): Long {
    if (initialDueAt != null) return initialDueAt
    val zoned = ZonedDateTime.now().plusMinutes(5)
    return ZonedDateTime.of(
        zoned.toLocalDate(),
        LocalTime.of(zoned.hour, zoned.minute),
        ZoneId.systemDefault(),
    ).toInstant().toEpochMilli()
}

private fun utcDateMillis(dueAt: Long): Long {
    val date = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
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
