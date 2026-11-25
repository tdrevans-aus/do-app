package com.evansappstudio.doapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.RepeatMath
import com.evansappstudio.doapp.alarm.RepeatText
import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.MonthNth
import com.evansappstudio.doapp.data.MonthOn
import com.evansappstudio.doapp.data.MonthTarget
import com.evansappstudio.doapp.data.RepeatChoice
import com.evansappstudio.doapp.data.RepeatFrom
import com.evansappstudio.doapp.data.RepeatPresets
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.RepeatUnit
import com.evansappstudio.doapp.data.WeekDay
import com.evansappstudio.doapp.data.WeekStart
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private val menuChoices = listOf(
    RepeatChoice.OFF,
    RepeatChoice.DAILY,
    RepeatChoice.WEEKDAYS,
    RepeatChoice.WEEKLY,
    RepeatChoice.MONTHLY,
    RepeatChoice.YEARLY,
    RepeatChoice.CUSTOM,
)

@Composable
fun RepeatMenuDialog(
    dueAt: Long,
    rule: RepeatRule?,
    patternDueAt: Long?,
    is24Hour: Boolean,
    weekStart: WeekStart,
    onSelect: (RepeatChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val locale = deviceLocale()
    val selected = RepeatPresets.choice(rule, dueAt, zone)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repeat_menu)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                menuChoices.forEach { choice ->
                    val label = RepeatText.menuLabel(choice, dueAt, zone, is24Hour, locale)
                    TextButton(onClick = { onSelect(choice) }) {
                        Text(if (choice == selected) "✓ $label" else label)
                    }
                    if (choice == selected && choice != RepeatChoice.OFF) {
                        val phrase = nextPhrase(rule, patternDueAt, dueAt, zone, is24Hour, locale, weekStart)
                        if (phrase != null) {
                            Text(
                                text = phrase,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun DueShiftDialog(
    onThis: () -> Unit,
    onAll: () -> Unit,
    onAlways: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repeat_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.repeat_prompt_body))
                TextButton(onClick = onThis) { Text(stringResource(R.string.repeat_only_this)) }
                TextButton(onClick = onAll) { Text(stringResource(R.string.repeat_all_future)) }
                TextButton(onClick = onAlways) { Text(stringResource(R.string.repeat_always_this)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
fun PatternWarningDialog(
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.repeat_warning_title)) },
        text = { Text(stringResource(R.string.repeat_warning_body)) },
        confirmButton = {
            TextButton(onClick = onApply) { Text(stringResource(R.string.repeat_all_future)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRepeatScreen(
    initial: RepeatRule?,
    draftDueAt: Long,
    patternDueAt: Long?,
    is24Hour: Boolean,
    weekStart: WeekStart,
    onAccept: (RepeatRule) -> Unit,
    onBack: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val locale = deviceLocale()
    val dueZoned = Instant.ofEpochMilli(draftDueAt).atZone(zone)
    val start = initial ?: RepeatPresets.daily()
    var interval by remember { mutableIntStateOf(start.interval.coerceIn(RepeatRule.MIN_INTERVAL, RepeatRule.MAX_INTERVAL)) }
    var intervalText by remember { mutableStateOf(interval.toString()) }
    var unit by remember { mutableStateOf(start.unit) }
    var from by remember { mutableStateOf(start.from) }
    var weekDays by remember { mutableStateOf(start.weekDays.ifEmpty { listOf(WeekDay.fromDayOfWeek(dueZoned.dayOfWeek)) }) }
    var monthOn by remember {
        mutableStateOf(start.monthOn ?: MonthOn.DayOfMonth(dueZoned.dayOfMonth))
    }
    val shownDays = if (unit == RepeatUnit.WEEK) weekDays else emptyList()
    val shownMonth = if (unit == RepeatUnit.MONTH) monthOn else null
    val draft = RepeatRule.of(interval, unit, from, shownDays, shownMonth)
    val showOn = from == RepeatFrom.DUE_DATE && (unit == RepeatUnit.WEEK || unit == RepeatUnit.MONTH)

    fun setInterval(value: Int) {
        val clamped = value.coerceIn(RepeatRule.MIN_INTERVAL, RepeatRule.MAX_INTERVAL)
        interval = clamped
        intervalText = clamped.toString()
    }

    fun setUnit(next: RepeatUnit) {
        unit = next
        if (next == RepeatUnit.WEEK && weekDays.isEmpty()) {
            weekDays = listOf(WeekDay.fromDayOfWeek(dueZoned.dayOfWeek))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.repeat_custom)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(
                        onClick = { draft?.let(onAccept) },
                        enabled = draft != null,
                    ) { Text(stringResource(R.string.repeat_accept)) }
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
            Text(stringResource(R.string.repeat_words))
            Text(
                text = draft?.let { RepeatText.describe(it, draftDueAt, zone, is24Hour, locale) }
                    ?: stringResource(R.string.repeat_invalid),
            )
            Text(stringResource(R.string.repeat_how_often))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { setInterval(interval - 1) }) { Text(stringResource(R.string.quick_times_minus)) }
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(2)
                        intervalText = digits
                        val parsed = digits.toIntOrNull()
                        if (parsed != null && parsed in RepeatRule.MIN_INTERVAL..RepeatRule.MAX_INTERVAL) {
                            interval = parsed
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(onClick = { setInterval(interval + 1) }) { Text(stringResource(R.string.quick_times_plus)) }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RepeatUnit.entries.forEach { choice ->
                    FilterChip(
                        selected = unit == choice,
                        onClick = { setUnit(choice) },
                        label = { Text(unitLabel(choice)) },
                    )
                }
            }
            Text(stringResource(R.string.repeat_from))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = from == RepeatFrom.DUE_DATE,
                    onClick = { from = RepeatFrom.DUE_DATE },
                    label = { Text(stringResource(R.string.repeat_from_due)) },
                )
                FilterChip(
                    selected = from == RepeatFrom.COMPLETION,
                    onClick = { from = RepeatFrom.COMPLETION },
                    label = { Text(stringResource(R.string.repeat_from_completion)) },
                )
            }
            if (showOn && unit == RepeatUnit.WEEK) {
                Text(stringResource(R.string.repeat_on))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    weekStart.orderedDays().forEach { day ->
                        FilterChip(
                            selected = day in weekDays,
                            onClick = {
                                weekDays = if (day in weekDays) {
                                    if (weekDays.size == 1) weekDays else weekDays.filter { it != day }
                                } else {
                                    (weekDays + day).distinct().sortedBy { it.ordinal }
                                }
                            },
                            label = { Text(day.name) },
                        )
                    }
                }
            }
            if (showOn && unit == RepeatUnit.MONTH) {
                Text(stringResource(R.string.repeat_on))
                MonthOnEditor(
                    monthOn = monthOn,
                    weekStart = weekStart,
                    onChange = { monthOn = it },
                )
            }
            if (from == RepeatFrom.DUE_DATE && draft != null) {
                val anchor = patternDueAt ?: draftDueAt
                val next = RepeatMath.nextPattern(anchor, draft, zone, weekStart)
                Text(stringResource(R.string.repeat_next))
                Text(RepeatText.nextLine(next, zone, is24Hour, locale))
            }
        }
    }
}

@Composable
private fun MonthOnEditor(
    monthOn: MonthOn,
    weekStart: WeekStart,
    onChange: (MonthOn) -> Unit,
) {
    val style = when (monthOn) {
        is MonthOn.DayOfMonth -> MonthStyle.DOM
        is MonthOn.Nth -> when (monthOn.target) {
            MonthTarget.DAY -> MonthStyle.DAY
            MonthTarget.WEEKDAY -> MonthStyle.WEEKDAY
            else -> MonthStyle.NTH
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MonthStyle.entries.forEach { choice ->
                FilterChip(
                    selected = style == choice,
                    onClick = { onChange(defaultMonth(choice, monthOn)) },
                    label = { Text(monthStyleLabel(choice)) },
                )
            }
        }
        when (val current = monthOn) {
            is MonthOn.DayOfMonth -> DayOfMonthField(current.day, onChange)
            is MonthOn.Nth -> when (current.target) {
                MonthTarget.DAY, MonthTarget.WEEKDAY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = current.which == MonthNth.FIRST,
                            onClick = { onChange(current.copy(which = MonthNth.FIRST)) },
                            label = { Text(MonthNth.FIRST.label()) },
                        )
                        FilterChip(
                            selected = current.which == MonthNth.LAST,
                            onClick = { onChange(current.copy(which = MonthNth.LAST)) },
                            label = { Text(MonthNth.LAST.label()) },
                        )
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MonthNth.entries.forEach { which ->
                            FilterChip(
                                selected = current.which == which,
                                onClick = { onChange(current.copy(which = which)) },
                                label = { Text(which.label()) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        weekStart.orderedDays().forEach { day ->
                            val target = MonthTarget.fromWeekDay(day)
                            FilterChip(
                                selected = current.target == target,
                                onClick = { onChange(current.copy(target = target)) },
                                label = { Text(day.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayOfMonthField(day: Int, onChange: (MonthOn) -> Unit) {
    var text by remember(day) { mutableStateOf(day.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            text = digits
            val parsed = digits.toIntOrNull()
            if (parsed != null && parsed in 1..31) onChange(MonthOn.DayOfMonth(parsed))
        },
        label = { Text(stringResource(R.string.repeat_day_of_month)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private enum class MonthStyle { DOM, NTH, DAY, WEEKDAY }

private fun defaultMonth(style: MonthStyle, current: MonthOn): MonthOn = when (style) {
    MonthStyle.DOM -> when (current) {
        is MonthOn.DayOfMonth -> current
        is MonthOn.Nth -> MonthOn.DayOfMonth(1)
    }
    MonthStyle.NTH -> when (current) {
        is MonthOn.Nth -> if (current.target == MonthTarget.DAY || current.target == MonthTarget.WEEKDAY) {
            MonthOn.Nth(MonthNth.FIRST, MonthTarget.MONDAY)
        } else {
            current
        }
        is MonthOn.DayOfMonth -> MonthOn.Nth(MonthNth.FIRST, MonthTarget.MONDAY)
    }
    MonthStyle.DAY -> MonthOn.Nth(MonthNth.FIRST, MonthTarget.DAY)
    MonthStyle.WEEKDAY -> MonthOn.Nth(MonthNth.FIRST, MonthTarget.WEEKDAY)
}

@Composable
private fun unitLabel(unit: RepeatUnit): String = when (unit) {
    RepeatUnit.MINUTE -> stringResource(R.string.repeat_unit_minute)
    RepeatUnit.HOUR -> stringResource(R.string.repeat_unit_hour)
    RepeatUnit.DAY -> stringResource(R.string.repeat_unit_day)
    RepeatUnit.WEEK -> stringResource(R.string.repeat_unit_week)
    RepeatUnit.MONTH -> stringResource(R.string.repeat_unit_month)
    RepeatUnit.YEAR -> stringResource(R.string.repeat_unit_year)
}

@Composable
private fun monthStyleLabel(style: MonthStyle): String = when (style) {
    MonthStyle.DOM -> stringResource(R.string.repeat_day_of_month)
    MonthStyle.NTH -> stringResource(R.string.repeat_nth_weekday)
    MonthStyle.DAY -> stringResource(R.string.repeat_calendar_day)
    MonthStyle.WEEKDAY -> stringResource(R.string.repeat_weekday_span)
}

private fun nextPhrase(
    rule: RepeatRule?,
    patternDueAt: Long?,
    dueAt: Long,
    zone: ZoneId,
    is24Hour: Boolean,
    locale: Locale,
    weekStart: WeekStart,
): String? {
    if (rule == null || rule.from != RepeatFrom.DUE_DATE) return null
    val anchor = patternDueAt ?: dueAt
    val next = RepeatMath.nextPattern(anchor, rule, zone, weekStart)
    return RepeatText.nextLine(next, zone, is24Hour, locale)
}

/** Due shift chosen by the editor, or null when the due time did not change. */
fun dueShiftForSave(
    initialDueAt: Long?,
    dueAt: Long,
    rule: RepeatRule?,
    prompts: Boolean,
): DueShiftMode? {
    val dueChanged = initialDueAt != null && dueAt != initialDueAt
    if (!dueChanged || rule?.from != RepeatFrom.DUE_DATE) return null
    return if (prompts) null else DueShiftMode.THIS
}

fun saveNeedsPrompt(
    initialDueAt: Long?,
    dueAt: Long,
    rule: RepeatRule?,
    prompts: Boolean,
): Boolean {
    val dueChanged = initialDueAt != null && dueAt != initialDueAt
    return prompts && dueChanged && rule?.from == RepeatFrom.DUE_DATE
}
