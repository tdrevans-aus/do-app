package com.evansappstudio.doapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.backup.BackupDecode
import com.evansappstudio.doapp.backup.BackupMode
import com.evansappstudio.doapp.backup.BackupUi
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    ui: BackupUi,
    currentCount: Int,
    onBack: () -> Unit,
    onBackupNow: () -> Unit,
    onChooseFolder: () -> Unit,
    onStopFolder: () -> Unit,
    onRestore: () -> Unit,
    onApply: (BackupMode, Set<String>?) -> Unit,
    onDismissPreview: () -> Unit,
    onDismissError: () -> Unit,
) {
    val preview = ui.preview
    var selected by remember(preview?.exportedAt) { mutableStateOf(emptyList<String>()) }
    var confirmReplace by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (preview == null) {
                            stringResource(R.string.backup)
                        } else {
                            stringResource(R.string.backup_restore)
                        },
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        if (preview != null) onDismissPreview() else onBack()
                    }) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        if (preview == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (ui.lastSuccessAt > 0L) {
                        stringResource(R.string.backup_last, formatBackupTime(ui.lastSuccessAt))
                    } else {
                        stringResource(R.string.backup_never)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (ui.lastFailed) {
                    Text(text = stringResource(R.string.backup_failed_attempt))
                }
                Button(onClick = onBackupNow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_now))
                }
                Button(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_choose_folder))
                }
                if (ui.folderSet) {
                    Text(text = stringResource(R.string.backup_folder_set))
                    TextButton(onClick = onStopFolder) {
                        Text(stringResource(R.string.backup_stop_folder))
                    }
                }
                Button(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_restore))
                }
                Text(text = stringResource(R.string.backup_explain))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.backup_preview_count, preview.reminders.size))
                        Text(stringResource(R.string.backup_exported, formatBackupTime(preview.exportedAt)))
                    }
                }
                items(preview.reminders, key = { it.uuid }) { reminder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = reminder.uuid in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) {
                                    selected + reminder.uuid
                                } else {
                                    selected - reminder.uuid
                                }
                            },
                        )
                        Text(text = reminder.title.ifBlank { stringResource(R.string.empty_reminders) })
                    }
                }
                item {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { confirmReplace = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.backup_replace)) }
                        Button(
                            onClick = { onApply(BackupMode.MERGE, null) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.backup_merge)) }
                        Button(
                            onClick = { onApply(BackupMode.MERGE, selected.toSet()) },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.backup_bring_in)) }
                    }
                }
            }
        }
    }
    if (confirmReplace && preview != null) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.backup_replace)) },
            text = { Text(stringResource(R.string.backup_replace_confirm, currentCount.coerceAtLeast(0))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    onApply(BackupMode.REPLACE, null)
                }) { Text(stringResource(R.string.backup_replace_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    val error = ui.error
    if (error != null && preview == null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            text = { Text(backupErrorText(error)) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

@Composable
private fun backupErrorText(reason: String): String = when (reason) {
    BackupDecode.SCHEMA -> stringResource(R.string.backup_error_schema)
    BackupDecode.APP -> stringResource(R.string.backup_error_app)
    else -> stringResource(R.string.backup_error_corrupt)
}

private fun formatBackupTime(epochMs: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return formatter.format(Date(epochMs))
}
