package com.evansappstudio.doapp.backup

import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.Approval
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.alarm.QuietConfig
import com.evansappstudio.doapp.alarm.QuietPatch
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimeSlot
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.data.Reminder
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.WeekStart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Backup document. Local Room ids are not portable and are not in the file.
 * Schema 2 added the reminder provenance and change-tracking keys; every one is
 * optional on decode, so schema 1 files still import with the column defaults.
 */
data class PortableSettings(
    val defaultNagMinutes: Int,
    val snooze: List<SnoozeOption>,
    val quickTimes: QuickTimesSettings,
    val weekStart: WeekStart,
    val parseDates: Boolean,
    val defaultAlertLevel: AlertLevel,
    val repeatPromptMode: RepeatPromptMode,
    val quietEnabled: Boolean? = null,
    val quietStartMinute: Int? = null,
    val quietEndMinute: Int? = null,
    val quietFollowDnd: Boolean? = null,
    val quietCriticalPauses: Boolean? = null,
    /** Spec 11 optional keys: catalogue region token ("AU-WA", "AU", "none") and its source. */
    val sysdRegion: String? = null,
    val sysdRegionSource: String? = null,
    /** Spec 11 optional key: the onboarding profile (life areas). */
    val sysdAreas: List<String>? = null,
)

fun PortableSettings.withQuiet(config: QuietConfig): PortableSettings = copy(
    quietEnabled = config.enabled,
    quietStartMinute = config.startMinute,
    quietEndMinute = config.endMinute,
    quietFollowDnd = config.followDnd,
    quietCriticalPauses = config.criticalPauses,
)

fun PortableSettings.quietPatch() = QuietPatch(
    enabled = quietEnabled,
    startMinute = quietStartMinute,
    endMinute = quietEndMinute,
    followDnd = quietFollowDnd,
    criticalPauses = quietCriticalPauses,
)

data class PortableReminder(
    val uuid: String,
    val title: String,
    val dueAt: Long,
    val nagMinutes: Int?,
    val isDone: Boolean,
    val doneAt: Long?,
    val createdAt: Long,
    val repeatInterval: Int?,
    val repeatUnit: String?,
    val repeatFrom: String?,
    val repeatWeekDays: String?,
    val repeatMonthOn: String?,
    val patternDueAt: Long?,
    val alertLevel: String,
    val source: String = ReminderSource.DEFAULT.name,
    val sourceId: String? = null,
    val category: String? = null,
    val templateId: String? = null,
    val templateVersion: Int? = null,
    /** Null when the file has no updatedAt (schema 1); import then uses [createdAt]. */
    val updatedAt: Long? = null,
    val version: Int = 1,
    val approval: String = Approval.DEFAULT.name,
    val requestedAlertLevel: String? = null,
)

data class BackupDocument(
    val schemaVersion: Int,
    val appId: String,
    val appVersionName: String,
    val exportedAt: Long,
    val settings: PortableSettings,
    val reminders: List<PortableReminder>,
)

fun Reminder.toPortable(): PortableReminder = PortableReminder(
    uuid = uuid,
    title = title,
    dueAt = dueAt,
    nagMinutes = nagMinutes,
    isDone = isDone,
    doneAt = doneAt,
    createdAt = createdAt,
    repeatInterval = repeatInterval,
    repeatUnit = repeatUnit,
    repeatFrom = repeatFrom,
    repeatWeekDays = repeatWeekDays,
    repeatMonthOn = repeatMonthOn,
    patternDueAt = patternDueAt,
    alertLevel = alertLevel,
    source = source,
    sourceId = sourceId,
    category = category,
    templateId = templateId,
    templateVersion = templateVersion,
    updatedAt = updatedAt,
    version = version,
    approval = approval,
    requestedAlertLevel = requestedAlertLevel,
)

/** Soft-deleted rows are never exported, and deletedAt is never written. */
fun List<Reminder>.exportable(): List<PortableReminder> = filter { it.deletedAt == null }.map { it.toPortable() }

fun PortableReminder.toReminder(id: Long): Reminder = Reminder(
    id = id,
    uuid = uuid,
    title = title,
    dueAt = dueAt,
    nagMinutes = nagMinutes,
    isDone = isDone,
    doneAt = doneAt,
    createdAt = createdAt,
    repeatInterval = repeatInterval,
    repeatUnit = repeatUnit,
    repeatFrom = repeatFrom,
    repeatWeekDays = repeatWeekDays,
    repeatMonthOn = repeatMonthOn,
    patternDueAt = patternDueAt,
    alertLevel = alertLevel.ifBlank { AlertLevel.STANDARD.name },
    source = source,
    sourceId = sourceId,
    category = category,
    templateId = templateId,
    templateVersion = templateVersion,
    updatedAt = updatedAt ?: createdAt,
    version = version,
    deletedAt = null,
    approval = approval,
    requestedAlertLevel = requestedAlertLevel,
)

object BackupNames {
    const val SCHEMA_VERSION = 2
    const val APP_ID = "com.evansappstudio.doapp"
    const val AUTO_GAP_MS = 24L * 60L * 60L * 1000L
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** Device local time, 24-hour digits, no spaces. */
    fun suggested(epochMs: Long, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "do-backup-${stamp.format(local)}.json"
    }
}

/**
 * Write an automatic backup when a folder is set and either nothing has succeeded
 * yet or the last automatic backup is at least 24 hours old.
 */
object BackupSchedule {
    fun due(now: Long, lastSuccessAt: Long, lastAutoAt: Long, folderSet: Boolean): Boolean {
        if (!folderSet) return false
        if (lastSuccessAt <= 0L) return true
        if (lastAutoAt <= 0L) return true
        return now - lastAutoAt >= BackupNames.AUTO_GAP_MS
    }
}

enum class BackupMode {
    MERGE,
    REPLACE,
    ;

    companion object {
        fun fromName(raw: String?): BackupMode? = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
