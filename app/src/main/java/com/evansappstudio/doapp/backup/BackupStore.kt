package com.evansappstudio.doapp.backup

import android.annotation.SuppressLint
import android.content.Context

/**
 * Credential-protected backup state. Not part of the JSON file.
 * A system Auto Backup of this file is excluded on purpose: restoring it would
 * clear "Reminders Not Backed Up" without a backup the user can see.
 */
class BackupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun lastSuccessAt(): Long = synchronized(lock) { prefs.getLong(KEY_SUCCESS, 0L) }

    fun lastAutoAt(): Long = synchronized(lock) { prefs.getLong(KEY_AUTO, 0L) }

    fun lastFailed(): Boolean = synchronized(lock) { prefs.getBoolean(KEY_FAILED, false) }

    fun folderUri(): String? = synchronized(lock) { prefs.getString(KEY_URI, null)?.ifBlank { null } }

    fun folderPath(): String? = synchronized(lock) { prefs.getString(KEY_PATH, null)?.ifBlank { null } }

    fun folderSet(): Boolean = folderUri() != null || folderPath() != null

    fun health(): BackupHealth {
        val success = lastSuccessAt()
        val failed = lastFailed()
        return BackupHealth(
            ok = success > 0L && !failed,
            successAt = success.takeIf { it > 0L },
            failed = failed,
        )
    }

    @SuppressLint("ApplySharedPref")
    fun setFolderUri(uri: String?) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_URI, uri?.ifBlank { null })
                .remove(KEY_PATH)
                .commit()
        }
    }

    @SuppressLint("ApplySharedPref")
    fun setFolderPath(path: String?) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_PATH, path?.ifBlank { null })
                .remove(KEY_URI)
                .commit()
        }
    }

    fun clearFolder() {
        setFolderPath(null)
    }

    @SuppressLint("ApplySharedPref")
    fun markSuccess(at: Long, auto: Boolean) {
        synchronized(lock) {
            val editor = prefs.edit()
                .putLong(KEY_SUCCESS, at)
                .putBoolean(KEY_FAILED, false)
            if (auto) editor.putLong(KEY_AUTO, at)
            editor.commit()
        }
    }

    @SuppressLint("ApplySharedPref")
    fun markFailure() {
        synchronized(lock) {
            prefs.edit().putBoolean(KEY_FAILED, true).commit()
        }
    }

    @SuppressLint("ApplySharedPref")
    fun clearSuccess() {
        synchronized(lock) {
            prefs.edit()
                .remove(KEY_SUCCESS)
                .remove(KEY_AUTO)
                .putBoolean(KEY_FAILED, false)
                .commit()
        }
    }

    companion object {
        const val PREFS = "do_backup"
        private const val KEY_SUCCESS = "last_success_at"
        private const val KEY_AUTO = "last_auto_at"
        private const val KEY_FAILED = "last_failed"
        private const val KEY_URI = "folder_uri"
        private const val KEY_PATH = "folder_path"
    }
}

data class BackupHealth(
    val ok: Boolean,
    val successAt: Long?,
    val failed: Boolean,
)
