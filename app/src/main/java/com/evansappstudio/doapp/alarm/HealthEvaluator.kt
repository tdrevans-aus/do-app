package com.evansappstudio.doapp.alarm

/**
 * Pure health-check rules. Device reads live in [HealthMonitor]; this object
 * only decides status, copy and which Fix to offer.
 *
 * A fire is late when actual minus scheduled is greater than [LATE_BOUNDARY_MS].
 * 60,000 ms exactly is on time. That is the Spec 1 phone tolerance.
 */
object HealthEvaluator {
    const val LATE_BOUNDARY_MS = 60_000L
    const val IMPORTANCE_NONE = 0
    const val STANDBY_RESTRICTED = 45
    const val STANDBY_NEVER = 50

    const val TEST_NONE = "none"
    const val TEST_OK = "OK"
    const val TEST_WARN = "WARN"

    fun isLate(scheduled: Long, actual: Long): Boolean {
        if (scheduled < 0L) return false
        return actual - scheduled > LATE_BOUNDARY_MS
    }

    /** 0 to 60 seconds after scheduled, and not early, is OK. Anything else is a warning. */
    fun testStatus(scheduled: Long, actual: Long): String {
        if (scheduled < 0L) return TEST_WARN
        val lag = actual - scheduled
        return if (lag in 0..LATE_BOUNDARY_MS) TEST_OK else TEST_WARN
    }

    fun evaluate(facts: HealthFacts): HealthReport {
        val items = buildList {
            add(notifications(facts))
            add(channels(facts))
            add(exactAlarm(facts))
            add(fullScreen(facts))
            add(dnd(facts))
            add(battery(facts))
            background(facts)?.let { add(it) }
            samsung(facts)?.let { add(it) }
            add(standby(facts))
            add(boot(facts))
            late(facts)?.let { add(it) }
        }
        val badge = items.any { it.status == HealthStatus.WARN || it.status == HealthStatus.BLOCKED }
        val testMessage = testMessage(facts)
        return HealthReport(
            items = items,
            badge = badge,
            dump = dump(facts, items),
            testResult = facts.testResult,
            testPending = facts.testPending,
            testEarly = facts.testEarly,
            testMessage = testMessage,
        )
    }

