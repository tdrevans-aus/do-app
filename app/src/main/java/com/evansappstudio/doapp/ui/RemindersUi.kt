package com.evansappstudio.doapp.ui

import android.Manifest
import android.content.ActivityNotFoundException
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
import androidx.compose.material3.AlertDialog
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
import com.evansappstudio.doapp.capture.CaptureEntry
import com.evansappstudio.doapp.capture.LaunchRequest
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.evansappstudio.doapp.backup.BackupNames
import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.alarm.AlarmPermissions
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.AlertPermissions
import com.evansappstudio.doapp.alarm.AlertPolicy
import com.evansappstudio.doapp.alarm.DateParser
import com.evansappstudio.doapp.alarm.alert
import com.evansappstudio.doapp.alarm.ExactAlarmPolicy
import com.evansappstudio.doapp.alarm.HealthActionKind
import com.evansappstudio.doapp.alarm.HealthFix
import com.evansappstudio.doapp.alarm.HealthIntents
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
import com.evansappstudio.doapp.data.WeekStart
import com.evansappstudio.doapp.catalogue.CardKind
import com.evansappstudio.doapp.catalogue.KeywordMatch
import com.evansappstudio.doapp.catalogue.LocalItem
import com.evansappstudio.doapp.catalogue.PlannedAdd
import kotlinx.coroutines.launch
import com.evansappstudio.doapp.alarm.Approval
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
private const val SCREEN_HEALTH = "health"
private const val SCREEN_UNLOCK = "unlock"
private const val SCREEN_BACKUP = "backup"
private const val SCREEN_AREAS = "sysd_areas"
private const val SCREEN_PICKS = "sysd_picks"
private const val SCREEN_CATALOGUE = "catalogue"
private const val SCREEN_ASSISTANTS = "assistants"

private val nagChoices = listOf(1, 5, 10, 15, 30, 60, 0)

