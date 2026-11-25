package com.evansappstudio.doapp.alarm

import android.annotation.SuppressLint
import android.content.Context

/**
 * Device-protected copy of the three snooze options.
 *
 * [com.evansappstudio.doapp.data.SettingsStore] lives in credential storage,
 * so a notification posted before the first unlock cannot read it. This copy
 * is what gets baked into action extras. After unlock the credential copy is
 * the source of truth and is written through here.
 */
class SnoozeConfig(context: Context) {
    private val prefs = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): List<SnoozeOption> = List(COUNT) { index ->
        val kind = SnoozeKind.fromName(prefs.getString(kindKey(index), null))
        val value = prefs.getInt(valueKey(index), Int.MIN_VALUE)
        if (kind == null) {
            SnoozeDue.DEFAULTS[index]
        } else {
            SnoozeOption.orNull(kind, value) ?: SnoozeDue.DEFAULTS[index]
        }
    }

    @SuppressLint("ApplySharedPref")
    fun setAll(options: List<SnoozeOption>) {
        require(options.size == COUNT) { "expected $COUNT snooze options" }
        require(options.all { it.isValid() }) { "invalid snooze option" }
        val editor = prefs.edit()
        options.forEachIndexed { index, option ->
            editor.putString(kindKey(index), option.kind.name)
            editor.putInt(valueKey(index), option.value)
        }
        // commit, not apply: the copy has to be on disk before a process kill.
        editor.commit()
    }

    private fun kindKey(index: Int) = "kind_$index"

    private fun valueKey(index: Int) = "value_$index"

    private companion object {
        const val PREFS = "snooze_config"
        const val COUNT = 3
    }
}
