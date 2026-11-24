package com.evansappstudio.doapp.ui

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
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeLabels
import com.evansappstudio.doapp.alarm.SnoozeOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSettingsScreen(
    viewModel: ReminderViewModel,
    onBack: () -> Unit,
) {
    val options by viewModel.snoozeOptions.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
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
        }
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
