package com.evansappstudio.doapp.alarm

import android.util.Log
import com.evansappstudio.doapp.backup.BackupNames
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.ReminderEvent

object DoAlarmLog {
    const val TAG = "DoAlarm"

    fun fire(id: Long, scheduled: Long, actual: Long) {
        Log.i(TAG, "DoAlarm fire id=$id scheduled=$scheduled actual=$actual")
    }

    fun debugAdd(id: Long, dueAt: Long, nagMinutes: Int?) {
        val logged = nagMinutes ?: 0
        Log.i(TAG, "DoAlarm debug-add id=$id dueAt=$dueAt nagMinutes=$logged")
    }

    fun snooze(id: Long, newDue: Long, option: Int) {
        Log.i(TAG, "DoAlarm snooze id=$id newDue=$newDue option=$option")
    }

    fun configSnooze(option: Int, kind: String, value: Int) {
        Log.i(TAG, "DoAlarm config-snooze option=$option kind=$kind value=$value")
    }

    fun qatConfig(message: String) {
        Log.i(TAG, message)
    }

    fun qat(id: Long, newDue: Long, slot: Int) {
        Log.i(TAG, "DoAlarm qat id=$id newDue=$newDue slot=$slot")
    }

    fun snoozeSkipped(id: Long, reason: String) {
        Log.i(TAG, "DoAlarm snooze skipped id=$id reason=$reason")
    }

    fun exactUnavailable(id: Long, error: Exception?) {
        if (error == null) {
            Log.w(TAG, "DoAlarm exact alarm unavailable id=$id; using setAndAllowWhileIdle")
        } else {
            Log.e(TAG, "DoAlarm exact alarm unavailable id=$id; using setAndAllowWhileIdle", error)
        }
    }

    fun inexactFailed(id: Long, error: Exception) {
        Log.e(TAG, "DoAlarm inexact alarm failed id=$id", error)
    }

    fun repeat(id: Long, rule: String, from: String, dueAt: Long, patternDue: Long?, next: String?) {
        val pattern = patternDue?.toString() ?: "null"
        val tail = if (next == null) "" else " next=$next"
        Log.i(TAG, "DoAlarm repeat id=$id rule=$rule from=$from dueAt=$dueAt patternDue=$pattern$tail")
    }

    fun repeatNext(id: Long, index: Int, at: Long) {
        Log.i(TAG, "DoAlarm repeat-next id=$id i=$index at=$at")
    }

    fun repeatAdvance(id: Long, newDue: Long, patternDue: Long?, from: String) {
        val pattern = patternDue?.toString() ?: "null"
        Log.i(TAG, "DoAlarm repeat-advance id=$id newDue=$newDue patternDue=$pattern from=$from")
    }

    fun shiftDue(id: Long, newDue: Long, mode: String, patternDue: Long?) {
        val pattern = patternDue?.toString() ?: "null"
        Log.i(TAG, "DoAlarm shift-due id=$id newDue=$newDue mode=$mode patternDue=$pattern")
    }

    fun configRepeatPrompt(value: String) {
        Log.i(TAG, "DoAlarm config-repeat-prompt value=$value")
    }

    fun parseMatch(matched: String, title: String, dueAt: Long, rule: String, start: Int, end: Int) {
        Log.i(TAG, "DoAlarm parse match=\"$matched\" title=\"$title\" dueAt=$dueAt rule=$rule span=$start,$end")
    }

    fun parseNone(text: String) {
        Log.i(TAG, "DoAlarm parse none text=\"$text\"")
    }

    fun alert(id: Long, level: String) {
        Log.i(TAG, "DoAlarm alert id=$id level=$level")
    }

