package com.evansappstudio.doapp.capture

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.ui.UnlockScreen
import com.evansappstudio.doapp.ui.deviceLocale
import com.evansappstudio.doapp.ui.rememberIs24Hour
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Share to Do (design screen 14): a bottom sheet over the sending app. Saves
 * through [CaptureCommands.saveShare] with Origin(USER, SHARE, caller) and goes
 * back to the sender. Over the free cap it shows the paywall; backing out of the
 * paywall goes back to the sender too.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DoApp).container
        val now = System.currentTimeMillis()
        val draft = CaptureDrafts.fromText(
            raw = sharedText(intent),
            now = now,
            zone = ZoneId.systemDefault(),
            parseDates = container.settings.parseDatesEnabled(),
        )
        if (draft == null) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val caller = callerPackage()
        setContent {
            MaterialTheme {
                ShareSheet(
                    draft = draft,
                    onSave = { title, dueAt, onCap ->
                        // dueAt is null while the parsed (or default) date is kept.
                        container.scope.launch {
                            val result = container.capture.saveShare(
                                draft = draft,
                                title = title,
                                dueAt = dueAt,
                                nagMinutes = container.settings.defaultNagMinutes(),
                                alertLevel = container.settings.defaultAlertLevel(),
                                callerPackage = caller,
                            )
                            runOnUiThread {
                                when (result) {
                                    is CaptureResult.Saved -> {
                                        Toast.makeText(this@ShareActivity, R.string.share_saved, Toast.LENGTH_SHORT).show()
                                        finish()
                                    }
                                    CaptureResult.NeedsUnlock -> onCap()
                                }
                            }
                        }
                    },
                    onCancel = { finish() },
                    unlocked = { container.entitlement.read().unlocked },
                    unlockScreen = { onBack ->
                        val state by container.billing.ui.collectAsStateWithLifecycle()
                        UnlockScreen(
                            state = state,
                            onBack = onBack,
                            onBuy = { container.billing.buy(this) },
                            onRestore = { container.billing.restore() },
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as DoApp).container.billing.onResume()
    }

    /** The sending app, when Android says (Android 14 and later, if the sender shares its identity), else the referrer. */
    private fun callerPackage(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            launchedFromPackage?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val referrer = referrer ?: return null
        if (referrer.scheme != "android-app") return null
        return referrer.host?.takeIf { it.isNotBlank() && it != packageName }
    }

    companion object {
        fun sharedText(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_SEND) return null
            return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        }
    }
}

private enum class ShareChoice { PARSED, TONIGHT, TOMORROW, WEEKEND, CHOSEN }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ShareSheet(
    draft: CaptureDraft,
    onSave: (title: String, dueAt: Long?, onCap: () -> Unit) -> Unit,
    onCancel: () -> Unit,
    unlocked: () -> Boolean,
    unlockScreen: @Composable (onBack: () -> Unit) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(draft.title) }
    var dueAt by rememberSaveable { mutableLongStateOf(draft.dueAt) }
    var choice by rememberSaveable { mutableStateOf(ShareChoice.PARSED) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var showUnlock by rememberSaveable { mutableStateOf(false) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var pickedDay by rememberSaveable { mutableLongStateOf(0L) }
    val zone = ZoneId.systemDefault()
    val is24Hour = rememberIs24Hour()
    val locale = deviceLocale()
    val scope = rememberCoroutineScope()

    if (showUnlock) {
        unlockScreen {
            showUnlock = false
            if (!unlocked()) onCancel()
        }
        return
    }

    fun pick(next: ShareChoice, due: Long) {
        choice = next
        dueAt = due
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = Color(0xFFF7F8FA),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.share_heading), style = MaterialTheme.typography.titleLarge, color = Color(0xFF111827))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.add_field_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = QuickTimes.formatDateTime(dueAt, zone, is24Hour, locale),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF111827),
            )
            val source = when {
                choice != ShareChoice.PARSED -> null
                draft.matched != null -> stringResource(R.string.share_from_words, draft.matched)
                else -> stringResource(R.string.share_no_date)
            }
            if (source != null) {
                Text(source, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4B5563))
            }
            val now = System.currentTimeMillis()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = choice == ShareChoice.TONIGHT,
                    onClick = { pick(ShareChoice.TONIGHT, CaptureTimes.tonight(now, zone)) },
                    label = { Text(stringResource(R.string.share_tonight)) },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
                FilterChip(
                    selected = choice == ShareChoice.TOMORROW,
                    onClick = { pick(ShareChoice.TOMORROW, CaptureTimes.tomorrow(now, zone)) },
                    label = { Text(stringResource(R.string.share_tomorrow)) },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
                FilterChip(
                    selected = choice == ShareChoice.WEEKEND,
                    onClick = { pick(ShareChoice.WEEKEND, CaptureTimes.weekend(now, zone)) },
                    label = { Text(stringResource(R.string.share_weekend)) },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
                FilterChip(
                    selected = choice == ShareChoice.CHOSEN,
                    onClick = { showDate = true },
                    label = { Text(stringResource(R.string.share_choose)) },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
            Button(
                onClick = {
                    saving = true
                    onSave(title.trim(), if (choice == ShareChoice.PARSED) null else dueAt) {
                        saving = false
                        showUnlock = true
                    }
                },
                enabled = !saving && title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.share_save)) }
            TextButton(
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onCancel() } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Text(stringResource(R.string.cancel)) }
        }
    }

    if (showDate) {
        val local = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = local.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis
                    showDate = false
                    if (picked != null) {
                        pickedDay = picked
                        showTime = true
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val current = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalTime()
        val state = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = is24Hour)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val day = Instant.ofEpochMilli(pickedDay).atZone(ZoneOffset.UTC).toLocalDate()
                    pick(ShareChoice.CHOSEN, CaptureTimes.chosen(day, LocalTime.of(state.hour, state.minute), zone))
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
