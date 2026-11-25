package com.evansappstudio.doapp.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeBase
import com.evansappstudio.doapp.alarm.QuickTimeDay
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimeUnit
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.SnoozeKind
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

@Composable
private fun deviceLocale(): Locale = LocalLocale.current.platformLocale

@Composable
fun rememberIs24Hour(): Boolean {
    val context = LocalContext.current
    var is24Hour by remember { mutableStateOf(DateFormat.is24HourFormat(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                is24Hour = DateFormat.is24HourFormat(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.TIME_12_24),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return is24Hour
}

@Composable
fun QuickTimesBanner(
    draftDueAt: Long,
    is24Hour: Boolean,
    onClick: () -> Unit,
) {
    var now by remember(draftDueAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(draftDueAt) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val overdue = draftDueAt <= now
    val text = QuickTimes.bannerText(
        dueAt = draftDueAt,
        now = now,
        zone = ZoneId.systemDefault(),
        is24Hour = is24Hour,
        locale = deviceLocale(),
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button),
        color = if (overdue) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = if (overdue) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun QuickTimesGrid(
    slots: List<QuickTimeSlot>,
    is24Hour: Boolean,
    onApply: (index: Int, base: QuickTimeBase?, day: QuickTimeDay?) -> Unit,
) {
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    val locale = deviceLocale()
    val longPressLabel = stringResource(R.string.quick_times_long_press)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slots.chunked(4).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEachIndexed { column, slot ->
                    val index = rowIndex * 4 + column
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = { onApply(index, null, null) },
                                onLongClick = { menuIndex = index },
                                onLongClickLabel = longPressLabel,
                                role = Role.Button,
                            ),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = QuickTimes.label(slot, is24Hour, locale),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp, vertical = 10.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
    val index = menuIndex
    if (index != null && index in slots.indices) {
        QuickTimeOverrideDialog(
            slot = slots[index],
            is24Hour = is24Hour,
            onDismiss = { menuIndex = null },
            onBase = { base ->
                menuIndex = null
                onApply(index, base, null)
            },
            onDay = { day ->
                menuIndex = null
                onApply(index, null, day)
            },
        )
    }
}

@Composable
fun QuickTimesSettings(
    slots: List<QuickTimeSlot>,
    postpone: PostponeMode,
    is24Hour: Boolean,
    onPostponeChange: (PostponeMode) -> Unit,
    onSlotChange: (Int, QuickTimeSlot) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val locale = deviceLocale()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.quick_times_postpone),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PostponeMode.entries.forEach { mode ->
                FilterChip(
                    selected = postpone == mode,
                    onClick = { onPostponeChange(mode) },
                    label = { Text(postponeLabel(mode)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.quick_times_customise),
            style = MaterialTheme.typography.titleSmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slots.chunked(4).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEachIndexed { column, slot ->
                        val index = rowIndex * 4 + column
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = { editing = index },
                                    role = Role.Button,
                                ),
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Text(
                                text = QuickTimes.label(slot, is24Hour, locale),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp, vertical = 10.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
    val index = editing
    if (index != null && index in slots.indices) {
        QuickTimeSlotDialog(
            index = index,
            slot = slots[index],
            is24Hour = is24Hour,
            onDismiss = { editing = null },
            onSave = { slot ->
                onSlotChange(index, slot)
                editing = null
            },
        )
    }
}

@Composable
private fun postponeLabel(mode: PostponeMode): String = when (mode) {
    PostponeMode.AUTO -> stringResource(R.string.quick_times_postpone_auto)
    PostponeMode.FROM_DUE -> stringResource(R.string.quick_times_postpone_from_due)
    PostponeMode.FROM_NOW -> stringResource(R.string.quick_times_postpone_from_now)
}

@Composable
private fun QuickTimeOverrideDialog(
    slot: QuickTimeSlot,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onBase: (QuickTimeBase) -> Unit,
    onDay: (QuickTimeDay) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(QuickTimes.label(slot, is24Hour, deviceLocale())) },
        text = {
            Column {
                if (slot.kind == SnoozeKind.FROM_NOW) {
                    TextButton(onClick = { onBase(QuickTimeBase.NOW) }) {
                        Text(stringResource(R.string.quick_times_from_now))
                    }
                    TextButton(onClick = { onBase(QuickTimeBase.DUE) }) {
                        Text(stringResource(R.string.quick_times_from_due))
                    }
                } else {
                    TextButton(onClick = { onDay(QuickTimeDay.TODAY) }) {
                        Text(stringResource(R.string.quick_times_today))
                    }
                    TextButton(onClick = { onDay(QuickTimeDay.TOMORROW) }) {
                        Text(stringResource(R.string.quick_times_tomorrow))
                    }
                    TextButton(onClick = { onDay(QuickTimeDay.DUE) }) {
                        Text(stringResource(R.string.quick_times_due_date))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTimeSlotDialog(
    index: Int,
    slot: QuickTimeSlot,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onSave: (QuickTimeSlot) -> Unit,
) {
    var kind by remember(slot) { mutableStateOf(slot.kind) }
    var negative by remember(slot) { mutableStateOf(slot.kind == SnoozeKind.FROM_NOW && slot.amount < 0) }
    var amountText by remember(slot) {
        mutableStateOf(if (slot.kind == SnoozeKind.FROM_NOW) abs(slot.amount).toString() else "10")
    }
    var unit by remember(slot) {
        mutableStateOf(if (slot.kind == SnoozeKind.FROM_NOW) slot.unit else QuickTimeUnit.MINUTE)
    }
    var atHour by remember(slot) {
        mutableIntStateOf(if (slot.kind == SnoozeKind.AT_TIME) slot.amount / 60 else 9)
    }
    var atMinute by remember(slot) {
        mutableIntStateOf(if (slot.kind == SnoozeKind.AT_TIME) slot.amount % 60 else 30)
    }
    var showTime by remember { mutableStateOf(false) }
    val magnitude = amountText.toIntOrNull()
    val draft = when (kind) {
        SnoozeKind.AT_TIME -> QuickTimeSlot.orNull(
            SnoozeKind.AT_TIME,
            atHour * 60 + atMinute,
            QuickTimeUnit.MINUTE,
        )
        SnoozeKind.FROM_NOW -> magnitude?.let { count ->
            QuickTimeSlot.orNull(
                SnoozeKind.FROM_NOW,
                if (negative) -count else count,
                unit,
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_times_slot, index + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == SnoozeKind.AT_TIME,
                        onClick = { kind = SnoozeKind.AT_TIME },
                        label = { Text(stringResource(R.string.snooze_at_time)) },
                    )
                    FilterChip(
                        selected = kind == SnoozeKind.FROM_NOW,
                        onClick = { kind = SnoozeKind.FROM_NOW },
                        label = { Text(stringResource(R.string.snooze_from_now)) },
                    )
                }
                if (kind == SnoozeKind.AT_TIME) {
                    TextButton(onClick = { showTime = true }) {
                        Text(
                            QuickTimes.label(
                                QuickTimeSlot(SnoozeKind.AT_TIME, atHour * 60 + atMinute, QuickTimeUnit.MINUTE),
                                is24Hour,
                                deviceLocale(),
                            ),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !negative,
                            onClick = { negative = false },
                            label = { Text(stringResource(R.string.quick_times_plus)) },
                        )
                        FilterChip(
                            selected = negative,
                            onClick = { negative = true },
                            label = { Text(stringResource(R.string.quick_times_minus)) },
                        )
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { char -> char.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.quick_times_amount)) },
                        supportingText = {
                            if (magnitude == 0) {
                                Text(stringResource(R.string.quick_times_amount_zero))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickTimeUnit.entries.forEach { choice ->
                            FilterChip(
                                selected = unit == choice,
                                onClick = { unit = choice },
                                label = { Text(unitLabel(choice)) },
                            )
                        }
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
        val state = rememberTimePickerState(
            initialHour = atHour,
            initialMinute = atMinute,
            is24Hour = is24Hour,
        )
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

@Composable
private fun unitLabel(unit: QuickTimeUnit): String = when (unit) {
    QuickTimeUnit.MINUTE -> stringResource(R.string.quick_times_minute)
    QuickTimeUnit.HOUR -> stringResource(R.string.quick_times_hour)
    QuickTimeUnit.DAY -> stringResource(R.string.quick_times_day)
    QuickTimeUnit.WEEK -> stringResource(R.string.quick_times_week)
}
