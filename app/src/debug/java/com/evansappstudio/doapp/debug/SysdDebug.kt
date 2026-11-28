package com.evansappstudio.doapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import com.evansappstudio.doapp.DoApp
import com.evansappstudio.doapp.UserUnlock
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.catalogue.RegionResolver
import com.evansappstudio.doapp.catalogue.SuggestionState
import com.evansappstudio.doapp.data.SettingsStore
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Spec 11 debug hooks (Stuff You Should Do). Every line starts "DoAlarm catalogue"
 * or "DoAlarm suggestion". Settings live in credential storage, so each hook
 * refuses before the first unlock.
 *
 * adb shell am broadcast -n com.evansappstudio.doapp/.debug.DebugReceiver -a com.evansappstudio.doapp.debug.<ACTION> ...
 * - CATALOGUE_DUMP
 * - SET_REGION --es region AU-WA|AU-NSW|...|AU|none|auto
 * - SUGGESTION_DUMP
 * - SUGGESTION_RESET [--ez firstRun true]
 * - SET_PROFILE --es areas home,car|none
 * - SET_TODAY --es date 2026-12-01|none (the date SuggestionPolicy uses; the phone clock is untouched)
 */
internal object SysdDebug {
    fun catalogueDump(context: Context, pending: BroadcastReceiver.PendingResult) {
        val app = unlockedApp(context, "catalogue") ?: return pending.finish()
        app.container.scope.launch {
            try {
                val service = app.container.catalogue
                val region = service.region()
                val catalogue = service.catalogue
                val added = service.actions.addedIds()
                DoAlarmLog.catalogue(
                    "count=${catalogue.items.size} catalogueVersion=${catalogue.catalogueVersion} " +
                        "skipped=${service.parse.skipped.size} region=${RegionResolver.token(region.region)} " +
                        "source=${region.source} needsChoice=${region.needsChoice} visible=${service.items().size} " +
                        "areas=${service.areas().joinToString(",").ifEmpty { "none" }} " +
                        "onboarding=${app.container.settings.sysdOnboarding()} added=${added.sorted().joinToString(",").ifEmpty { "none" }}",
                )
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(-1, error)
            } finally {
                pending.finish()
            }
        }
    }

    fun setRegion(context: Context, raw: String?) {
        val app = unlockedApp(context, "catalogue") ?: return
        val service = app.container.catalogue
        when {
            raw?.trim().equals("auto", ignoreCase = true) -> service.setRegion(null)
            else -> {
                val region = RegionResolver.parse(raw)
                if (region == null) {
                    DoAlarmLog.catalogue("bad region=$raw")
                    return
                }
                service.setRegion(region)
            }
        }
        app.container.backup.noteChange()
        val now = service.region()
        DoAlarmLog.catalogue("region=${RegionResolver.token(now.region)} source=${now.source} needsChoice=${now.needsChoice}")
    }

    fun suggestionDump(context: Context, pending: BroadcastReceiver.PendingResult) {
        val app = unlockedApp(context, "suggestion") ?: return pending.finish()
        app.container.scope.launch {
            try {
                val service = app.container.catalogue
                val decision = service.decide()
                val override = app.container.settings.suggestionDateOverride()
                val state = service.suggestionState()
                val card = decision.card
                DoAlarmLog.suggestion(
                    "today=${service.suggestionDate()} override=${override ?: "none"} " +
                        "card=${card?.kind ?: "none"} items=${card?.items?.joinToString(",") { it.id }.orEmpty().ifEmpty { "none" }} " +
                        "reason=\"${decision.reason}\" stopped=${state.stopped} skippedOn=${state.skippedOn ?: "none"} " +
                        "dismiss=${state.dismissCount.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }}",
                )
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(-1, error)
            } finally {
                pending.finish()
            }
        }
    }

    fun suggestionReset(context: Context, firstRun: Boolean) {
        val app = unlockedApp(context, "suggestion") ?: return
        app.container.settings.setSuggestionState(SuggestionState.DEFAULT)
        if (firstRun) app.container.settings.setSysdOnboarding(SettingsStore.ONBOARDING_PENDING)
        DoAlarmLog.suggestion("reset firstRun=$firstRun")
    }

    fun setProfile(context: Context, raw: String?) {
        val app = unlockedApp(context, "catalogue") ?: return
        val text = raw?.trim()
        if (text == null) {
            DoAlarmLog.catalogue("bad areas=null")
            return
        }
        val parts = if (text.equals("none", ignoreCase = true)) emptyList() else text.split(",").map { it.trim() }
        val areas = parts.mapNotNull { ReminderCategory.normalize(it) }
        if (areas.size != parts.size) {
            DoAlarmLog.catalogue("bad areas=$raw")
            return
        }
        app.container.catalogue.setAreas(areas.toCollection(LinkedHashSet()))
        app.container.backup.noteChange()
        DoAlarmLog.catalogue("areas=${areas.joinToString(",").ifEmpty { "none" }}")
    }

    fun setToday(context: Context, raw: String?) {
        val app = unlockedApp(context, "suggestion") ?: return
        val text = raw?.trim()
        val date = when {
            text.equals("none", ignoreCase = true) -> null
            else -> try {
                LocalDate.parse(text)
            } catch (error: RuntimeException) {
                DoAlarmLog.suggestion("bad date=$raw")
                return
            }
        }
        app.container.settings.setSuggestionDateOverride(date)
        DoAlarmLog.suggestion("today=${app.container.catalogue.suggestionDate()} override=${date ?: "none"}")
    }

    private fun unlockedApp(context: Context, label: String): DoApp? {
        val app = context.applicationContext as DoApp
        if (!UserUnlock.isUnlocked(app)) {
            DoAlarmLog.catalogue("$label user locked")
            return null
        }
        return app
    }
}
