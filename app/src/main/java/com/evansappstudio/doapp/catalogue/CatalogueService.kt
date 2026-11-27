package com.evansappstudio.doapp.catalogue

import android.content.Context
import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.data.SettingsStore
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Android side of Stuff You Should Do: loads the bundled asset once, and
 * joins the pure rules to the settings and the reminder list.
 */
class CatalogueService(
    context: Context,
    private val settings: SettingsStore,
    val actions: CatalogueActions,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext

    val parse: CatalogueParse by lazy {
        val text = try {
            appContext.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            ""
        }
        val result = CatalogueParser.parse(text)
        result.skipped.forEach { DoAlarmLog.catalogueSkipped(it) }
        result
    }

    val catalogue: Catalogue get() = parse.catalogue

    fun region(): RegionSetting = settings.sysdRegion(zone().id)

    fun setRegion(region: Region?) = settings.setSysdRegion(region)

    /** Catalogue items for the region in force, localised. */
    fun items(): List<LocalItem> = CatalogueFilter.forRegion(catalogue, region().region)

    fun areas(): Set<String> = settings.sysdAreas()

    fun setAreas(areas: Set<String>) = settings.setSysdAreas(areas)

    /** The date SuggestionPolicy uses: the debug override when set, else today. */
    fun suggestionDate(): LocalDate =
        settings.suggestionDateOverride() ?: java.time.Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()

    fun suggestionState(): SuggestionState = settings.suggestionState()

    fun updateSuggestions(block: (SuggestionState) -> SuggestionState) {
        settings.setSuggestionState(block(settings.suggestionState()))
    }

    suspend fun decide(): SuggestionDecision = SuggestionPolicy.decide(
        SuggestionInput(
            today = suggestionDate(),
            areas = areas(),
            added = actions.addedIds(),
            doneItemIds = actions.doneItemIds(),
            items = items(),
            state = settings.suggestionState(),
        ),
    )

    /** Plans first dues for [items] in order (section D). */
    fun plan(items: List<LocalItem>, pickedDates: Map<String, LocalDate> = emptyMap()): List<PlannedAdd> =
        FirstDue.plan(items, clock(), zone(), pickedDates)

    suspend fun add(plans: List<PlannedAdd>, actor: Actor = Actor.USER): CatalogueAddResult {
        val result = actions.add(plans, settings.defaultNagMinutes(), actor)
        DoAlarmLog.catalogueAdd(plans.map { it.item.id }, result.ids, result.blocked)
        return result
    }

    suspend fun remove(itemId: String, actor: Actor = Actor.USER): Int = actions.remove(itemId, actor)

    companion object {
        const val ASSET = "catalogue/sysd.json"
    }
}
