package com.evansappstudio.doapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evansappstudio.doapp.AppContainer
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.data.Reminder
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

    fun reloadQuickTimes() {
        quickTimesState.value = container.settings.quickTimes()
    }

    fun setQuickTimeSlot(index: Int, slot: QuickTimeSlot) {
        viewModelScope.launch {
            container.settings.setQuickTimeSlot(index, slot)
            reloadQuickTimes()
        }
    }

    fun setPostponeMode(mode: PostponeMode) {
        viewModelScope.launch {
            container.settings.setPostponeMode(mode)
            reloadQuickTimes()
        }
    }

    fun add(title: String, dueAt: Long, nagMinutes: Int?) {
        viewModelScope.launch { container.scheduler.add(title, dueAt, nagMinutes) }
    }

    fun update(id: Long, title: String, dueAt: Long, nagMinutes: Int?) {
        viewModelScope.launch { container.scheduler.update(id, title, dueAt, nagMinutes) }
    }

    suspend fun reminderOrNull(id: Long): Reminder? = container.reminderDao().getById(id)

    fun setSnoozeOption(index: Int, option: SnoozeOption) {
        viewModelScope.launch {
            container.saveSnoozeOption(index, option)
            snoozeOptionState.value = container.settings.snoozeOptions()
            container.scheduler.rebuildShowingNotifications()
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