@Composable
fun ReminderApp(
    container: AppContainer,
    openRequest: Long?,
    onOpenRequestConsumed: () -> Unit,
    launchRequest: LaunchRequest? = null,
    onLaunchRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: ReminderViewModel = viewModel(factory = ReminderViewModelFactory(container))
    val chooseAlert = rememberAlertChoice(viewModel)
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val quickTimes by viewModel.quickTimes.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(SCREEN_LIST) }
    var healthFrom by rememberSaveable { mutableStateOf(SCREEN_LIST) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exactAllowed by remember { mutableStateOf(AlarmPermissions.canScheduleExact(context)) }
    var notificationsAllowed by remember { mutableStateOf(AlarmPermissions.canPostNotifications(context)) }
    val health by container.health.snapshot.collectAsStateWithLifecycle()
    val unlock by container.billing.ui.collectAsStateWithLifecycle()
    val backupUi by container.backup.ui.collectAsStateWithLifecycle()
    var unlockFrom by rememberSaveable { mutableStateOf(SCREEN_LIST) }
    var backupFrom by rememberSaveable { mutableStateOf(SCREEN_LIST) }
    var replaceCount by remember { mutableIntStateOf(0) }
    val sysd: SysdViewModel = viewModel(factory = SysdViewModelFactory(container))
    val card by sysd.card.collectAsStateWithLifecycle()
    val sysdItems by sysd.items.collectAsStateWithLifecycle()
    val sysdAdded by sysd.added.collectAsStateWithLifecycle()

    fun catalogueAdd(item: LocalItem, date: java.time.LocalDate?, from: String, kind: CardKind? = null) {
        sysd.addOne(item, date, kind) { blocked ->
            if (blocked) {
                unlockFrom = from
                screen = SCREEN_UNLOCK
            }
        }
    }

    fun finishPicks(plans: List<PlannedAdd>) {
        sysd.finishOnboarding()
        sysd.addPlans(plans)
        screen = SCREEN_LIST
    }

    // A Done on a catalogue reminder (from the list or a notification) can bring a related card.
    LaunchedEffect(reminders) { sysd.refreshCard() }

    // First run steps 3 and 4: once, the first time there is an active reminder
    // (after the first reminder is saved, or on first launch with reminders).
    LaunchedEffect(screen, reminders) {
        if (screen == SCREEN_LIST && reminders.any { !it.isDone } && sysd.onboardingPending()) {
            sysd.startOnboarding()
            screen = SCREEN_AREAS
        }
    }

    var addEntry by rememberSaveable { mutableStateOf(CaptureEntry.APP.name) }
    var addText by rememberSaveable { mutableStateOf("") }
    var addNonce by rememberSaveable { mutableIntStateOf(0) }

    fun openAdd(entry: CaptureEntry, text: String?) {
        viewModel.reloadQuickTimes()
        addEntry = entry.name
        addText = text.orEmpty()
        addNonce++
        screen = SCREEN_ADD
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        container.scope.launch { container.backup.exportToUri(uri) }
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        container.scope.launch {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (error: Throwable) {
                null
            }
            if (bytes == null) {
                container.backup.preview(byteArrayOf())
            } else {
                container.backup.preview(bytes)
            }
        }
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        container.scope.launch { container.backup.rememberTree(uri) }
    }

    LaunchedEffect(backupUi.preview?.exportedAt) {
        if (backupUi.preview != null) {
            replaceCount = container.backup.reminderCount()
        }
    }

    fun suggestedExportName(): String = BackupNames.suggested(System.currentTimeMillis(), ZoneId.systemDefault())

    fun openBackup(from: String) {
        backupFrom = from
        screen = SCREEN_BACKUP
    }

    fun openUnlock(from: String) {
        unlockFrom = from
        screen = SCREEN_UNLOCK
    }

    fun refreshPermissions() {
        exactAllowed = AlarmPermissions.canScheduleExact(context)
        notificationsAllowed = AlarmPermissions.canPostNotifications(context)
    }

    fun openHealth(from: String) {
        healthFrom = from
        screen = SCREEN_HEALTH
    }

    fun applyHealthFix(fix: HealthFix) {
        when (fix.kind) {
            HealthActionKind.RESTORE_ALARMS -> container.scope.launch { container.health.restoreAlarms() }
            HealthActionKind.REENABLE_BOOT -> container.scope.launch { container.health.reenableBootReceiver() }
            HealthActionKind.CLEAR_LATE -> container.scope.launch { container.health.clearLate() }
            HealthActionKind.BACKUP -> openBackup(screen)
            else -> HealthIntents.open(context, fix)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
                viewModel.reloadQuickTimes()
                sysd.reload()
                container.scope.launch {
                    if (UserUnlock.isUnlocked(context)) {
                        container.scheduler.rescheduleAll()
                    }
                    container.health.refresh()
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

    LaunchedEffect(launchRequest) {
        val request = launchRequest ?: return@LaunchedEffect
        when (request) {
            is LaunchRequest.Add -> openAdd(request.entry, request.text)
            LaunchRequest.Today -> screen = SCREEN_LIST
        }
        onLaunchRequestConsumed()
    }

    if (unlock.celebrate) {
        AlertDialog(
            onDismissRequest = { container.billing.clearCelebrate() },
            text = { Text(stringResource(R.string.paywall_active)) },
            confirmButton = {
                TextButton(onClick = { container.billing.clearCelebrate() }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    when (screen) {
        SCREEN_ADD -> ReminderFormScreen(
            heading = stringResource(R.string.add_reminder),
            formKey = "add-$addNonce",
            initialTitle = addText,
            initialDueAt = null,
            initialNagMinutes = viewModel.defaultNagMinutes,
            saveEnabled = true,
            onBack = { screen = SCREEN_LIST },
            initialAlert = viewModel.defaultAlertLevel(),
            parseDates = viewModel.parseDatesEnabled(),
            onParseDates = viewModel::setParseDatesEnabled,
            related = { typed -> KeywordMatch.related(typed, sysdItems, sysdAdded) },
            onAddRelated = { item, date -> catalogueAdd(item, date, SCREEN_ADD) },
            onSave = { title, dueAt, nagMinutes, rule, _, alert ->
                val entry = CaptureEntry.fromName(addEntry) ?: CaptureEntry.APP
                viewModel.add(entry, title, dueAt, nagMinutes, rule, alert) { created ->
                    screen = if (created) SCREEN_LIST else SCREEN_UNLOCK.also { unlockFrom = SCREEN_ADD }
                }
            },
            promptMode = viewModel::repeatPromptMode,
            onPromptMode = viewModel::setRepeatPromptMode,
            weekStart = viewModel.weekStart(),
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
            unlock = unlock,
            onBack = { screen = SCREEN_LIST },
            onOpenHealth = { openHealth(SCREEN_SNOOZE) },
            onUnlock = { openUnlock(SCREEN_SNOOZE) },
            suggestionsOn = sysd::suggestionsOn,
            onSuggestionsOn = sysd::setSuggestionsOn,
            onOpenAssistants = if (container.cloudConfig.enabled) {
                { screen = SCREEN_ASSISTANTS }
            } else {
                null
            },
        )
        SCREEN_ASSISTANTS -> {
            val cloud = container.cloud
            if (cloud == null) {
                LaunchedEffect(Unit) { screen = SCREEN_SNOOZE }
            } else {
                val cloudUi by cloud.ui.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { if (cloud.hasIdentity()) cloud.refreshAgents() }
                AssistantsScreen(
                    ui = cloudUi,
                    held = reminders.filter { it.approval == Approval.PENDING.name && !it.isDone },
                    onBack = {
                        cloud.clearPairing()
                        screen = SCREEN_SNOOZE
                    },
                    onConnect = { container.scope.launch { cloud.connectAssistant() } },
                    onRefresh = { container.scope.launch { cloud.refreshAgents() } },
                    onDisconnect = { agentId -> container.scope.launch { cloud.disconnect(agentId) } },
                    onAssistantsEnabled = { on -> container.scope.launch { cloud.setAssistantsEnabled(on) } },
                    onAskBefore = cloud::setAskBeforeRingThrough,
                    onDecision = { id, decision -> container.scope.launch { cloud.resolveApproval(id, decision) } },
                )
            }
        }
        SCREEN_UNLOCK -> {
            val activity = context as? android.app.Activity
            // First run picks waiting on the paywall (kept in the view model across rotation).
            val waiting = sysd.pendingPlans
            UnlockScreen(
                state = unlock,
                onBack = {
                    // Back never adds. From picks it returns to step 4 with the ticks as they were
                    // (after buying, Add there adds them all).
                    sysd.pendingPlans = null
                    screen = unlockFrom
                },
                onBuy = { if (activity != null) container.startPurchase(activity) },
                onRestore = { container.billing.restore() },
                onManage = { openSubscriptionPage(context) },
                onKeepFree = if (waiting == null) {
                    null
                } else {
                    {
                        sysd.pendingPlans = null
                        finishPicks(waiting)
                    }
                },
            )
        }
        SCREEN_AREAS -> AreasScreen(
            initial = sysd.areas.collectAsStateWithLifecycle().value,
            onNext = { areas ->
                sysd.setAreas(areas)
                screen = SCREEN_PICKS
            },
            onSkip = {
                sysd.skipOnboarding()
                screen = SCREEN_LIST
            },
        )
        SCREEN_PICKS -> PicksScreen(
            viewModel = sysd,
            onAdd = { plans, overCap ->
                if (overCap) {
                    sysd.pendingPlans = plans
                    unlockFrom = SCREEN_PICKS
                    screen = SCREEN_UNLOCK
                } else {
                    finishPicks(plans)
                }
            },
            onSkip = {
                sysd.skipOnboarding()
                screen = SCREEN_LIST
            },
        )
        SCREEN_CATALOGUE -> CatalogueScreen(
            viewModel = sysd,
            onAdd = { item, date -> catalogueAdd(item, date, SCREEN_CATALOGUE) },
            onRemove = { item -> sysd.remove(item.id) },
            bottomBar = {
                DoTabs(DoTab.CATALOGUE) { tab -> if (tab == DoTab.TODAY) screen = SCREEN_LIST }
            },
        )
        SCREEN_BACKUP -> BackupScreen(
            ui = backupUi,
            currentCount = replaceCount,
            onBack = { screen = backupFrom },
            onBackupNow = {
                if (!backupUi.folderSet) {
                    exportLauncher.launch(suggestedExportName())
                } else {
                    container.scope.launch { container.backup.backupNow() }
                }
            },
            onChooseFolder = { treeLauncher.launch(null) },
            onStopFolder = { container.scope.launch { container.backup.configureFolder("", clearSuccess = false) } },
            onRestore = { openLauncher.launch(arrayOf("application/json", "*/*")) },
            onApply = { mode, uuids ->
                container.scope.launch {
                    container.backup.applyPreview(mode, uuids)
                }
            },
            onDismissPreview = { container.backup.clearPreview() },
            onDismissError = { container.backup.clearError() },
        )
        SCREEN_HEALTH -> HealthCheckScreen(
            report = health,
            onBack = { screen = healthFrom },
            onFix = ::applyHealthFix,
            onTest = { container.scope.launch { container.health.armTest() } },
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
            onAdd = { openAdd(CaptureEntry.APP, null) },
            onSnoozeSettings = { screen = SCREEN_SNOOZE },
            showHealthWarning = health.badge,
            onHealth = { openHealth(SCREEN_LIST) },
            onExport = { exportLauncher.launch(suggestedExportName()) },
            onBackup = { openBackup(SCREEN_LIST) },
            onAllowExactAlarms = { openExactAlarmSettings(context) },
            onOpenNotificationSettings = { openNotificationSettings(context) },
            onSetAlert = viewModel::setAlertLevel,
            chooseAlert = chooseAlert,
            card = card?.let { shown ->
                {
                    val doneTitle = shown.forItemId?.let { id -> container.catalogue.catalogue.item(id)?.title }
                    TodayCard(
                        card = shown,
                        doneTitle = doneTitle,
                        onAdd = { item, date -> catalogueAdd(item, date, SCREEN_LIST, shown.kind) },
                        onOpenCatalogue = {
                            sysd.followed(shown.kind)
                            screen = SCREEN_CATALOGUE
                        },
                        onNotNow = { sysd.notNow(shown.kind) },
                        onStop = sysd::stopSuggesting,
                    )
                }
            },
            bottomBar = {
                DoTabs(DoTab.TODAY) { tab -> if (tab == DoTab.CATALOGUE) screen = SCREEN_CATALOGUE }
            },
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
    showHealthWarning: Boolean,
    onHealth: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSetAlert: (Long, AlertLevel) -> Unit,
    chooseAlert: (AlertLevel, () -> Unit) -> Unit,
    card: (@Composable () -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val now = remember(reminders) { System.currentTimeMillis() }
    // The Stuff You Should Do card sits below Overdue, at the top of the rest of the list.
    val overdue = reminders.takeWhile { !it.isDone && it.dueAt < now }
    val rest = reminders.drop(overdue.size)
    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    HealthMenuButton(
                        warning = showHealthWarning,
                        onHealth = onHealth,
                        onExport = onExport,
                        onBackup = onBackup,
                    )
                },
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
            if (overdue.isNotEmpty()) {
                item(key = "overdue-heading") {
                    Text(
                        text = stringResource(R.string.sysd_overdue),
                        color = DoLook.Alarm,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(overdue, key = { it.id }) { reminder ->
                ReminderRow(
                    reminder = reminder,
                    onOpen = { onOpen(reminder.id) },
                    onDone = { onDone(reminder.id) },
                    onDelete = { onDelete(reminder.id) },
                    onSetAlert = { level ->
                        chooseAlert(level) { onSetAlert(reminder.id, level) }
                    },
                    overdue = true,
                )
            }
            if (card != null) {
                item(key = "sysd-card") { card() }
            }
            items(rest, key = { it.id }) { reminder ->
                ReminderRow(
                    reminder = reminder,
                    onOpen = { onOpen(reminder.id) },
                    onDone = { onDone(reminder.id) },
                    onDelete = { onDelete(reminder.id) },
                    onSetAlert = { level ->
                        chooseAlert(level) { onSetAlert(reminder.id, level) }
                    },
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
    onSetAlert: (AlertLevel) -> Unit,
    overdue: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val level = reminder.alert()
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
                text = reminderTitle(reminder.title, level),
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (reminder.isDone) TextDecoration.LineThrough else null,
            )
            Text(
                text = formatDue(reminder.dueAt),
                style = MaterialTheme.typography.bodyMedium,
                color = if (overdue) DoLook.Alarm else androidx.compose.ui.graphics.Color.Unspecified,
            )
            if (reminder.source == com.evansappstudio.doapp.alarm.ReminderSource.CATALOGUE.name) {
                CatalogueChip(modifier = Modifier.padding(vertical = 2.dp))
            }
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
        TextButton(onClick = { menu = true }) { Text(stringResource(R.string.alert_button)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            AlertLevel.entries.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        val mark = if (choice == level) "✓ " else ""
                        Text(mark + alertName(choice))
                    },
                    onClick = {
                        menu = false
                        if (choice != level) onSetAlert(choice)
                    },
                )
            }
        }
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
                initialAlert = current.alert(),
                parseDates = viewModel.parseDatesEnabled(),
                onParseDates = viewModel::setParseDatesEnabled,
                saveEnabled = !current.isDone,
                onBack = onBack,
                onSave = { title, dueAt, nagMinutes, rule, shift, alert ->
                    viewModel.update(current.id, title, dueAt, nagMinutes, rule, shift, alert)
                    onBack()
                },
                promptMode = viewModel::repeatPromptMode,
                onPromptMode = viewModel::setRepeatPromptMode,
                weekStart = viewModel.weekStart(),
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
    initialAlert: AlertLevel = AlertLevel.STANDARD,
    parseDates: Boolean = true,
    onParseDates: (Boolean) -> Unit = {},
    saveEnabled: Boolean,
    onBack: () -> Unit,
    onSave: (title: String, dueAt: Long, nagMinutes: Int?, rule: RepeatRule?, shift: DueShiftMode?, alert: AlertLevel) -> Unit,
    promptMode: () -> RepeatPromptMode,
    onPromptMode: (RepeatPromptMode) -> Unit,
    weekStart: WeekStart,
    requestNotificationsFirst: () -> Boolean,
    onNotificationPrompt: () -> Unit,
    onPermissionResult: () -> Unit,
    quickSlots: List<QuickTimeSlot>,
    postpone: PostponeMode,
    onQuickSlotChange: (Int, QuickTimeSlot) -> Unit,
    onPostponeChange: (PostponeMode) -> Unit,
    related: ((String) -> List<LocalItem>)? = null,
    onAddRelated: (LocalItem, java.time.LocalDate?) -> Unit = { _, _ -> },
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
    var parseOn by rememberSaveable(formKey) { mutableStateOf(parseDates) }
    var alertName by rememberSaveable(formKey) { mutableStateOf(initialAlert.name) }
    var keepSource by rememberSaveable(formKey) { mutableStateOf<String?>(null) }
    var showSpanMenu by rememberSaveable(formKey) { mutableStateOf(false) }
    var showAlertMenu by rememberSaveable(formKey) { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val chooseAlert = rememberAlertChoice(viewModel = viewModel(factory = ReminderViewModelFactory(
        (LocalContext.current.applicationContext as com.evansappstudio.doapp.DoApp).container,
    )))
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
        val level = AlertLevel.fromName(alertName) ?: AlertLevel.STANDARD
        onSave(title, dueAt, nagMinutes.takeIf { it != 0 }, draftRule, shift, level)
    }

    val parsed = if (parseOn) {
        DateParser.parse(title, clock, draftDueAt, ZoneId.systemDefault())
    } else {
        null
    }
    LaunchedEffect(title, draftDueAt, parseOn) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            clock = System.currentTimeMillis()
        }
    }

    fun acceptParse(strip: Boolean) {
        val match = parsed ?: return
        if (strip) {
            keepSource = title
            title = match.title
        }
        draftDueAt = match.dueAt
        draftExact = true
        ruleText = RepeatRule.encodeDraft(match.rule)
        patternText = if (match.rule == null || match.rule.from == com.evansappstudio.doapp.data.RepeatFrom.COMPLETION) {
            ""
        } else {
            match.dueAt.toString()
        }
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
            weekStart = weekStart,
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
                    TextButton(onClick = { showAlertMenu = true }) {
                        Text(stringResource(R.string.alert_button))
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
                onValueChange = {
                    title = it
                    if (keepSource != null) keepSource = null
                },
                label = { Text(stringResource(R.string.title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = underlineMatch(parsed),
            )
            if (related != null) {
                RelatedLine(items = related(title), onAdd = onAddRelated)
            }
            if (keepSource != null) {
                TextButton(onClick = {
                    title = keepSource.orEmpty()
                    keepSource = null
                }) { Text(stringResource(R.string.keep_words)) }
            }
            val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            if (parsed != null && !imeOpen) {
                TextButton(onClick = { showSpanMenu = true }) {
                    Text(buildAnnotatedString {
                        append(title)
                        if (parsed.start < parsed.end && parsed.end <= title.length) {
                            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), parsed.start, parsed.end)
                        }
                    })
                }
                TextButton(onClick = { acceptParse(strip = false) }) { Text(stringResource(R.string.set_words)) }
                TextButton(onClick = { acceptParse(strip = true) }) { Text(stringResource(R.string.set_and_remove)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.parse_dates), modifier = Modifier.weight(1f))
                Switch(checked = parseOn, onCheckedChange = {
                    parseOn = it
                    onParseDates(it)
                })
            }
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
            val setLabel = if (parsed == null) null else stringResource(R.string.set_to, parsed.matched)
            val setDetail = parsed?.let { match ->
                buildString {
                    append(QuickTimes.formatDateTime(match.dueAt, ZoneId.systemDefault(), is24Hour, locale))
                    if (match.rule != null) {
                        append(" ")
                        append(
                            RepeatText.describe(
                                match.rule,
                                match.dueAt,
                                ZoneId.systemDefault(),
                                is24Hour,
                                locale,
                            ),
                        )
                    }
                }
            }
            QuickTimesGrid(
                slots = quickSlots,
                is24Hour = is24Hour,
                onApply = { index, base, day -> applyQuick(index, base, day) },
                slotZero = if (setLabel == null || setDetail == null) {
                    null
                } else {
                    SlotZero(label = setLabel, detail = setDetail, onClick = { acceptParse(strip = true) })
                },
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

    if (showAlertMenu) {
        AlertDialog(
            onDismissRequest = { showAlertMenu = false },
            title = { Text(stringResource(R.string.alert_button)) },
            text = {
                val context = LocalContext.current
                Column {
                    val current = AlertLevel.fromName(alertName) ?: AlertLevel.STANDARD
                    val decision = AlertPolicy.decide(
                        current,
                        AlertPermissions.canUseFullScreenIntent(context),
                        AlertPermissions.hasDndAccess(context),
                    )
                    if (decision.reasons.isNotEmpty()) {
                        Text(stringResource(R.string.alert_fallback, decision.effective.name))
                        TextButton(onClick = {
                            openMissingPermission(context, current)
                        }) { Text(stringResource(R.string.alert_open)) }
                    }
                    AlertLevel.entries.forEach { choice ->
                        TextButton(onClick = {
                            showAlertMenu = false
                            chooseAlert(choice) { alertName = choice.name }
                        }) {
                            Text((if (choice.name == alertName) "✓ " else "") + alertName(choice))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAlertMenu = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showSpanMenu) {
        AlertDialog(
            onDismissRequest = { showSpanMenu = false },
            title = { Text(stringResource(R.string.set_words)) },
            text = { Text(stringResource(R.string.set_and_remove)) },
            confirmButton = {
                TextButton(onClick = {
                    showSpanMenu = false
                    acceptParse(strip = false)
                }) { Text(stringResource(R.string.set_words)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSpanMenu = false
                    acceptParse(strip = true)
                }) { Text(stringResource(R.string.set_and_remove)) }
            },
        )
    }
    if (showRepeatMenu) {
        RepeatMenuDialog(
            dueAt = draftDueAt,
            rule = draftRule,
            patternDueAt = patternDueAt,
            is24Hour = is24Hour,
            weekStart = weekStart,
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

private fun reminderTitle(title: String, level: AlertLevel): String {
    val mark = when (level) {
        AlertLevel.CRITICAL -> " !!!"
        AlertLevel.PROMINENT -> " ⏰"
        AlertLevel.STANDARD -> ""
    }
    return title + mark
}

private fun underlineMatch(match: DateParser.Match?): VisualTransformation {
    if (match == null) return VisualTransformation.None
    return VisualTransformation { text ->
        val annotated = androidx.compose.ui.text.AnnotatedString.Builder(text.text).apply {
            if (match.start < match.end && match.end <= text.text.length) {
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), match.start, match.end)
            }
        }.toAnnotatedString()
        TransformedText(annotated, OffsetMapping.Identity)
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

/** Opens Play's management page for Do Unlimited. Does nothing if no app can open it. */
internal fun openSubscriptionPage(context: Context) {
    val url = FreeTier.manageSubscriptionUrl(context.packageName)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser or Play Store: leave the paywall as it is.
    }
}
