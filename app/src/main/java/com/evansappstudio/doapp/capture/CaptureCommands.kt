package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.ReminderCommands
import com.evansappstudio.doapp.data.RepeatRule

/** The result of an add from a capture surface. [NeedsUnlock] means the free cap stopped it: show the paywall. */
sealed interface CaptureResult {
    data class Saved(val id: Long) : CaptureResult

    data object NeedsUnlock : CaptureResult
}

/**
 * The thin adapter every Spec 12 surface (and the in-app Add screen) saves
 * through. It only picks the [com.evansappstudio.doapp.alarm.Origin] for the
 * surface and calls [ReminderCommands]; the cap and the write are the command
 * layer's.
 */
class CaptureCommands(private val commands: ReminderCommands) {
    suspend fun add(
        entry: CaptureEntry,
        title: String,
        dueAt: Long,
        nagMinutes: Int?,
        rule: RepeatRule? = null,
        alertLevel: AlertLevel = AlertLevel.STANDARD,
        sourceId: String? = null,
    ): CaptureResult {
        val id = commands.add(entry.origin(sourceId), title, dueAt, nagMinutes, rule, alertLevel)
        return if (id == null) CaptureResult.NeedsUnlock else CaptureResult.Saved(id)
    }

    /**
     * Share to Do. [dueAt] is null to keep the draft's date (and any repeat the text
     * asked for); a quick choice passes its own time and drops the repeat.
     * [callerPackage] is the sending app when Android tells us, else null.
     */
    suspend fun saveShare(
        draft: CaptureDraft,
        title: String,
        dueAt: Long?,
        nagMinutes: Int?,
        alertLevel: AlertLevel,
        callerPackage: String?,
    ): CaptureResult {
        val rule = if (dueAt == null) draft.rule else null
        val clean = title.trim().ifEmpty { draft.title }
        return add(CaptureEntry.SHARE, clean, dueAt ?: draft.dueAt, nagMinutes, rule, alertLevel, callerPackage)
    }

    /** The large widget's tick. */
    suspend fun widgetDone(id: Long) {
        commands.markDone(CaptureEntry.WIDGET.origin(), id)
    }
}