    fun alertDump(id: Long?, level: String?, defaultLevel: String, fullScreen: Boolean, dnd: Boolean, effective: String?, parse: String) {
        if (id == null || level == null || effective == null) {
            Log.i(TAG, "DoAlarm alert default=$defaultLevel fsi=$fullScreen dnd=$dnd parse=$parse")
        } else {
            Log.i(
                TAG,
                "DoAlarm alert id=$id level=$level default=$defaultLevel fsi=$fullScreen dnd=$dnd effective=$effective",
            )
        }
    }

    fun alertFallback(id: Long, requested: String, effective: String, reason: String) {
        Log.i(TAG, "DoAlarm alert-fallback id=$id requested=$requested effective=$effective reason=$reason")
    }

    fun alertStop(id: Long, next: Long?) {
        Log.i(TAG, "DoAlarm alert-stop id=$id next=${next?.toString() ?: "none"}")
    }

    fun configParse(value: String) {
        Log.i(TAG, "DoAlarm config-parse value=$value")
    }

    fun configAlertDefault(level: String) {
        Log.i(TAG, "DoAlarm config-alert-default level=$level")
    }

    fun handlingFailed(id: Long, error: Throwable) {
        Log.e(TAG, "DoAlarm handling failed id=$id", error)
    }

    fun healthDump(body: String) {
        Log.i(TAG, "DoAlarm health $body")
    }

    fun healthLate(id: Long, scheduled: Long, actual: Long, lagMs: Long) {
        Log.i(TAG, "DoAlarm health-late id=$id scheduled=$scheduled actual=$actual lagMs=$lagMs")
    }

    fun healthLateSynthetic(lagMs: Long) {
        Log.i(TAG, "DoAlarm health-late synthetic lagMs=$lagMs")
    }

    fun healthTestScheduled(scheduled: Long, id: Long) {
        Log.i(TAG, "DoAlarm health-test scheduled=$scheduled id=$id")
    }

    fun healthTestFire(id: Long, scheduled: Long, actual: Long) {
        Log.i(TAG, "DoAlarm health-test id=$id scheduled=$scheduled actual=$actual")
    }

    fun healthTestDone(id: Long) {
        Log.i(TAG, "DoAlarm health-test done id=$id")
    }

    fun unlockBlocked(active: Int, cap: String) {
        Log.i(TAG, "DoAlarm unlock blocked active=$active cap=$cap")
    }

    fun unlockFake(state: String, active: Int, cap: String, intro: Boolean) {
        Log.i(TAG, "DoAlarm unlock state=$state source=fake active=$active cap=$cap intro=$intro")
    }

    /**
     * The Spec 7 fields come first, unchanged in name, so older runner regexes still match:
     * the first state= is unlocked or locked, product= is the subscription id and price= the renewal price.
     * The Spec 10 fields follow, with the second state= being FREE, ACTIVE, PENDING or EXPIRED.
     */
    fun unlockDump(
        state: String,
        source: String,
        pending: Boolean,
        price: String,
        active: Int,
        cap: String,
        subscription: String,
        everSubscribed: Boolean,
        intro: String,
        renewal: String,
        product: String,
    ) {
        Log.i(
            TAG,
            "DoAlarm unlock state=$state source=$source pending=$pending product=$product price=$price active=$active cap=$cap " +
                "state=$subscription everSubscribed=$everSubscribed intro=$intro renewal=$renewal product=$product",
        )
    }

    fun quietDefer(id: Long, planned: Long, at: Long) {
        Log.i(TAG, "DoAlarm quiet-defer id=$id planned=$planned at=$at")
    }

    fun dndHold(id: Long, scheduled: Long, next: Long) {
        Log.i(TAG, "DoAlarm dnd-hold id=$id scheduled=$scheduled next=$next")
    }

    fun dndRelease(id: Long, at: Long) {
        Log.i(TAG, "DoAlarm dnd-release id=$id at=$at")
    }

    fun dndFilter(filter: Int, active: Boolean) {
        Log.i(TAG, "DoAlarm dnd filter=$filter active=$active")
    }

