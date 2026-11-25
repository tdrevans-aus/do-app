package com.evansappstudio.doapp.alarm

import android.content.Context

/**
 * Device-protected health records. Locked boot can write a receipt before
 * the user unlocks credential storage. Room is not used.
 */
class HealthStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun observeClock(bootCount: Int?, elapsed: Long, mirrorNeedsSchedule: Boolean) {
        synchronized(lock) { observeLocked(bootCount, elapsed, mirrorNeedsSchedule) }
    }

    fun recordBoot(lockedBoot: Boolean, bootCount: Int?, elapsed: Long, mirrorNeedsSchedule: Boolean) {
        synchronized(lock) {
            observeLocked(bootCount, elapsed, mirrorNeedsSchedule)
            val generation = prefs.getInt(KEY_GENERATION, 0)
            val edit = prefs.edit()
            if (lockedBoot) {
                edit.putInt(KEY_LOCKED_GEN, generation)
                if (mirrorNeedsSchedule) edit.putInt(KEY_MIRROR_NEEDED_GEN, generation)
            } else {
                edit.putInt(KEY_UNLOCK_GEN, generation)
            }
            edit.commit()
        }
    }

    fun bootSnapshot(): StoredBoot {
        synchronized(lock) {
            return StoredBoot(
                generation = prefs.getInt(KEY_GENERATION, 0),
                rebootedSinceInstall = prefs.getBoolean(KEY_REBOOTED, false),
                lockedGeneration = prefs.getInt(KEY_LOCKED_GEN, NO_GEN),
                unlockGeneration = prefs.getInt(KEY_UNLOCK_GEN, NO_GEN),
                mirrorNeededGeneration = prefs.getInt(KEY_MIRROR_NEEDED_GEN, NO_GEN),
            )
        }
    }

    fun setLate(lagMs: Long) {
        synchronized(lock) {
            prefs.edit().putLong(KEY_LATE_LAG, lagMs).commit()
        }
    }

    fun clearLate() {
        synchronized(lock) {
            prefs.edit().remove(KEY_LATE_LAG).commit()
        }
    }

    fun lateLagMs(): Long? {
        synchronized(lock) {
            if (!prefs.contains(KEY_LATE_LAG)) return null
            return prefs.getLong(KEY_LATE_LAG, 0L)
        }
    }

    fun setTestArmed(id: Long, scheduled: Long) {
        synchronized(lock) {
            prefs.edit()
                .putLong(KEY_TEST_ID, id)
                .putLong(KEY_TEST_SCHEDULED, scheduled)
                .commit()
        }
    }

    fun clearTestArmed() {
        synchronized(lock) {
            prefs.edit().remove(KEY_TEST_ID).remove(KEY_TEST_SCHEDULED).commit()
        }
    }

    fun testId(): Long? {
        synchronized(lock) {
            if (!prefs.contains(KEY_TEST_ID)) return null
            val id = prefs.getLong(KEY_TEST_ID, 0L)
            return id.takeIf { it > 0L }
        }
    }

    fun testScheduled(): Long {
        return synchronized(lock) { prefs.getLong(KEY_TEST_SCHEDULED, 0L) }
    }

    fun setTestResult(result: String, early: Boolean) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_TEST_RESULT, result)
                .putBoolean(KEY_TEST_EARLY, early)
                .commit()
        }
    }

    fun testResult(): String = synchronized(lock) {
        prefs.getString(KEY_TEST_RESULT, HealthEvaluator.TEST_NONE) ?: HealthEvaluator.TEST_NONE
    }

    fun testEarly(): Boolean = synchronized(lock) { prefs.getBoolean(KEY_TEST_EARLY, false) }

    fun saveReport(report: HealthReport) {
        synchronized(lock) {
            prefs.edit()
                .putBoolean(KEY_BADGE, report.badge)
                .putString(KEY_REPORT, encode(report))
                .commit()
        }
    }

    fun loadReport(): HealthReport {
        synchronized(lock) {
            val raw = prefs.getString(KEY_REPORT, null) ?: return HealthReport.EMPTY.copy(
                badge = prefs.getBoolean(KEY_BADGE, false),
            )
            return decode(raw) ?: HealthReport.EMPTY.copy(badge = prefs.getBoolean(KEY_BADGE, false))
        }
    }

    private fun observeLocked(bootCount: Int?, elapsed: Long, mirrorNeedsSchedule: Boolean) {
        if (!prefs.getBoolean(KEY_SEEN, false)) {
            val edit = prefs.edit()
                .putBoolean(KEY_SEEN, true)
                .putInt(KEY_GENERATION, 0)
                .putBoolean(KEY_REBOOTED, false)
                .putLong(KEY_ELAPSED, elapsed)
            if (bootCount != null) edit.putInt(KEY_BOOT_COUNT, bootCount)
            edit.commit()
            return
        }
        val previousElapsed = prefs.getLong(KEY_ELAPSED, elapsed)
        val previousBoot = if (prefs.contains(KEY_BOOT_COUNT)) prefs.getInt(KEY_BOOT_COUNT, -1) else null
        val rebooted = elapsed < previousElapsed ||
            (previousBoot != null && bootCount != null && bootCount > previousBoot)
        if (rebooted) {
            val generation = prefs.getInt(KEY_GENERATION, 0) + 1
            val edit = prefs.edit()
                .putInt(KEY_GENERATION, generation)
                .putBoolean(KEY_REBOOTED, true)
                .putLong(KEY_ELAPSED, elapsed)
            if (bootCount != null) edit.putInt(KEY_BOOT_COUNT, bootCount)
            if (mirrorNeedsSchedule) edit.putInt(KEY_MIRROR_NEEDED_GEN, generation)
            edit.commit()
        } else {
            val edit = prefs.edit().putLong(KEY_ELAPSED, elapsed)
            if (bootCount != null) edit.putInt(KEY_BOOT_COUNT, bootCount)
            edit.commit()
        }
    }

    data class StoredBoot(
        val generation: Int,
        val rebootedSinceInstall: Boolean,
        val lockedGeneration: Int,
        val unlockGeneration: Int,
        val mirrorNeededGeneration: Int,
    )

    companion object {
        private const val PREFS = "do-health"
        private const val NO_GEN = -1
        private const val KEY_SEEN = "seen"
        private const val KEY_GENERATION = "generation"
        private const val KEY_REBOOTED = "rebooted"
        private const val KEY_ELAPSED = "elapsed"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_LOCKED_GEN = "locked_gen"
        private const val KEY_UNLOCK_GEN = "unlock_gen"
        private const val KEY_MIRROR_NEEDED_GEN = "mirror_needed_gen"
        private const val KEY_LATE_LAG = "late_lag"
        private const val KEY_TEST_ID = "test_id"
        private const val KEY_TEST_SCHEDULED = "test_scheduled"
        private const val KEY_TEST_RESULT = "test_result"
        private const val KEY_TEST_EARLY = "test_early"
        private const val KEY_BADGE = "badge"
        private const val KEY_REPORT = "report"

        fun encode(report: HealthReport): String {
            val lines = ArrayList<String>()
            lines += "badge=${report.badge}"
            lines += "dump=${report.dump.replace('\n', ' ')}"
            lines += "testResult=${report.testResult}"
            lines += "testPending=${report.testPending}"
            lines += "testEarly=${report.testEarly}"
            lines += "testMessage=${report.testMessage.replace('\n', ' ')}"
            for (item in report.items) {
                val fixes = item.fixes.joinToString(",") { fix ->
                    listOf(fix.label, fix.kind.name, fix.channelId.orEmpty()).joinToString("~")
                }
                lines += listOf(
                    "item",
                    item.id,
                    item.status.name,
                    item.title.replace('\n', ' '),
                    item.explanation.replace('\n', ' '),
                    fixes,
                ).joinToString("\t")
            }
            return lines.joinToString("\n")
        }

        fun decode(raw: String): HealthReport? {
            return try {
                var badge = false
                var dump = ""
                var testResult = HealthEvaluator.TEST_NONE
                var testPending = false
                var testEarly = false
                var testMessage = ""
                val items = ArrayList<HealthItem>()
                for (line in raw.lineSequence()) {
                    when {
                        line.startsWith("badge=") -> badge = line.removePrefix("badge=") == "true"
                        line.startsWith("dump=") -> dump = line.removePrefix("dump=")
                        line.startsWith("testResult=") -> testResult = line.removePrefix("testResult=")
                        line.startsWith("testPending=") -> testPending = line.removePrefix("testPending=") == "true"
                        line.startsWith("testEarly=") -> testEarly = line.removePrefix("testEarly=") == "true"
                        line.startsWith("testMessage=") -> testMessage = line.removePrefix("testMessage=")
                        line.startsWith("item\t") -> {
                            val parts = line.split('\t')
                            if (parts.size < 5) continue
                            val status = HealthStatus.entries.firstOrNull { it.name == parts[2] } ?: continue
                            val fixes = if (parts.size < 6 || parts[5].isBlank()) {
                                emptyList()
                            } else {
                                parts[5].split(',').mapNotNull { token ->
                                    val bits = token.split('~')
                                    if (bits.size < 2) return@mapNotNull null
                                    val kind = HealthActionKind.entries.firstOrNull { it.name == bits[1] }
                                        ?: return@mapNotNull null
                                    HealthFix(bits[0], kind, bits.getOrNull(2)?.ifBlank { null })
                                }
                            }
                            items += HealthItem(parts[1], status, parts[3], parts[4], fixes)
                        }
                    }
                }
                HealthReport(items, badge, dump, testResult, testPending, testEarly, testMessage)
            } catch (error: Throwable) {
                null
            }
        }
    }
}
