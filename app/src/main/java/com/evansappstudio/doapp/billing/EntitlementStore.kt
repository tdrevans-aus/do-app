package com.evansappstudio.doapp.billing

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Credential-protected entitlement cache. Same unlock rule as [com.evansappstudio.doapp.data.SettingsStore].
 * The purchase token is never written here.
 *
 * The [KEY_UNLOCKED] key keeps its Spec 7 name and now means "subscription active".
 * [KEY_EVER_SUBSCRIBED] is set by [EntitlementPolicy] on the first grant and
 * only the debug fake clears it. This file is never in Do's own backup file,
 * so importing a Do backup never grants anything.
 */
class EntitlementStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    private val lock = Any()

    fun hasRecord(): Boolean = synchronized(lock) { prefs.contains(KEY_UNLOCKED) }

    fun read(): EntitlementRecord = synchronized(lock) {
        if (!prefs.contains(KEY_UNLOCKED)) return EntitlementRecord.EMPTY
        EntitlementRecord(
            active = prefs.getBoolean(KEY_UNLOCKED, false),
            pending = prefs.getBoolean(KEY_PENDING, false),
            lastQueryAt = prefs.getLong(KEY_QUERY_AT, 0L),
            grantSource = prefs.getString(KEY_SOURCE, EntitlementPolicy.SOURCE_NONE)
                ?: EntitlementPolicy.SOURCE_NONE,
            lastQueryOk = prefs.getBoolean(KEY_QUERY_OK, false),
            everSubscribed = prefs.getBoolean(KEY_EVER_SUBSCRIBED, false),
        )
    }

    @SuppressLint("ApplySharedPref")
    fun write(record: EntitlementRecord) {
        synchronized(lock) {
            prefs.edit()
                .putBoolean(KEY_UNLOCKED, record.active)
                .putBoolean(KEY_PENDING, record.pending)
                .putLong(KEY_QUERY_AT, record.lastQueryAt)
                .putString(KEY_SOURCE, record.grantSource)
                .putBoolean(KEY_QUERY_OK, record.lastQueryOk)
                .putBoolean(KEY_EVER_SUBSCRIBED, record.everSubscribed)
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
        private const val KEY_EVER_SUBSCRIBED = "ever_subscribed"
    }
}
