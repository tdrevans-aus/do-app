package com.evansappstudio.doapp.backup

import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.Approval
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderCategory
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.alarm.QuietHours
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimeUnit
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.catalogue.RegionResolver
import com.evansappstudio.doapp.catalogue.RegionSource
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.WeekStart

sealed class BackupDecode {
    data class Ok(val document: BackupDocument) : BackupDecode()
    data class Rejected(val reason: String) : BackupDecode()

    companion object {
        const val SCHEMA = "schema"
        const val APP = "app"
        const val CORRUPT = "corrupt"
    }
}

object BackupCodec {
    fun encode(document: BackupDocument): String {
        val json = jsonObject(
            "schemaVersion" to JsonValue.Num(document.schemaVersion.toLong()),
            "appId" to JsonValue.Str(document.appId),
            "appVersionName" to JsonValue.Str(document.appVersionName),
            "exportedAt" to JsonValue.Num(document.exportedAt),
            "settings" to settingsJson(document.settings),
            "reminders" to JsonValue.Arr(document.reminders.map { reminderJson(it) }),
        )
        return encodeJson(json) + "\n"
    }

    fun decode(text: String): BackupDecode {
        if (text.isBlank()) return BackupDecode.Rejected(BackupDecode.CORRUPT)
        val root = try {
            parseJson(text).obj()
        } catch (error: Throwable) {
            return BackupDecode.Rejected(BackupDecode.CORRUPT)
        }
        val schema = root.fields["schemaVersion"] as? JsonValue.Num
            ?: return BackupDecode.Rejected(BackupDecode.SCHEMA)
        if (schema.value > BackupNames.SCHEMA_VERSION || schema.value < 1) {
            return BackupDecode.Rejected(BackupDecode.SCHEMA)
        }
        val appId = (root.fields["appId"] as? JsonValue.Str)?.value
        if (appId.isNullOrBlank() || appId != BackupNames.APP_ID) {
            return BackupDecode.Rejected(BackupDecode.APP)
        }
        val versionName = (root.fields["appVersionName"] as? JsonValue.Str)?.value ?: ""
        val exportedAt = (root.fields["exportedAt"] as? JsonValue.Num)?.value
            ?: return BackupDecode.Rejected(BackupDecode.CORRUPT)
        val settings = decodeSettings(root.fields["settings"]) ?: return BackupDecode.Rejected(BackupDecode.CORRUPT)
        val remindersValue = root.fields["reminders"] as? JsonValue.Arr
            ?: return BackupDecode.Rejected(BackupDecode.CORRUPT)
        val reminders = ArrayList<PortableReminder>(remindersValue.items.size)
        for (item in remindersValue.items) {
            val reminder = try {
                decodeReminder(item)
            } catch (error: JsonParseException) {
                return BackupDecode.Rejected(BackupDecode.CORRUPT)
            }
            reminders += reminder ?: return BackupDecode.Rejected(BackupDecode.CORRUPT)
        }
        return BackupDecode.Ok(
            BackupDocument(
                schemaVersion = schema.value.toInt(),
                appId = appId,
                appVersionName = versionName,
                exportedAt = exportedAt,
                settings = settings,
                reminders = reminders,
            ),
        )
    }

    /**
     * A rejected file returns [current] unchanged. Replace swaps the list.
     * Merge matches on uuid and keeps local-only rows.
     */
    fun restoreReminders(
        current: List<PortableReminder>,
        text: String,
        mode: BackupMode,
        onlyUuids: Set<String>?,
    ): List<PortableReminder> {
        val decoded = decode(text)
        val document = (decoded as? BackupDecode.Ok)?.document ?: return current
        return when (mode) {
            BackupMode.REPLACE -> document.reminders
            BackupMode.MERGE -> mergeReminders(current, document.reminders, onlyUuids)
        }
    }
}

fun mergeReminders(
    local: List<PortableReminder>,
    incoming: List<PortableReminder>,
    onlyUuids: Set<String>?,
): List<PortableReminder> {
    val selected = if (onlyUuids == null) incoming else incoming.filter { it.uuid in onlyUuids }
    val byUuid = LinkedHashMap<String, PortableReminder>()
    for (row in local) byUuid[row.uuid] = row
    for (row in selected) byUuid[row.uuid] = row
    return byUuid.values.toList()
}