    fun quietSet(
        enabled: Boolean,
        start: String,
        end: String,
        followDnd: Boolean,
        criticalPauses: Boolean,
        simDnd: String,
    ) {
        Log.i(
            TAG,
            "DoAlarm quiet set enabled=$enabled start=$start end=$end followDnd=$followDnd criticalPauses=$criticalPauses simDnd=$simDnd",
        )
    }

    fun quietBadArgs() {
        Log.i(TAG, "DoAlarm quiet bad args")
    }

    fun quietDump(body: String) {
        Log.i(TAG, "DoAlarm quiet $body")
    }

    fun quietId(body: String) {
        Log.i(TAG, "DoAlarm quiet $body")
    }

    fun unlockAckFailed(responseCode: Int) {
        Log.i(TAG, "DoAlarm unlock ack failed code=$responseCode")
    }

    fun backupExport(path: String, reminders: Int) {
        Log.i(TAG, "DoAlarm backup export path=$path reminders=$reminders schema=${BackupNames.SCHEMA_VERSION}")
    }

    fun backupImport(mode: String, reminders: Int, ids: List<Long>) {
        val suffix = if (ids.isEmpty()) "" else " ids=${ids.joinToString(",")}"
        Log.i(TAG, "DoAlarm backup import mode=$mode reminders=$reminders schema=${BackupNames.SCHEMA_VERSION}$suffix")
    }

    fun backupImportError(reason: String) {
        Log.i(TAG, "DoAlarm backup import error reason=$reason")
    }

    fun backupFolder(set: Boolean) {
        Log.i(TAG, "DoAlarm backup folder=${if (set) "set" else "none"}")
    }

    fun backupDump(folderSet: Boolean, lastSuccessAt: Long, health: String) {
        val success = if (lastSuccessAt > 0L) lastSuccessAt.toString() else "none"
        val folder = if (folderSet) "set" else "none"
        Log.i(TAG, "DoAlarm backup folder=$folder lastSuccess=$success health=$health")
    }

    fun backupAuto(path: String, reminders: Int) {
        Log.i(TAG, "DoAlarm backup auto path=$path reminders=$reminders schema=${BackupNames.SCHEMA_VERSION}")
    }

    fun backupAutoSkipped(reason: String) {
        Log.i(TAG, "DoAlarm backup auto skipped reason=$reason")
    }

    fun backupAutoError(reason: String) {
        Log.i(TAG, "DoAlarm backup auto error reason=$reason")
    }

    fun meta(row: Reminder) {
        Log.i(
            TAG,
            "DoAlarm meta id=${row.id} uuid=${row.uuid} source=${row.source} sourceId=${row.sourceId ?: "none"} " +
                "category=${row.category ?: "none"} templateId=${row.templateId ?: "none"} " +
                "templateVersion=${row.templateVersion?.toString() ?: "none"} version=${row.version} " +
                "updatedAt=${row.updatedAt} deletedAt=${row.deletedAt?.toString() ?: "none"} " +
                "approval=${row.approval} requestedAlertLevel=${row.requestedAlertLevel ?: "none"}",
        )
    }

    fun event(id: Long, event: ReminderEvent) {
        Log.i(
            TAG,
            "DoAlarm event id=$id at=${event.at} type=${event.type} actor=${event.actor} " +
                "sourceId=${event.sourceId ?: "none"} detail=${event.detail ?: "none"}",
        )
    }

    fun eventsEnd(count: Int) {
        Log.i(TAG, "DoAlarm events end count=$count")
    }

    fun deleted(id: Long, deletedAt: Long) {
        Log.i(TAG, "DoAlarm deleted id=$id deletedAt=$deletedAt")
    }

    fun restored(id: Long) {
        Log.i(TAG, "DoAlarm restored id=$id")
    }

    fun purge(removed: Int) {
        Log.i(TAG, "DoAlarm purge removed=$removed")
    }
}
