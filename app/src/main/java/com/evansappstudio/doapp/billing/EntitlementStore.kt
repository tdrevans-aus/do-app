package com.evansappstudio.doapp.billing

import android.annotation.SuppressLint
import android.content.Context

/**
 * Credential-protected entitlement cache. Same unlock rule as [com.evansappstudio.doapp.data.SettingsStore].
 * The purchase token is never written here.
 */
class EntitlementStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun hasRecord(): Boolean = synchronized(lock) { prefs.contains(KEY_UNLOCKED) }

    fun read(): EntitlementRecord = synchronized(lock) {
        if (!prefs.contains(KEY_UNLOCKED)) return EntitlementRecord.EMPTY
        EntitlementRecord(
            unlocked = prefs.getBoolean(KEY_UNLOCKED, false),
            pending = prefs.getBoolean(KEY_PENDING, false),
            lastQueryAt = prefs.getLong(KEY_QUERY_AT, 0L),
            grantSource = prefs.getString(KEY_SOURCE, EntitlementPolicy.SOURCE_NONE)
                ?: EntitlementPolicy.SOURCE_NONE,
            lastQueryOk = prefs.getBoolean(KEY_QUERY_OK, false),
        )
    }

    @SuppressLint("ApplySharedPref")
    fun write(record: EntitlementRecord) {
        synchronized(lock) {
            prefs.edit()
                .putBoolean(KEY_UNLOCKED, record.unlocked)
                .putBoolean(KEY_PENDING, record.pending)
                .putLong(KEY_QUERY_AT, record.lastQueryAt)
                .putString(KEY_SOURCE, record.grantSource)
                .putBoolean(KEY_QUERY_OK, record.lastQueryOk)
                .commit()
        }
    }

    companion object {
        const val PREFS = "do_entitlement"
        private const val KEY_UNLOCKED = "unlocked"
        private const val KEY_PENDING = "pending"
        private const val KEY_QUERY_AT = "last_query_at"
        private const val KEY_SOURCE = "grant_source"
        private const val KEY_QUERY_OK = "last_query_ok"
    }
}