private fun settingsJson(settings: PortableSettings): JsonValue {
    val fields = linkedMapOf(
        "defaultNagMinutes" to JsonValue.Num(settings.defaultNagMinutes.toLong()),
        "snooze" to JsonValue.Arr(settings.snooze.map { snoozeJson(it) }),
        "quickTimes" to jsonObject(
            "postpone" to JsonValue.Str(settings.quickTimes.postpone.name),
            "slots" to JsonValue.Arr(settings.quickTimes.slots.map { slotJson(it) }),
        ),
        "weekStart" to JsonValue.Str(settings.weekStart.name),
        "parseDates" to JsonValue.Bool(settings.parseDates),
        "defaultAlertLevel" to JsonValue.Str(settings.defaultAlertLevel.name),
        "repeatPromptMode" to JsonValue.Str(settings.repeatPromptMode.name),
    )
    settings.quietEnabled?.let { fields["quietEnabled"] = JsonValue.Bool(it) }
    settings.quietStartMinute?.let { fields["quietStartMinute"] = JsonValue.Num(it.toLong()) }
    settings.quietEndMinute?.let { fields["quietEndMinute"] = JsonValue.Num(it.toLong()) }
    settings.quietFollowDnd?.let { fields["quietFollowDnd"] = JsonValue.Bool(it) }
    settings.quietCriticalPauses?.let { fields["quietCriticalPauses"] = JsonValue.Bool(it) }
    settings.sysdRegion?.let { fields["sysdRegion"] = JsonValue.Str(it) }
    settings.sysdRegionSource?.let { fields["sysdRegionSource"] = JsonValue.Str(it) }
    settings.sysdAreas?.let { areas -> fields["sysdAreas"] = JsonValue.Arr(areas.map { JsonValue.Str(it) }) }
    return JsonValue.Obj(fields)
}

private fun snoozeJson(option: SnoozeOption): JsonValue = jsonObject(
    "kind" to JsonValue.Str(option.kind.name),
    "value" to JsonValue.Num(option.value.toLong()),
)

private fun slotJson(slot: QuickTimeSlot): JsonValue = jsonObject(
    "kind" to JsonValue.Str(slot.kind.name),
    "amount" to JsonValue.Num(slot.amount.toLong()),
    "unit" to JsonValue.Str(slot.unit.name),
)

private fun reminderJson(reminder: PortableReminder): JsonValue = JsonValue.Obj(reminderFields(reminder))

