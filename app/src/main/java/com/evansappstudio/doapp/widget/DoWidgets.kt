package com.evansappstudio.doapp.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.MainActivity
import com.evansappstudio.doapp.R
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.capture.CaptureEntry
import com.evansappstudio.doapp.data.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Design pass 1 look. Red is only for overdue. Dark follows the system. */
private object WidgetColours {
    val light = lightColorScheme(
        primary = Color(0xFF111827),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF111827),
        surface = Color(0xFFF7F8FA),
        onSurface = Color(0xFF111827),
        surfaceVariant = Color(0xFFE5E7EB),
        onSurfaceVariant = Color(0xFF4B5563),
        error = Color(0xFFC2391B),
        onError = Color(0xFFFFFFFF),
        secondary = Color(0xFF4B5563),
    )
    val dark = darkColorScheme(
        primary = Color(0xFFF3F4F6),
        onPrimary = Color(0xFF111827),
        background = Color(0xFF111827),
        onBackground = Color(0xFFF3F4F6),
        surface = Color(0xFF111827),
        onSurface = Color(0xFFF3F4F6),
        surfaceVariant = Color(0xFF1F2937),
        onSurfaceVariant = Color(0xFFC7CCD4),
        error = Color(0xFFFF8A70),
        onError = Color(0xFF111827),
        secondary = Color(0xFFC7CCD4),
    )
    val providers = ColorProviders(light = light, dark = dark)
}

/** Data for both widgets: the live Room list plus a time tick, so "overdue" moves at each fire. */
internal object WidgetData {
    /** Bumped by [WidgetRefresher] before each update. */
    val tick = MutableStateFlow(System.currentTimeMillis())

    /** Null before the phone is first unlocked: Room is closed then. */
    fun summaries(context: Context): Flow<WidgetSummary?> {
        if (!UserUnlock.isUnlocked(context)) return flowOf(null)
        val container = (context.applicationContext as DoApp).container
        return combine(container.reminderDao().observeAll(), tick) { rows, _ ->
            WidgetModel.summarise(rows.map(Reminder::toWidgetItem), System.currentTimeMillis())
        }
    }
}

private fun Reminder.toWidgetItem() = WidgetItem(id = id, title = title, dueAt = dueAt, isDone = isDone)

/** Opens Add in the app, saving with the widget's Origin. */
private fun addIntent(context: Context): Intent = MainActivity.addIntent(context, CaptureEntry.WIDGET)

private fun appIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun dueLabel(context: Context, dueAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val due = Instant.ofEpochMilli(dueAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    val pattern = if (due.toLocalDate() == today) clock else "EEE $clock"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(due)
        .replace(' ', ' ')
        .replace("AM", "am")
        .replace("PM", "pm")
}

class LargeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summaries = WidgetData.summaries(context)
        provideContent {
            val summary by remember { summaries }.collectAsState(initial = null)
            GlanceTheme(colors = WidgetColours.providers) {
                LargeContent(context, summary)
            }
        }
    }
}

class SmallWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val summaries = WidgetData.summaries(context)
        provideContent {
            val summary by remember { summaries }.collectAsState(initial = null)
            GlanceTheme(colors = WidgetColours.providers) {
                SmallContent(context, summary)
            }
        }
    }
}

@Composable
private fun LargeContent(context: Context, summary: WidgetSummary?) {
    val colours = GlanceTheme.colors
    val now = System.currentTimeMillis()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(colours.background)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(44.dp).clickable(actionStartActivity(appIntent(context))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.widget_heading),
                style = TextStyle(color = colours.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            val overdue = summary?.overdue ?: 0
            Text(
                text = when {
                    summary == null -> ""
                    overdue > 0 -> context.getString(R.string.widget_overdue, overdue)
                    else -> context.getString(R.string.widget_all_clear)
                },
                style = TextStyle(
                    color = if (overdue > 0) colours.error else colours.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = if (overdue > 0) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
        when {
            summary == null -> WidgetNote(context.getString(R.string.widget_locked))
            summary.next.isEmpty() -> WidgetNote(context.getString(R.string.widget_nothing_due))
            else -> summary.next.forEach { item -> WidgetRow(context, item, now) }
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(44.dp)
                .background(colours.surfaceVariant)
                .cornerRadius(22.dp)
                .padding(horizontal = 16.dp)
                .clickable(actionStartActivity(addIntent(context))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.add_field_label),
                style = TextStyle(color = colours.onSurfaceVariant, fontSize = 16.sp),
            )
        }
    }
}

@Composable
private fun WidgetNote(text: String) {
    Text(
        text = text,
        modifier = GlanceModifier.padding(vertical = 8.dp),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
    )
}

@Composable
private fun WidgetRow(context: Context, item: WidgetItem, now: Long) {
    val colours = GlanceTheme.colors
    val overdue = WidgetModel.isOverdue(item, now)
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<WidgetDoneAction>(actionParametersOf(WidgetDoneAction.ID to item.id)),
            text = item.title,
            maxLines = 1,
            style = TextStyle(color = colours.onBackground, fontSize = 15.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = dueLabel(context, item.dueAt, now),
            style = TextStyle(
                color = if (overdue) colours.error else colours.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun SmallContent(context: Context, summary: WidgetSummary?) {
    val colours = GlanceTheme.colors
    val overdue = summary?.overdue ?: 0
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(colours.background)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(addIntent(context))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = context.getString(R.string.widget_heading),
            style = TextStyle(color = colours.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
        if (overdue > 0) {
            Text(
                text = overdue.toString(),
                style = TextStyle(color = colours.error, fontSize = 36.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                text = context.getString(R.string.widget_overdue_word),
                style = TextStyle(color = colours.error, fontSize = 14.sp),
            )
        } else {
            Text(
                text = context.getString(if (summary == null) R.string.widget_locked_short else R.string.widget_all_clear),
                style = TextStyle(color = colours.onSurfaceVariant, fontSize = 16.sp),
            )
        }
        Text(
            text = context.getString(R.string.widget_tap_to_add),
            modifier = GlanceModifier.padding(top = 4.dp),
            style = TextStyle(color = colours.onSurfaceVariant, fontSize = 12.sp),
        )
    }
}

/** Tick-to-done: Done through the command layer with the widget's Origin. */
class WidgetDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ID] ?: return
        if (!UserUnlock.isUnlocked(context)) return
        try {
            (context.applicationContext as DoApp).container.capture.widgetDone(id)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id, error)
        }
    }

    companion object {
        val ID = ActionParameters.Key<Long>("reminderId")
    }
}

class LargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LargeWidget()
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallWidget()
}

/**
 * Refreshes both widgets after each ReminderCommands command, a fire, boot and
 * a clock change. Requests are folded together, so a restore of 50 reminders is
 * one refresh. Nothing polls.
 */
class WidgetRefresher(private val context: Context, private val scope: CoroutineScope) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            for (request in requests) {
                try {
                    refreshNow()
                } catch (error: Throwable) {
                    DoAlarmLog.handlingFailed(id = -1, error)
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }

    private suspend fun refreshNow() {
        WidgetData.tick.value = System.currentTimeMillis()
        // Before first unlock WorkManager (and so Glance) cannot run; BOOT_COMPLETED refreshes later.
        if (!UserUnlock.isUnlocked(context)) return
        val manager = GlanceAppWidgetManager(context)
        for (widget in listOf(LargeWidget(), SmallWidget())) {
            for (id in manager.getGlanceIds(widget.javaClass)) {
                widget.update(context, id)
            }
        }
    }
}