    fun latePhrase(lagMs: Long, samsung: Boolean): String {
        val minutes = ((lagMs + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
        val base = if (minutes == 1) {
            "A reminder fired 1 minute late."
        } else {
            "A reminder fired $minutes minutes late."
        }
        if (!samsung) return base
        return base + " On Samsung, check Sleeping apps, Deep sleeping apps, Never sleeping apps," +
            " and turn off Put unused apps to sleep."
    }

    private fun notifications(facts: HealthFacts): HealthItem {
        return if (facts.notificationsGranted) {
            item(
                HealthIds.NOTIFICATIONS,
                HealthStatus.OK,
                "Notifications",
                "Notifications are allowed.",
            )
        } else {
            item(
                HealthIds.NOTIFICATIONS,
                HealthStatus.BLOCKED,
                "Notifications",
                "Notifications are off, so nothing will alert.",
                HealthFix("Fix", HealthActionKind.APP_NOTIFICATIONS),
            )
        }
    }

    private fun channels(facts: HealthFacts): HealthItem {
        val unhealthy = facts.channels.firstOrNull { channel ->
            !channel.summary && channelUnhealthy(channel)
        }
        if (unhealthy == null) {
            return item(
                HealthIds.CHANNELS,
                HealthStatus.OK,
                "Notification channels",
                "Reminders, Critical and Prominent channels can alert.",
            )
        }
        val reason = if (!unhealthy.present) "missing" else "muted"
        return item(
            HealthIds.CHANNELS,
            HealthStatus.BLOCKED,
            "Notification channels",
            "The ${unhealthy.name} channel is $reason, so alerts will not make a sound or appear.",
            HealthFix("Fix", HealthActionKind.CHANNEL, unhealthy.id),
        )
    }

    private fun channelUnhealthy(channel: ChannelFact): Boolean {
        if (channel.summary) return false
        if (!channel.shouldExist && !channel.present) return false
        if (!channel.present) return true
        if (channel.importance == IMPORTANCE_NONE) return true
        return channel.blocked
    }

    private fun exactAlarm(facts: HealthFacts): HealthItem {
        val mode = facts.exactMode.ifBlank { "USE" }
        val flavour = "This build is $mode."
        if (facts.sdkInt <= 30 || facts.canScheduleExact) {
            return item(
                HealthIds.EXACT,
                HealthStatus.OK,
                "Alarms & reminders",
                "Alarms & reminders is allowed. $flavour",
            )
        }
        return item(
            HealthIds.EXACT,
            HealthStatus.WARN,
            "Alarms & reminders",
            "Alarms & reminders is off, so reminders may be late. $flavour",
            HealthFix("Fix", HealthActionKind.EXACT_ALARM),
        )
    }

    private fun fullScreen(facts: HealthFacts): HealthItem {
        return if (facts.fullScreenGranted) {
            item(
                HealthIds.FULL_SCREEN,
                HealthStatus.OK,
                "Full-screen alerts",
                "Full-screen alerts are allowed.",
            )
        } else {
            item(
                HealthIds.FULL_SCREEN,
                HealthStatus.WARN,
                "Full-screen alerts",
                "Full-screen alerts are off, so Prominent reminders fall back to a Critical-style notification.",
                HealthFix("Fix", HealthActionKind.FULL_SCREEN),
            )
        }
    }

    private fun dnd(facts: HealthFacts): HealthItem {
        return if (facts.dndGranted) {
            item(
                HealthIds.DND,
                HealthStatus.OK,
                "Do Not Disturb access",
                "Do Not Disturb access is allowed.",
            )
        } else {
            item(
                HealthIds.DND,
                HealthStatus.WARN,
                "Do Not Disturb access",
                "Do Not Disturb access is off, so Critical reminders may be silent when Do Not Disturb is on.",
                HealthFix("Fix", HealthActionKind.DND),
            )
        }
    }

    private fun battery(facts: HealthFacts): HealthItem {
        return if (facts.ignoringBatteryOptimizations) {
            item(
                HealthIds.BATTERY,
                HealthStatus.OK,
                "Battery optimisation",
                "Battery optimisation is off for Do.",
            )
        } else {
            item(
                HealthIds.BATTERY,
                HealthStatus.WARN,
                "Battery optimisation",
                "Battery optimisation is still on for Do. On some phones that can stop nags after a few hours.",
                HealthFix("Fix", HealthActionKind.BATTERY_LIST),
            )
        }
    }

    private fun background(facts: HealthFacts): HealthItem? {
        if (facts.sdkInt < 28) return null
        val restricted = facts.backgroundRestricted ?: return item(
            HealthIds.BACKGROUND,
            HealthStatus.OK,
            "Background restriction",
            "Background restriction could not be read.",
        )
        return if (restricted) {
            item(
                HealthIds.BACKGROUND,
                HealthStatus.BLOCKED,
                "Background restriction",
                "Background activity is restricted, so reminders may not fire once Do is in the background.",
                HealthFix("Fix", HealthActionKind.APP_DETAILS),
            )
        } else {
            item(
                HealthIds.BACKGROUND,
                HealthStatus.OK,
                "Background restriction",
                "Background activity is allowed.",
            )
        }
    }

    private fun samsung(facts: HealthFacts): HealthItem? {
        if (!facts.samsung) return null
        return item(
            HealthIds.SAMSUNG,
            HealthStatus.CHECK,
            "Samsung sleeping apps",
            "On Samsung, Sleeping apps or Deep sleeping apps can stop reminders. Check that Do is not on" +
                " those lists, add it to Never sleeping apps, and turn off Put unused apps to sleep." +
                " Path: Settings, Battery and device care, Battery, Background usage limits.",
            HealthFix("Fix", HealthActionKind.SAMSUNG_BATTERY),
            HealthFix("Learn more", HealthActionKind.LEARN_MORE),
        )
    }

    private fun standby(facts: HealthFacts): HealthItem {
        val bucket = facts.standbyBucket
        if (bucket == null) {
            return item(
                HealthIds.STANDBY,
                HealthStatus.OK,
                "App standby",
                "App standby is not reported on this Android version.",
            )
        }
        val name = standbyName(bucket)
        if (bucket == STANDBY_RESTRICTED || bucket == STANDBY_NEVER) {
            return item(
                HealthIds.STANDBY,
                HealthStatus.WARN,
                "App standby",
                "Android has classed Do as rarely used, which can limit background work. Bucket: $name.",
                HealthFix("Fix", HealthActionKind.APP_DETAILS),
            )
        }
        return item(
            HealthIds.STANDBY,
            HealthStatus.OK,
            "App standby",
            "Android standby is $name.",
        )
    }

    fun standbyName(bucket: Int): String = when (bucket) {
        5 -> "Exempted"
        10 -> "Active"
        20 -> "WorkingSet"
        30 -> "Frequent"
        40 -> "Rare"
        STANDBY_RESTRICTED -> "Restricted"
        STANDBY_NEVER -> "Never"
        else -> "Unknown"
    }

    private fun boot(facts: HealthFacts): HealthItem {
        val boot = facts.boot
        if (boot.receiverDisabled) {
            return item(
                HealthIds.BOOT,
                HealthStatus.BLOCKED,
                "Restart",
                "The boot receiver is disabled, so reminders will not be restored after a restart.",
                HealthFix("Fix", HealthActionKind.REENABLE_BOOT),
            )
        }
        val warnings = mutableListOf<String>()
        if (boot.rebootedSinceInstall && boot.userUnlocked && !boot.unlockReceiptThisBoot) {
            warnings += "Do did not hear the unlock-after-restart event, so some reminders may not have been restored."
        }
        if (boot.rebootedSinceInstall && boot.mirrorNeededPreUnlock && !boot.lockedReceiptThisBoot) {
            warnings += "Do did not hear the restart event before unlock, so a reminder due before you unlock may have been missed."
        }
        if (boot.userUnlocked && boot.mirrorMissingActive && boot.activeReminderCount > 0) {
            warnings += "Some reminders are missing from the restart copy, so they may not fire before the first unlock."
        }
        if (warnings.isEmpty()) {
            val explanation = if (boot.rebootedSinceInstall) {
                "Reminders can be restored after a restart."
            } else {
                "No restart has been seen since Do was installed."
            }
            return item(HealthIds.BOOT, HealthStatus.OK, "Restart", explanation)
        }
        return item(
            HealthIds.BOOT,
            HealthStatus.WARN,
            "Restart",
            warnings.joinToString(" "),
            HealthFix("Restore alarms now", HealthActionKind.RESTORE_ALARMS),
            HealthFix("App details", HealthActionKind.APP_DETAILS),
        )
    }

    private fun late(facts: HealthFacts): HealthItem? {
        val lag = facts.lateLagMs ?: return null
        if (lag <= LATE_BOUNDARY_MS) return null
        return item(
            HealthIds.LATE,
            HealthStatus.WARN,
            "A reminder fired late",
            latePhrase(lag, facts.samsung),
            HealthFix("Clear", HealthActionKind.CLEAR_LATE),
        )
    }

    private fun testMessage(facts: HealthFacts): String {
        val pending = if (facts.testPending) {
            "A test alarm is set for about one minute from now."
        } else {
            ""
        }
        val result = when (facts.testResult) {
            TEST_OK -> "The last test alarm fired on time."
            TEST_WARN -> if (facts.testEarly) {
                "The last test alarm fired early."
            } else {
                "The last test alarm fired late."
            }
            else -> ""
        }
        return listOf(pending, result).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun dump(facts: HealthFacts, items: List<HealthItem>): String {
        fun status(id: String, missing: String = "NA"): String {
            return items.find { it.id == id }?.status?.dumpWord ?: missing
        }
        val exactMode = facts.exactMode.ifBlank { "USE" }
        val restricted = facts.backgroundRestricted
        val background = when {
            facts.sdkInt < 28 || restricted == null -> "NA"
            restricted -> "BLOCKED"
            else -> "OK"
        }
        val standby = facts.standbyBucket?.let { bucket ->
            val word = status(HealthIds.STANDBY)
            "$word:${standbyName(bucket)}:$bucket"
        } ?: "NA"
        val late = facts.lateLagMs?.takeIf { it > LATE_BOUNDARY_MS }?.toString() ?: "none"
        val test = when (facts.testResult) {
            TEST_OK, TEST_WARN -> facts.testResult
            else -> TEST_NONE
        }
        val unlocked = if (facts.boot.userUnlocked) "true" else "false"
        return listOf(
            "notif=${status(HealthIds.NOTIFICATIONS)}",
            "channels=${status(HealthIds.CHANNELS)}",
            "exact=${status(HealthIds.EXACT)}:$exactMode",
            "fsi=${status(HealthIds.FULL_SCREEN)}",
            "dnd=${status(HealthIds.DND)}",
            "battery=${status(HealthIds.BATTERY)}",
            "bgRestrict=$background",
            "samsung=${status(HealthIds.SAMSUNG)}",
            "standby=$standby",
            "boot=${status(HealthIds.BOOT)}",
            "unlocked=$unlocked",
            "lastLate=$late",
            "test=$test",
            "late=${status(HealthIds.LATE, "OK")}",
        ).joinToString(" ")
    }

    private fun item(
        id: String,
        status: HealthStatus,
        title: String,
        explanation: String,
        vararg fixes: HealthFix,
    ): HealthItem = HealthItem(id, status, title, explanation, fixes.toList())
}

enum class HealthStatus(val dumpWord: String) {
    OK("OK"),
    WARN("WARN"),
    BLOCKED("BLOCKED"),
    CHECK("CHECK"),
}

object HealthIds {
    const val NOTIFICATIONS = "notif"
    const val CHANNELS = "channels"
    const val EXACT = "exact"
    const val FULL_SCREEN = "fsi"
    const val DND = "dnd"
    const val BATTERY = "battery"
    const val BACKGROUND = "bg"
    const val SAMSUNG = "samsung"
    const val STANDBY = "standby"
    const val BOOT = "boot"
    const val LATE = "late"
}

enum class HealthActionKind {
    APP_NOTIFICATIONS,
    CHANNEL,
    EXACT_ALARM,
    FULL_SCREEN,
    DND,
    BATTERY_LIST,
    APP_DETAILS,
    SAMSUNG_BATTERY,
    SYSTEM_SETTINGS,
    RESTORE_ALARMS,
    REENABLE_BOOT,
    CLEAR_LATE,
    LEARN_MORE,
}

data class HealthFix(
    val label: String,
    val kind: HealthActionKind,
    val channelId: String? = null,
)

data class ChannelFact(
    val id: String,
    val name: String,
    val present: Boolean,
    val importance: Int,
    val blocked: Boolean,
    val shouldExist: Boolean,
    val summary: Boolean = false,
)

data class BootFacts(
    val receiverDisabled: Boolean = false,
    val userUnlocked: Boolean = true,
    val rebootedSinceInstall: Boolean = false,
    val unlockReceiptThisBoot: Boolean = false,
    val lockedReceiptThisBoot: Boolean = false,
    val mirrorNeededPreUnlock: Boolean = false,
    val activeReminderCount: Int = 0,
    val mirrorMissingActive: Boolean = false,
)

data class HealthFacts(
    val sdkInt: Int = 36,
    val notificationsGranted: Boolean = true,
    val channels: List<ChannelFact> = emptyList(),
    val canScheduleExact: Boolean = true,
    val exactMode: String = "USE",
    val fullScreenGranted: Boolean = true,
    val dndGranted: Boolean = true,
    val ignoringBatteryOptimizations: Boolean = true,
    val backgroundRestricted: Boolean? = false,
    val samsung: Boolean = false,
    val standbyBucket: Int? = 10,
    val boot: BootFacts = BootFacts(),
    val lateLagMs: Long? = null,
    val testResult: String = HealthEvaluator.TEST_NONE,
    val testPending: Boolean = false,
    val testEarly: Boolean = false,
)

data class HealthItem(
    val id: String,
    val status: HealthStatus,
    val title: String,
    val explanation: String,
    val fixes: List<HealthFix> = emptyList(),
)

data class HealthReport(
    val items: List<HealthItem>,
    val badge: Boolean,
    val dump: String,
    val testResult: String,
    val testPending: Boolean,
    val testEarly: Boolean,
    val testMessage: String,
) {
    companion object {
        val EMPTY = HealthReport(
            items = emptyList(),
            badge = false,
            dump = "",
            testResult = HealthEvaluator.TEST_NONE,
            testPending = false,
            testEarly = false,
            testMessage = "",
        )
    }
}