private fun reminderFields(reminder: PortableReminder): LinkedHashMap<String, JsonValue> = linkedMapOf(
    "uuid" to JsonValue.Str(reminder.uuid),
    "title" to JsonValue.Str(reminder.title),
    "dueAt" to JsonValue.Num(reminder.dueAt),
    "nagMinutes" to (reminder.nagMinutes?.let { JsonValue.Num(it.toLong()) } ?: JsonValue.Null),
    "isDone" to JsonValue.Bool(reminder.isDone),
    "doneAt" to (reminder.doneAt?.let { JsonValue.Num(it) } ?: JsonValue.Null),
    "createdAt" to JsonValue.Num(reminder.createdAt),
    "repeatInterval" to (reminder.repeatInterval?.let { JsonValue.Num(it.toLong()) } ?: JsonValue.Null),
    "repeatUnit" to (reminder.repeatUnit?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "repeatFrom" to (reminder.repeatFrom?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "repeatWeekDays" to (reminder.repeatWeekDays?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "repeatMonthOn" to (reminder.repeatMonthOn?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "patternDueAt" to (reminder.patternDueAt?.let { JsonValue.Num(it) } ?: JsonValue.Null),
    "alertLevel" to JsonValue.Str(reminder.alertLevel),
    "source" to JsonValue.Str(reminder.source),
    "sourceId" to (reminder.sourceId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "category" to (reminder.category?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "templateId" to (reminder.templateId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "templateVersion" to (reminder.templateVersion?.let { JsonValue.Num(it.toLong()) } ?: JsonValue.Null),
    "version" to JsonValue.Num(reminder.version.toLong()),
    "approval" to JsonValue.Str(reminder.approval),
    "requestedAlertLevel" to (reminder.requestedAlertLevel?.let { JsonValue.Str(it) } ?: JsonValue.Null),
).also { fields ->
    reminder.updatedAt?.let { fields["updatedAt"] = JsonValue.Num(it) }
}

private fun decodeSettings(value: JsonValue?): PortableSettings? {
    val obj = value as? JsonValue.Obj ?: return null
    val nag = (obj.fields["defaultNagMinutes"] as? JsonValue.Num)?.value?.toInt() ?: return null
    val snoozeValue = obj.fields["snooze"] as? JsonValue.Arr ?: return null
    if (snoozeValue.items.size != SnoozeDue.DEFAULTS.size) return null
    val snooze = snoozeValue.items.map { decodeSnooze(it) ?: return null }
    val quick = obj.fields["quickTimes"] as? JsonValue.Obj ?: return null
    val postpone = PostponeMode.fromName((quick.fields["postpone"] as? JsonValue.Str)?.value) ?: return null
    val slotsValue = quick.fields["slots"] as? JsonValue.Arr ?: return null
    if (slotsValue.items.size != QuickTimes.SLOT_COUNT) return null
    val slots = slotsValue.items.map { decodeSlot(it) ?: return null }
    val week = WeekStart.fromName((obj.fields["weekStart"] as? JsonValue.Str)?.value) ?: return null
    val parse = (obj.fields["parseDates"] as? JsonValue.Bool)?.value ?: return null
    val alert = AlertLevel.fromName((obj.fields["defaultAlertLevel"] as? JsonValue.Str)?.value) ?: return null
    val prompt = RepeatPromptMode.fromName((obj.fields["repeatPromptMode"] as? JsonValue.Str)?.value) ?: return null
    var quietStart = quietMinute(obj.fields["quietStartMinute"])
    var quietEnd = quietMinute(obj.fields["quietEndMinute"])
    if (quietStart != null && quietEnd != null && quietStart == quietEnd) {
        quietStart = null
        quietEnd = null
    }
    return PortableSettings(
        defaultNagMinutes = nag,
        snooze = snooze,
        quickTimes = QuickTimesSettings(slots, postpone),
        weekStart = week,
        parseDates = parse,
        defaultAlertLevel = alert,
        repeatPromptMode = prompt,
        quietEnabled = optionalBool(obj.fields["quietEnabled"]),
        quietStartMinute = quietStart,
        quietEndMinute = quietEnd,
        quietFollowDnd = optionalBool(obj.fields["quietFollowDnd"]),
        quietCriticalPauses = optionalBool(obj.fields["quietCriticalPauses"]),
        sysdRegion = optionalString(obj.fields["sysdRegion"])?.takeIf { RegionResolver.parse(it) != null },
        sysdRegionSource = optionalString(obj.fields["sysdRegionSource"])?.takeIf { RegionSource.fromName(it) != null },
        sysdAreas = sysdAreas(obj.fields["sysdAreas"]),
    )
}

/** Spec 11 optional key. Unknown areas are dropped; a missing or wrongly typed value is ignored. */
private fun sysdAreas(value: JsonValue?): List<String>? {
    val arr = value as? JsonValue.Arr ?: return null
    return arr.items.mapNotNull { ReminderCategory.normalize((it as? JsonValue.Str)?.value) }.distinct()
}

/** A missing or unusable quiet minute is ignored. Out of range is ignored. */
private fun quietMinute(value: JsonValue?): Int? {
    val minute = when (value) {
        null, JsonValue.Null -> return null
        is JsonValue.Num -> value.value.toInt()
        else -> return null
    }
    return minute.takeIf { QuietHours.minuteInRange(it) }
}

private fun optionalBool(value: JsonValue?): Boolean? = when (value) {
    null, JsonValue.Null -> null
    is JsonValue.Bool -> value.value
    else -> null
}

private fun decodeSnooze(value: JsonValue): SnoozeOption? {
    val obj = value as? JsonValue.Obj ?: return null
    val kind = SnoozeKind.fromName((obj.fields["kind"] as? JsonValue.Str)?.value) ?: return null
    val amount = (obj.fields["value"] as? JsonValue.Num)?.value?.toInt() ?: return null
    return SnoozeOption.orNull(kind, amount)
}

private fun decodeSlot(value: JsonValue): QuickTimeSlot? {
    val obj = value as? JsonValue.Obj ?: return null
    val kind = SnoozeKind.fromName((obj.fields["kind"] as? JsonValue.Str)?.value) ?: return null
    val amount = (obj.fields["amount"] as? JsonValue.Num)?.value?.toInt() ?: return null
    val unit = QuickTimeUnit.fromName((obj.fields["unit"] as? JsonValue.Str)?.value) ?: return null
    return QuickTimeSlot.orNull(kind, amount, unit)
}

private fun decodeReminder(value: JsonValue): PortableReminder? {
    val obj = value as? JsonValue.Obj ?: return null
    val uuid = (obj.fields["uuid"] as? JsonValue.Str)?.value?.trim().orEmpty()
    if (uuid.isEmpty()) return null
    val title = (obj.fields["title"] as? JsonValue.Str)?.value ?: return null
    val dueAt = (obj.fields["dueAt"] as? JsonValue.Num)?.value ?: return null
    val createdAt = (obj.fields["createdAt"] as? JsonValue.Num)?.value ?: return null
    val isDone = (obj.fields["isDone"] as? JsonValue.Bool)?.value ?: return null
    val alert = (obj.fields["alertLevel"] as? JsonValue.Str)?.value ?: return null
    return PortableReminder(
        uuid = uuid,
        title = title,
        dueAt = dueAt,
        nagMinutes = optionalInt(obj.fields["nagMinutes"]),
        isDone = isDone,
        doneAt = optionalLong(obj.fields["doneAt"]),
        createdAt = createdAt,
        repeatInterval = optionalInt(obj.fields["repeatInterval"]),
        repeatUnit = optionalString(obj.fields["repeatUnit"]),
        repeatFrom = optionalString(obj.fields["repeatFrom"]),
        repeatWeekDays = optionalString(obj.fields["repeatWeekDays"]),
        repeatMonthOn = optionalString(obj.fields["repeatMonthOn"]),
        patternDueAt = optionalLong(obj.fields["patternDueAt"]),
        alertLevel = alert,
        source = (ReminderSource.fromName(lenientString(obj.fields["source"])) ?: ReminderSource.DEFAULT).name,
        sourceId = Origin.clipSourceId(lenientString(obj.fields["sourceId"])),
        category = ReminderCategory.normalize(lenientString(obj.fields["category"])),
        templateId = Origin.clipSourceId(lenientString(obj.fields["templateId"])),
        templateVersion = lenientLong(obj.fields["templateVersion"])?.toInt(),
        updatedAt = lenientLong(obj.fields["updatedAt"])?.takeIf { it > 0L },
        version = lenientLong(obj.fields["version"])?.toInt()?.takeIf { it >= 1 } ?: 1,
        approval = (Approval.fromName(lenientString(obj.fields["approval"])) ?: Approval.DEFAULT).name,
        requestedAlertLevel = AlertLevel.fromName(lenientString(obj.fields["requestedAlertLevel"]))?.name,
    )
}

/**
 * Schema 2 reminder keys follow the optional-key rule Spec 8 used for quiet hours:
 * a missing, null, unknown or wrongly typed value takes the default, never a failure.
 */
private fun lenientString(value: JsonValue?): String? = (value as? JsonValue.Str)?.value

private fun lenientLong(value: JsonValue?): Long? = (value as? JsonValue.Num)?.value

private fun optionalInt(value: JsonValue?): Int? = when (value) {
    null, JsonValue.Null -> null
    is JsonValue.Num -> value.value.toInt()
    else -> throw JsonParseException("bad number")
}

private fun optionalLong(value: JsonValue?): Long? = when (value) {
    null, JsonValue.Null -> null
    is JsonValue.Num -> value.value
    else -> throw JsonParseException("bad number")
}

private fun optionalString(value: JsonValue?): String? = when (value) {
    null, JsonValue.Null -> null
    is JsonValue.Str -> value.value
    else -> throw JsonParseException("bad string")
}

private fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj {
    return JsonValue.Obj(linkedMapOf(*pairs))
}
