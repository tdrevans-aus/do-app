package com.evansappstudio.doapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.evansappstudio.doapp.AppContainer
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.billing.SubscriptionState
import com.evansappstudio.doapp.catalogue.CardKind
import com.evansappstudio.doapp.catalogue.CatalogueActions
import com.evansappstudio.doapp.catalogue.LocalItem
import com.evansappstudio.doapp.catalogue.Picks
import com.evansappstudio.doapp.catalogue.PlannedAdd
import com.evansappstudio.doapp.catalogue.Region
import com.evansappstudio.doapp.catalogue.RegionSetting
import com.evansappstudio.doapp.catalogue.SuggestionCard
import com.evansappstudio.doapp.catalogue.SuggestionPolicy
import com.evansappstudio.doapp.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What step 4 starts with. */
data class PicksStart(
    val groups: List<Pair<String, List<LocalItem>>>,
    val preTicked: Set<String>,
    val activeCount: Int,
    val unlocked: Boolean,
)

/** Stuff You Should Do on the screens: first run steps 3 and 4, the Catalogue tab and the Today card. */
class SysdViewModel(private val container: AppContainer) : ViewModel() {
    private val service get() = container.catalogue

    private val regionState = MutableStateFlow(service.region())
    val region: StateFlow<RegionSetting> = regionState.asStateFlow()

    private val itemsState = MutableStateFlow(service.items())
    val items: StateFlow<List<LocalItem>> = itemsState.asStateFlow()

    private val areasState = MutableStateFlow(service.areas())
    val areas: StateFlow<Set<String>> = areasState.asStateFlow()

    val added: StateFlow<Set<String>> = container.reminderDao().observeAll()
        .map { CatalogueActions.addedIds(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val cardState = MutableStateFlow<SuggestionCard?>(null)
    val card: StateFlow<SuggestionCard?> = cardState.asStateFlow()

    /** Reads settings again (after a resume, or a debug hook) and works out the Today card. */
    fun reload() {
        regionState.value = service.region()
        itemsState.value = service.items()
        areasState.value = service.areas()
        refreshCard()
    }

    fun refreshCard() {
        viewModelScope.launch {
            try {
                val decision = service.decide()
                val shown = decision.card
                if (shown != null) {
                    val today = service.suggestionDate()
                    service.updateSuggestions { SuggestionPolicy.shown(it, shown, today) }
                }
                cardState.value = shown
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
                cardState.value = null
            }
        }
    }

    fun setRegion(region: Region?) {
        service.setRegion(region)
        container.backup.noteChange()
        reload()
    }

    fun setAreas(areas: Set<String>) {
        val ordered = ReminderCategory.ALL.filter { it in areas }.toCollection(LinkedHashSet())
        service.setAreas(ordered)
        areasState.value = ordered
        container.backup.noteChange()
    }

    /** First run steps 3 and 4 are due once, the first time there is a reminder. */
    fun onboardingPending(): Boolean = container.settings.sysdOnboarding() == SettingsStore.ONBOARDING_PENDING

    fun startOnboarding() = container.settings.setSysdOnboarding(SettingsStore.ONBOARDING_STARTED)

    fun finishOnboarding() {
        container.settings.setSysdOnboarding(SettingsStore.ONBOARDING_DONE)
        clearPicks()
    }

    /** Skip on step 3 or 4: go to Today and arm the head-start card for the next day. */
    fun skipOnboarding() {
        clearPicks()
        container.settings.setSysdOnboarding(SettingsStore.ONBOARDING_SKIPPED)
        val today = service.suggestionDate()
        service.updateSuggestions { SuggestionPolicy.skipped(it, today) }
        refreshCard()
    }

    /** Spec 10: only an active Do Unlimited subscription lifts the cap (as Scheduler.add). */
    fun unlocked(): Boolean = SubscriptionState.of(container.entitlement.read()).liftsCap

    suspend fun picksStart(): PicksStart {
        val added = service.actions.addedIds()
        val groups = Picks.grouped(itemsState.value, areasState.value, added, ReminderCategory.ALL)
        val active = container.reminderDao().activeCount()
        val unlocked = unlocked()
        val pre = Picks.preTicked(groups.flatMap { it.second }, active, unlocked, FreeTier.ACTIVE_REMINDER_CAP, areasState.value)
        return PicksStart(groups, pre, active, unlocked)
    }

    private val picksState = MutableStateFlow<PicksStart?>(null)
    val picks: StateFlow<PicksStart?> = picksState.asStateFlow()

    /** Step 4's ticks, picked dates and Show more, kept here so rotation and the paywall keep them. */
    val pickTicks = mutableStateMapOf<String, Boolean>()
    val pickDates = mutableStateMapOf<String, LocalDate>()
    val pickExpanded = mutableStateMapOf<String, Boolean>()

    /** Picks waiting on the paywall. Back returns to step 4; Keep 5 for free adds what fits. */
    var pendingPlans by mutableStateOf<List<PlannedAdd>?>(null)

    fun loadPicks() {
        if (picksState.value != null) return
        viewModelScope.launch {
            val start = picksStart()
            pickTicks.clear()
            start.preTicked.forEach { pickTicks[it] = true }
            picksState.value = start
        }
    }

    fun clearPicks() {
        picksState.value = null
        pickTicks.clear()
        pickDates.clear()
        pickExpanded.clear()
        pendingPlans = null
    }

    fun suggestionsOn(): Boolean = !service.suggestionState().stopped

    /** The Settings switch: off is "Stop suggesting", on brings the cards back. */
    fun setSuggestionsOn(on: Boolean) {
        service.updateSuggestions { it.copy(stopped = !on) }
        refreshCard()
    }

    fun plan(items: List<LocalItem>, dates: Map<String, LocalDate>): List<PlannedAdd> = service.plan(items, dates)

    /** Adds in order; [onResult] gets true when the free cap stopped it. */
    fun addPlans(plans: List<PlannedAdd>, onResult: (blocked: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = service.add(plans)
            refreshCard()
            onResult(result.blocked)
        }
    }

    /** One item (Catalogue tab, Today card, the Related line): section D with i = 0. */
    fun addOne(item: LocalItem, date: LocalDate?, from: CardKind? = null, onResult: (blocked: Boolean) -> Unit = {}) {
        if (from != null) service.updateSuggestions { SuggestionPolicy.followed(it, from) }
        val dates = if (date == null) emptyMap() else mapOf(item.id to date)
        addPlans(service.plan(listOf(item), dates), onResult)
    }

    /** Soft delete, so it can be restored. */
    fun remove(itemId: String) {
        viewModelScope.launch {
            service.remove(itemId)
            refreshCard()
        }
    }

    fun notNow(kind: CardKind) {
        val today = service.suggestionDate()
        service.updateSuggestions { SuggestionPolicy.dismiss(it, kind, today) }
        cardState.value = null
    }

    fun stopSuggesting() {
        service.updateSuggestions { SuggestionPolicy.stop(it) }
        cardState.value = null
    }

    fun followed(kind: CardKind) {
        service.updateSuggestions { SuggestionPolicy.followed(it, kind) }
        refreshCard()
    }
}

class SysdViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SysdViewModel::class.java)) return SysdViewModel(container) as T
        throw IllegalArgumentException("Unknown model $modelClass")
    }
}
