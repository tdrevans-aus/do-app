package com.evansappstudio.doapp.alarm

import android.content.Context
import com.evansappstudio.doapp.data.Reminder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Device-protected copy of the reminders that still need an alarm.
 *
 * Room lives in credential storage and cannot be read before the first unlock.
 * The mirror is written on every schedule change. It keeps dueAt and nagMinutes
 * as well as the spec's id, title and next fire time, so a fire before unlock
 * can post the notification and schedule the next anchored nag.
 * nextFireAt null means nag-off has already been delivered.
 * pendingSnoozeDueAt is a snooze applied before unlock. rescheduleAll writes it
 * to Room. If pendingDone is also set, done wins and the snooze is dropped.
 */
data class MirrorEntry(
    val id: Long,
    val title: String,
    val dueAt: Long,
    val nagMinutes: Int?,
    val nextFireAt: Long?,
    val pendingDone: Boolean = false,
    val pendingSnoozeDueAt: Long? = null,
    val pendingDoneAt: Long? = null,
    val wallClock: Boolean = false,
    val alertLevel: String = "STANDARD",
    val awaitingAttend: Boolean = false,
    val dndHeld: Boolean = false,
) {
    /** A pending nag is a later fire for this due. nextFireAt == dueAt is a first fire. */
    fun pendingNag(): Boolean {
        val next = nextFireAt ?: return false
        return next > dueAt
    }
}

class ReminderMirror internal constructor(
    private val file: File,
) {
    constructor(context: Context) : this(
        File(
            context.createDeviceProtectedStorageContext().filesDir,
            FILE_NAME,
        ).also { it.parentFile?.mkdirs() },
    )

    private val lock = Any()

    fun all(): List<MirrorEntry> = synchronized(lock) { read() }

    fun get(id: Long): MirrorEntry? = synchronized(lock) { read().find { it.id == id } }

    fun upsert(entry: MirrorEntry) = synchronized(lock) {
        val updated = read().filterNot { it.id == entry.id } + entry
        write(updated)
    }

    fun remove(id: Long) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    private fun read(): List<MirrorEntry> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        MirrorEntry(
                            id = item.getLong("id"),
                            title = item.optString("title"),
                            dueAt = item.optLong("dueAt"),
                            nagMinutes = if (item.isNull("nagMinutes")) {
                                null
                            } else {
                                item.getInt("nagMinutes")
                            },
                            nextFireAt = if (item.isNull("nextFireAt")) {
                                null
                            } else {
                                item.getLong("nextFireAt")
                            },
                            pendingDone = item.optBoolean("pendingDone", false),
                            pendingSnoozeDueAt = if (
                                !item.has("pendingSnoozeDueAt") || item.isNull("pendingSnoozeDueAt")
                            ) {
                                null
                            } else {
                                item.getLong("pendingSnoozeDueAt")
                            },
                            pendingDoneAt = if (!item.has("pendingDoneAt") || item.isNull("pendingDoneAt")) {
                                null
                            } else {
                                item.getLong("pendingDoneAt")
                            },
                            wallClock = item.optBoolean("wallClock", false),
                            alertLevel = item.optString("alertLevel", "STANDARD").ifBlank { "STANDARD" },
                            awaitingAttend = item.optBoolean("awaitingAttend", false),
                            dndHeld = item.optBoolean("dndHeld", false),
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            android.util.Log.e(DoAlarmLog.TAG, "DoAlarm mirror unreadable ${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun write(entries: List<MirrorEntry>) {
        val array = JSONArray()
        for (entry in entries.sortedBy { it.id }) {
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("dueAt", entry.dueAt)
                    .put("nagMinutes", entry.nagMinutes ?: JSONObject.NULL)
                    .put("nextFireAt", entry.nextFireAt ?: JSONObject.NULL)
                    .put("pendingDone", entry.pendingDone)
                    .put("pendingSnoozeDueAt", entry.pendingSnoozeDueAt ?: JSONObject.NULL)
                    .put("pendingDoneAt", entry.pendingDoneAt ?: JSONObject.NULL)
                    .put("wallClock", entry.wallClock)
                    .put("alertLevel", entry.alertLevel)
                    .put("awaitingAttend", entry.awaitingAttend)
                    .put("dndHeld", entry.dndHeld),
            )
        }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            file.writeText(array.toString())
            temp.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "reminder-mirror.json"
    }
}

fun Reminder.toMirror(
    nextFireAt: Long?,
    pendingDone: Boolean = false,
    pendingSnoozeDueAt: Long? = null,
    pendingDoneAt: Long? = null,
    awaitingAttend: Boolean = false,
    dndHeld: Boolean = false,
): MirrorEntry = MirrorEntry(
    id = id,
    title = title,
    dueAt = dueAt,
    nagMinutes = nagMinutes,
    nextFireAt = nextFireAt,
    pendingDone = pendingDone,
    pendingSnoozeDueAt = pendingSnoozeDueAt,
    pendingDoneAt = pendingDoneAt,
    wallClock = repeatRule()?.keepsWallClock() == true,
    alertLevel = alertLevel.ifBlank { "STANDARD" },
    awaitingAttend = awaitingAttend,
    dndHeld = dndHeld,
)
