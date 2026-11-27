package com.evansappstudio.doapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evansappstudio.doapp.AppContainer
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.QuietConfig
import com.evansappstudio.doapp.alarm.QuietHours
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.data.DueShiftMode
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.RepeatRule
import com.evansappstudio.doapp.data.WeekStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReminderViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val defaultNagMinutes: Int = container.settings.defaultNagMinutes()

    val reminders: StateFlow<List<Reminder>> = container.reminderDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val snoozeOptionState = MutableStateFlow(container.settings.snoozeOptions())
    val snoozeOptions: StateFlow<List<SnoozeOption>> = snoozeOptionState.asStateFlow()

    private val quickTimesState = MutableStateFlow(container.settings.quickTimes())
    val quickTimes: StateFlow<QuickTimesSettings> = quickTimesState.asStateFlow()

    private val quietState = MutableStateFlow(container.quiet.read())
    val quietHours: StateFlow<QuietConfig> = quietState.asStateFlow()

    fun reloadQuiet() {
        quietState.value = container.quiet.read()
    }

    fun quietConfig(): QuietConfig = container.quiet.read()

    fun setQuietEnabled(enabled: Boolean) = writeQuiet { it.copy(enabled = enabled) }

    fun setQuietFollowDnd(enabled: Boolean) = writeQuiet { it.copy(followDnd = enabled) }

    fun setQuietCriticalPauses(enabled: Boolean) = writeQuiet { it.copy(criticalPauses = enabled) }

    /** False when the minute matches the other end. The stored window is left as it was. */
    fun setQuietStart(minute: Int): Boolean {
        val current = container.quiet.read()
        if (!QuietHours.minuteInRange(minute) || minute == current.endMinute) return false
        writeQuiet { it.copy(startMinute = minute) }
        return true
    }

    /** False when the minute matches the other end. The stored window is left as it was. */
    fun setQuietEnd(minute: Int): Boolean {
        val current = container.quiet.read()
        if (!QuietHours.minuteInRange(minute) || minute == current.startMinute) return false
        writeQuiet { it.copy(endMinute = minute) }
        return true
    }

    private fun writeQuiet(block: (QuietConfig) -> QuietConfig) {
        viewModelScope.launch {
            val next = block(container.quiet.read())
            if (container.quiet.write(next)) {
                quietState.value = container.quiet.read()
                container.scheduler.reconcileQuietHours()
            }
        }
    }

    fun reloadQuickTimes() {
        quickTimesState.value = container.settings.quickTimes()
    }

    fun setQuickTimeSlot(index: Int, slot: QuickTimeSlot) {
        viewModelScope.launch {
            container.settings.setQuickTimeSlot(index, slot)
            reloadQuickTimes()
            container.backup.noteChange()
        }
    }

    fun setPostponeMode(mode: PostponeMode) {
        viewModelScope.launch {
            container.settings.setPostponeMode(mode)
            reloadQuickTimes()
            container.backup.noteChange()
        }
    }

    fun add(
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule?,
        alert: AlertLevel,
        onResult: (created: Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val created = container.scheduler.add(title, dueAt, nagMinutes, rule, alert) != null
            onResult(created)
        }
    }

    fun update(
        id: Long,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule?,
        dueShift: DueShiftMode?,
        alert: AlertLevel,
    ) {
        viewModelScope.launch {
            container.scheduler.update(id, title, dueAt, nagMinutes, rule, dueShift, alert)
        }
    }

    fun setAlertLevel(id: Long, level: AlertLevel) {
        viewModelScope.launch { container.scheduler.setAlertLevel(id, level) }
    }

    fun parseDatesEnabled(): Boolean = container.settings.parseDatesEnabled()

    fun setParseDatesEnabled(enabled: Boolean) {
        container.settings.setParseDatesEnabled(enabled)
        container.backup.noteChange()
    }

    fun defaultAlertLevel(): AlertLevel = container.settings.defaultAlertLevel()

    fun setDefaultAlertLevel(level: AlertLevel) {
        container.settings.setDefaultAlertLevel(level)
        container.backup.noteChange()
    }

    fun hasExplainedAlert(level: AlertLevel): Boolean = container.settings.hasExplainedAlert(level)

    fun markAlertExplained(level: AlertLevel) {
        container.settings.markAlertExplained(level)
    }

    fun repeatPromptMode(): RepeatPromptMode = container.settings.repeatPromptMode()

    fun setRepeatPromptMode(mode: RepeatPromptMode) {
        container.settings.setRepeatPromptMode(mode)
        container.backup.noteChange()
    }

    fun weekStart(): WeekStart = container.settings.weekStart()

    fun setWeekStart(start: WeekStart) {
        container.settings.setWeekStart(start)
        container.backup.noteChange()
    }

    suspend fun reminderOrNull(id: Long): Reminder? = container.reminderDao().getById(id)

    fun setSnoozeOption(index: Int, option: SnoozeOption) {
        viewModelScope.launch {
            container.saveSnoozeOption(index, option)
            snoozeOptionState.value = container.settings.snoozeOptions()
            container.scheduler.rebuildShowingNotifications()
            container.backup.noteChange()
        }
    }

    fun markDone(id: Long) {
        viewModelScope.launch { container.scheduler.markDone(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { container.scheduler.delete(id) }
    }

    fun hasRequestedNotificationPermission(): Boolean =
        container.settings.hasRequestedNotificationPermission()

    fun markNotificationPermissionRequested() {
        container.settings.markNotificationPermissionRequested()
    }
}

class ReminderViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReminderViewModel::class.java)) {
            return ReminderViewModel(container) as T
        }
        throw IllegalArgumentException("Unknown model $modelClass")
    }
}
