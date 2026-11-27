package com.evansappstudio.doapp.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.evansappstudio.doapp.alarm.DoAlarmLog
import com.evansappstudio.doapp.alarm.HealthMonitor
import com.evansappstudio.doapp.alarm.QuietHoursStore
import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderCommands
import com.evansappstudio.doapp.alarm.SnoozeConfig
import com.evansappstudio.doapp.data.ReminderDao
import com.evansappstudio.doapp.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.ZoneId

class BackupController(
    context: Context,
    private val store: BackupStore,
    private val settings: SettingsStore,
    private val quiet: QuietHoursStore,
    private val snoozeConfig: SnoozeConfig,
    private val dao: () -> ReminderDao,
    private val commands: () -> ReminderCommands,
    private val health: () -> HealthMonitor,
    private val scope: CoroutineScope,
    private val versionName: String,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext
    private val gate = Mutex()
    private val uiState = MutableStateFlow(snapshotUi())
    val ui: StateFlow<BackupUi> = uiState.asStateFlow()

    fun noteChange() {
        scope.launch {
            try {
                maybeAutoBackup()
            } catch (error: Throwable) {
                DoAlarmLog.handlingFailed(id = -1, error)
            }
        }
    }

    suspend fun maybeAutoBackup(announceSkip: Boolean = false) {
        gate.withLock { autoLocked(announceSkip) }
    }

    suspend fun exportToFile(path: String): BackupWrite = gate.withLock {
        val file = File(path)
        val directory = file.parentFile ?: return@withLock fail(BackupDecode.CORRUPT, surface = true)
        loggedExport(writeBytes(directory, file.name, auto = false, surface = true))
    }

    suspend fun exportToUri(uri: Uri): BackupWrite = gate.withLock {
        try {
            val bytes = encodeCurrent()
            appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return@withLock fail(BackupDecode.CORRUPT, surface = true)
            loggedExport(succeed(uri.toString(), countReminders(bytes), auto = false))
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            fail(BackupDecode.CORRUPT, surface = true)
        }
    }

    /** Writes into the chosen folder, or asks the UI to open the export picker. */
    suspend fun backupNow(): BackupWrite = gate.withLock {
        if (!store.folderSet()) return@withLock BackupWrite.NeedsPicker
        loggedExport(writeToFolder(auto = true, surface = true))
    }

    suspend fun rememberTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            publish(error = BackupDecode.CORRUPT)
            return
        }
        store.setFolderUri(uri.toString())
        publish()
        DoAlarmLog.backupFolder(set = true)
    }

    suspend fun configureFolder(path: String?, clearSuccess: Boolean) {
        if (path != null) {
            if (path.isBlank()) {
                store.clearFolder()
            } else {
                store.setFolderPath(path)
            }
            DoAlarmLog.backupFolder(store.folderSet())
        }
        if (clearSuccess) {
            store.clearSuccess()
            publish()
            health().refresh()
        } else {
            publish()
        }
    }

    fun clearPreview() {
        publish(preview = null, error = null)
    }

    fun clearError() {
        publish(error = null)
    }

    suspend fun preview(bytes: ByteArray): BackupDecode {
        if (bytes.size > MAX_BYTES) {
            publish(error = BackupDecode.CORRUPT, preview = null)
            return BackupDecode.Rejected(BackupDecode.CORRUPT)
        }
        val decoded = BackupCodec.decode(bytes.toString(Charsets.UTF_8))
        when (decoded) {
            is BackupDecode.Ok -> publish(error = null, preview = decoded.document)
            is BackupDecode.Rejected -> publish(error = decoded.reason, preview = null)
        }
        return decoded
    }

    suspend fun importPath(path: String, mode: BackupMode?, uuids: Set<String>?): ImportOutcome {
        if (mode == null) {
            DoAlarmLog.backupImportError("mode")
            return ImportOutcome.Rejected("mode")
        }
        val bytes = try {
            File(path).readBytes()
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            DoAlarmLog.backupImportError(BackupDecode.CORRUPT)
            return ImportOutcome.Rejected(BackupDecode.CORRUPT)
        }
        if (bytes.size > MAX_BYTES) {
            DoAlarmLog.backupImportError(BackupDecode.CORRUPT)
            return ImportOutcome.Rejected(BackupDecode.CORRUPT)
        }
        return applyDecoded(BackupCodec.decode(bytes.toString(Charsets.UTF_8)), mode, uuids)
    }

    suspend fun applyPreview(mode: BackupMode, uuids: Set<String>?): ImportOutcome {
        val document = uiState.value.preview ?: return ImportOutcome.Rejected(BackupDecode.CORRUPT)
        return applyDecoded(BackupDecode.Ok(document), mode, uuids)
    }

    suspend fun reminderCount(): Int = dao().all().size

    private suspend fun applyDecoded(
        decoded: BackupDecode,
        mode: BackupMode,
        uuids: Set<String>?,
    ): ImportOutcome {
        val document = when (decoded) {
            is BackupDecode.Rejected -> {
                DoAlarmLog.backupImportError(decoded.reason)
                return ImportOutcome.Rejected(decoded.reason)
            }
            is BackupDecode.Ok -> decoded.document
        }
        val selected = if (mode == BackupMode.MERGE && uuids != null) {
            document.reminders.filter { it.uuid in uuids }
        } else {
            document.reminders
        }
        val previous = settings.portable()
        val previousQuiet = quiet.read()
        return try {
            if (mode == BackupMode.REPLACE) {
                settings.replacePortable(document.settings)
                snoozeConfig.setAll(settings.snoozeOptions())
                quiet.apply(document.settings.quietPatch())
            }
            val ids = when (mode) {
                BackupMode.REPLACE -> commands().replaceAll(IMPORT, document.reminders.map { it.toReminder(0) })
                BackupMode.MERGE -> commands().mergeAll(IMPORT, selected.map { it.toReminder(0) })
            }
            health().refresh()
            publish(preview = null, error = null)
            DoAlarmLog.backupImport(mode.name.lowercase(), selected.size, ids)
            ImportOutcome.Applied(selected.size, ids)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            if (mode == BackupMode.REPLACE) {
                try {
                    settings.replacePortable(previous)
                    snoozeConfig.setAll(settings.snoozeOptions())
                    quiet.write(previousQuiet)
                } catch (restore: Throwable) {
                    DoAlarmLog.handlingFailed(id = -1, restore)
                }
            }
            DoAlarmLog.backupImportError(BackupDecode.CORRUPT)
            ImportOutcome.Rejected(BackupDecode.CORRUPT)
        }
    }

    private suspend fun autoLocked(announceSkip: Boolean) {
        val due = BackupSchedule.due(
            now = clock(),
            lastSuccessAt = store.lastSuccessAt(),
            lastAutoAt = store.lastAutoAt(),
            folderSet = store.folderSet(),
        )
        if (!due) {
            if (announceSkip) {
                val reason = if (store.folderSet()) "gap" else "folder"
                DoAlarmLog.backupAutoSkipped(reason)
            }
            return
        }
        when (val result = writeToFolder(auto = true, surface = false)) {
            is BackupWrite.Saved -> DoAlarmLog.backupAuto(result.path, result.reminders)
            is BackupWrite.Failed -> DoAlarmLog.backupAutoError(result.reason)
            BackupWrite.NeedsPicker -> DoAlarmLog.backupAutoSkipped("folder")
        }
    }

    private suspend fun writeToFolder(auto: Boolean, surface: Boolean): BackupWrite {
        val path = store.folderPath()
        if (path != null) {
            return writeBytes(File(path), BackupNames.suggested(clock(), zone()), auto, surface)
        }
        val uri = store.folderUri()?.let { Uri.parse(it) } ?: return BackupWrite.NeedsPicker
        return try {
            val bytes = encodeCurrent()
            val name = BackupNames.suggested(clock(), zone())
            val created = DocumentsContract.createDocument(
                appContext.contentResolver,
                uri,
                "application/json",
                name,
            ) ?: return fail(BackupDecode.CORRUPT, surface)
            appContext.contentResolver.openOutputStream(created)?.use { it.write(bytes) }
                ?: return fail(BackupDecode.CORRUPT, surface)
            succeed(created.toString(), countReminders(bytes), auto)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            fail(BackupDecode.CORRUPT, surface)
        }
    }

    private suspend fun writeBytes(
        directory: File,
        name: String,
        auto: Boolean,
        surface: Boolean,
    ): BackupWrite {
        return try {
            val bytes = encodeCurrent()
            val file = writeUnique(directory, name, bytes)
            succeed(file.absolutePath, countReminders(bytes), auto)
        } catch (error: Throwable) {
            DoAlarmLog.handlingFailed(id = -1, error)
            fail(BackupDecode.CORRUPT, surface)
        }
    }

    private fun loggedExport(result: BackupWrite): BackupWrite {
        if (result is BackupWrite.Saved) DoAlarmLog.backupExport(result.path, result.reminders)
        return result
    }

    private suspend fun succeed(path: String, reminders: Int, auto: Boolean): BackupWrite {
        store.markSuccess(clock(), auto)
        publish()
        health().refresh()
        return BackupWrite.Saved(path, reminders)
    }

    private suspend fun fail(reason: String, surface: Boolean): BackupWrite {
        store.markFailure()
        if (surface) publish(error = reason) else publish()
        health().refresh()
        return BackupWrite.Failed(reason)
    }

    private suspend fun encodeCurrent(): ByteArray {
        val document = BackupDocument(
            schemaVersion = BackupNames.SCHEMA_VERSION,
            appId = BackupNames.APP_ID,
            appVersionName = versionName,
            exportedAt = clock(),
            settings = settings.portable().withQuiet(quiet.read()),
            reminders = dao().all().exportable(),
        )
        return BackupCodec.encode(document).toByteArray(Charsets.UTF_8)
    }

    private fun countReminders(bytes: ByteArray): Int {
        val decoded = BackupCodec.decode(bytes.toString(Charsets.UTF_8))
        return (decoded as? BackupDecode.Ok)?.document?.reminders?.size ?: 0
    }

    private fun snapshotUi() = BackupUi(
        lastSuccessAt = store.lastSuccessAt(),
        lastFailed = store.lastFailed(),
        folderSet = store.folderSet(),
        error = null,
        preview = null,
    )

    private fun publish(
        error: String? = uiState.value.error,
        preview: BackupDocument? = uiState.value.preview,
    ) {
        uiState.value = BackupUi(
            lastSuccessAt = store.lastSuccessAt(),
            lastFailed = store.lastFailed(),
            folderSet = store.folderSet(),
            error = error,
            preview = preview,
        )
    }

    private fun writeUnique(directory: File, baseName: String, bytes: ByteArray): File {
        if (!directory.exists() && !directory.mkdirs()) error("Could not create the backup folder")
        if (!directory.isDirectory) error("Backup folder is not a directory")
        var candidate = File(directory, baseName)
        var suffix = 2
        while (candidate.exists()) {
            val dot = baseName.lastIndexOf('.')
            val stem = if (dot > 0) baseName.substring(0, dot) else baseName
            val ext = if (dot > 0) baseName.substring(dot) else ""
            candidate = File(directory, "$stem-$suffix$ext")
            suffix++
            if (suffix > 1000) error("Could not choose a backup name")
        }
        val temp = File(directory, candidate.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(candidate)) {
            candidate.writeBytes(bytes)
            temp.delete()
        }
        return candidate
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        private val IMPORT = Origin(Actor.IMPORT)
    }
}

data class BackupUi(
    val lastSuccessAt: Long,
    val lastFailed: Boolean,
    val folderSet: Boolean,
    val error: String?,
    val preview: BackupDocument?,
)

sealed class BackupWrite {
    data class Saved(val path: String, val reminders: Int) : BackupWrite()
    data object NeedsPicker : BackupWrite()
    data class Failed(val reason: String) : BackupWrite()
}

sealed class ImportOutcome {
    data class Applied(val count: Int, val ids: List<Long>) : ImportOutcome()
    data class Rejected(val reason: String) : ImportOutcome()
}
