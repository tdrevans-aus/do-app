package com.evansappstudio.doapp.alarm

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthEvaluatorTest {
    private companion object {
        const val ONE_TAP_BATTERY_REQUEST = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    }

    @Test
    fun platformConstantsMatchTheEvaluatorThresholds() {
        assertEquals(NotificationManager.IMPORTANCE_NONE, HealthEvaluator.IMPORTANCE_NONE)
        assertEquals(UsageStatsManager.STANDBY_BUCKET_RESTRICTED, HealthEvaluator.STANDBY_RESTRICTED)
        // API 36's public stub does not declare STANDBY_BUCKET_NEVER. The platform value is still 50.
        assertEquals(50, HealthEvaluator.STANDBY_NEVER)
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            HealthIntents.ACTION_BATTERY_LIST,
        )
    }

    @Test
    fun notificationsAreBlockedOnlyWhenThePermissionIsOff() {
        val granted = evaluate(healthy())
        assertEquals(HealthStatus.OK, granted.item(HealthIds.NOTIFICATIONS).status)
        assertTrue(granted.item(HealthIds.NOTIFICATIONS).fixes.isEmpty())

        val denied = evaluate(healthy().copy(notificationsGranted = false))
        val row = denied.item(HealthIds.NOTIFICATIONS)
        assertEquals(HealthStatus.BLOCKED, row.status)
        assertEquals("Notifications are off, so nothing will alert.", row.explanation)
        assertEquals(HealthActionKind.APP_NOTIFICATIONS, row.fixes.single().kind)
        assertTrue(denied.badge)
    }

    @Test
    fun eachAlertChannelIsBlockedWhenMutedMissingOrImportanceNone() {
        val names = listOf("Reminders" to "reminders", "Critical reminders" to "reminders_critical", "Prominent reminders" to "reminders_prominent")
        for ((name, id) in names) {
            val none = evaluate(healthy().copy(channels = channels(id, importance = HealthEvaluator.IMPORTANCE_NONE)))
            val noneRow = none.item(HealthIds.CHANNELS)
            assertEquals(HealthStatus.BLOCKED, noneRow.status)
            assertTrue(noneRow.explanation.contains("The $name channel is muted"))
            assertEquals(id, noneRow.fixes.single().channelId)

            val blocked = evaluate(healthy().copy(channels = channels(id, blocked = true)))
            assertEquals(HealthStatus.BLOCKED, blocked.item(HealthIds.CHANNELS).status)
            assertEquals(id, blocked.item(HealthIds.CHANNELS).fixes.single().channelId)

            val missing = evaluate(healthy().copy(channels = channels(id, present = false, shouldExist = true)))
            val missingRow = missing.item(HealthIds.CHANNELS)
            assertEquals(HealthStatus.BLOCKED, missingRow.status)
            assertTrue(missingRow.explanation.contains("The $name channel is missing"))
        }
    }

    @Test
    fun aChannelThatHasNotBeenCreatedYetIsNotAFault() {
        val report = evaluate(
            healthy().copy(
                channels = listOf(
                    ChannelFact("reminders", "Reminders", present = false, importance = 0, blocked = false, shouldExist = false),
                    ChannelFact("reminders_critical", "Critical reminders", present = false, importance = 0, blocked = false, shouldExist = false),
                    ChannelFact("reminders_prominent", "Prominent reminders", present = false, importance = 0, blocked = false, shouldExist = false),
                ),
            ),
        )
        assertEquals(HealthStatus.OK, report.item(HealthIds.CHANNELS).status)
        assertFalse(report.badge)
    }

    @Test
    fun theFirstUnhealthyChannelIsTheOneNamedAndFixed() {
        val report = evaluate(
            healthy().copy(
                channels = listOf(
                    healthyChannel("reminders", "Reminders"),
                    ChannelFact("reminders_critical", "Critical reminders", present = true, importance = 0, blocked = false, shouldExist = true),
                    ChannelFact("reminders_prominent", "Prominent reminders", present = false, importance = 4, blocked = true, shouldExist = true),
                ),
            ),
        )
        val row = report.item(HealthIds.CHANNELS)
        assertTrue(row.explanation.contains("Critical reminders"))
        assertEquals("reminders_critical", row.fixes.single().channelId)
    }

    @Test
    fun theSilentSummaryChannelIsNeverAFault() {
        val report = evaluate(
            healthy().copy(
                channels = healthyChannels() + ChannelFact(
                    id = "reminders_summary",
                    name = "Reminder summary",
                    present = true,
                    importance = HealthEvaluator.IMPORTANCE_NONE,
                    blocked = true,
                    shouldExist = true,
                    summary = true,
                ),
            ),
        )
        assertEquals(HealthStatus.OK, report.item(HealthIds.CHANNELS).status)
        assertEquals("OK", report.dump.substringAfter("channels=").substringBefore(" "))
    }

    @Test
    fun aMissingSummaryChannelDoesNotFaultTheAlertChannels() {
        val report = evaluate(
            healthy().copy(
                channels = healthyChannels() + ChannelFact(
                    id = "reminders_summary",
                    name = "Reminder summary",
                    present = false,
                    importance = 0,
                    blocked = false,
                    shouldExist = true,
                    summary = true,
                ),
            ),
        )
        assertEquals(HealthStatus.OK, report.item(HealthIds.CHANNELS).status)
    }

    @Test
    fun exactAlarmIsOkBelowApi31AndWarnsWithTheBuildFlavourFromApi31() {
        for (sdk in listOf(26, 30)) {
            val report = evaluate(healthy().copy(sdkInt = sdk, canScheduleExact = false, exactMode = "USE"))
            assertEquals(HealthStatus.OK, report.item(HealthIds.EXACT).status)
            assertTrue(report.item(HealthIds.EXACT).explanation.contains("USE"))
        }
        for (sdk in listOf(31, 32, 33, 36)) {
            val missingUse = evaluate(healthy().copy(sdkInt = sdk, canScheduleExact = false, exactMode = "USE"))
            val row = missingUse.item(HealthIds.EXACT)
            assertEquals(HealthStatus.WARN, row.status)
            assertEquals(
                "Alarms & reminders is off, so reminders may be late. This build is USE.",
                row.explanation,
            )
            assertEquals(HealthActionKind.EXACT_ALARM, row.fixes.single().kind)
            assertTrue(missingUse.dump.contains("exact=WARN:USE"))

            val missingSchedule = evaluate(healthy().copy(sdkInt = sdk, canScheduleExact = false, exactMode = "SCHEDULE"))
            assertTrue(missingSchedule.item(HealthIds.EXACT).explanation.contains("SCHEDULE"))
            assertTrue(missingSchedule.dump.contains("exact=WARN:SCHEDULE"))

            val granted = evaluate(healthy().copy(sdkInt = sdk, canScheduleExact = true, exactMode = "USE"))
            assertEquals(HealthStatus.OK, granted.item(HealthIds.EXACT).status)
            assertTrue(granted.dump.contains("exact=OK:USE"))
        }
    }

    @Test
    fun fullScreenAndDoNotDisturbMissesAreWarnings() {
        val fullScreen = evaluate(healthy().copy(fullScreenGranted = false))
        val fullRow = fullScreen.item(HealthIds.FULL_SCREEN)
        assertEquals(HealthStatus.WARN, fullRow.status)
        assertEquals(
            "Full-screen alerts are off, so Prominent reminders fall back to a Critical-style notification.",
            fullRow.explanation,
        )
        assertEquals(HealthActionKind.FULL_SCREEN, fullRow.fixes.single().kind)
        assertEquals(HealthStatus.OK, evaluate(healthy().copy(fullScreenGranted = true)).item(HealthIds.FULL_SCREEN).status)

        val dnd = evaluate(healthy().copy(dndGranted = false))
        val dndRow = dnd.item(HealthIds.DND)
        assertEquals(HealthStatus.WARN, dndRow.status)
        assertEquals(
            "Do Not Disturb access is off, so Critical reminders may be silent when Do Not Disturb is on.",
            dndRow.explanation,
        )
        assertEquals(HealthActionKind.DND, dndRow.fixes.single().kind)
        assertEquals(HealthStatus.OK, evaluate(healthy()).item(HealthIds.DND).status)
        assertTrue(fullScreen.badge)
        assertTrue(dnd.badge)
    }

    @Test
    fun batteryWarningUsesTheSettingsListAndNotTheOneTapRequest() {
        val optimised = evaluate(healthy().copy(ignoringBatteryOptimizations = false))
        val row = optimised.item(HealthIds.BATTERY)
        assertEquals(HealthStatus.WARN, row.status)
        assertEquals(
            "Battery optimisation is still on for Do. On some phones that can stop nags after a few hours.",
            row.explanation,
        )
        assertEquals(HealthActionKind.BATTERY_LIST, row.fixes.single().kind)
        assertEquals(HealthIntents.ACTION_BATTERY_LIST, HealthIntents.primaryAction(row.fixes.single().kind))
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            HealthIntents.primaryAction(HealthActionKind.BATTERY_LIST),
        )

        val exempt = evaluate(healthy().copy(ignoringBatteryOptimizations = true))
        assertEquals(HealthStatus.OK, exempt.item(HealthIds.BATTERY).status)
        assertTrue(exempt.item(HealthIds.BATTERY).fixes.isEmpty())
    }

    @Test
    fun batteryFixIntentActionIsTheOptimisationList() {
        assertEquals(
            "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            HealthIntents.primaryAction(HealthActionKind.BATTERY_LIST),
        )
        for (kind in HealthActionKind.entries) {
            org.junit.Assert.assertNotEquals(
                ONE_TAP_BATTERY_REQUEST,
                HealthIntents.primaryAction(kind),
            )
        }
    }

    @Test
    fun samsungBatteryChainTriesDeviceCareThenTheOptimisationList() {
        val chain = HealthIntents.samsungBatteryTargets("com.evansappstudio.doapp")
        assertEquals(
            listOf(
                "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
                "com.samsung.android.sm/com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
            chain.take(2).map { "${it.componentPackage}/${it.componentClass}" },
        )
        assertEquals(
            listOf(
                "com.samsung.android.sm.ACTION_BATTERY",
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_BATTERY_SAVER_SETTINGS,
                Settings.ACTION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            chain.drop(2).map { it.action },
        )
        assertEquals("package:com.evansappstudio.doapp", chain.last().data)
        assertTrue(chain.none { it.action == ONE_TAP_BATTERY_REQUEST })
    }

    @Test
    fun backgroundRestrictionIsBlockedFromApi28AndSkippedBelow() {
        val blocked = evaluate(healthy().copy(sdkInt = 28, backgroundRestricted = true))
        val row = blocked.item(HealthIds.BACKGROUND)
        assertEquals(HealthStatus.BLOCKED, row.status)
        assertEquals(
            "Background activity is restricted, so reminders may not fire once Do is in the background.",
            row.explanation,
        )
        assertEquals(HealthActionKind.APP_DETAILS, row.fixes.single().kind)
        assertTrue(blocked.dump.contains("bgRestrict=BLOCKED"))

        val allowed = evaluate(healthy().copy(sdkInt = 36, backgroundRestricted = false))
        assertEquals(HealthStatus.OK, allowed.item(HealthIds.BACKGROUND).status)
        assertTrue(allowed.dump.contains("bgRestrict=OK"))

        val unknown = evaluate(healthy().copy(sdkInt = 29, backgroundRestricted = null))
        assertEquals(HealthStatus.OK, unknown.item(HealthIds.BACKGROUND).status)
        assertTrue(unknown.dump.contains("bgRestrict=NA"))

        for (sdk in listOf(26, 27)) {
            val report = evaluate(healthy().copy(sdkInt = sdk, backgroundRestricted = true))
            assertNull(report.items.find { it.id == HealthIds.BACKGROUND })
            assertTrue(report.dump.contains("bgRestrict=NA"))
        }
    }

    @Test
    fun samsungRowIsCheckOnlyAndDoesNotLightTheBadgeByItself() {
        val galaxy = evaluate(healthy().copy(samsung = true))
        val row = galaxy.item(HealthIds.SAMSUNG)
        assertEquals(HealthStatus.CHECK, row.status)
        assertTrue(row.explanation.contains("Sleeping apps"))
        assertTrue(row.explanation.contains("Deep sleeping apps"))
        assertTrue(row.explanation.contains("Never sleeping apps"))
        assertTrue(row.explanation.contains("Put unused apps to sleep"))
        assertTrue(row.explanation.contains("Settings, Battery and device care, Battery, Background usage limits"))
        assertEquals(HealthActionKind.SAMSUNG_BATTERY, row.fixes[0].kind)
        assertEquals(HealthActionKind.LEARN_MORE, row.fixes[1].kind)
        assertFalse(galaxy.badge)
        assertTrue(galaxy.dump.contains("samsung=CHECK"))

        val other = evaluate(healthy().copy(samsung = false))
        assertNull(other.items.find { it.id == HealthIds.SAMSUNG })
        assertTrue(other.dump.contains("samsung=NA"))
        assertFalse(other.badge)
    }

    @Test
    fun standbyWarnsOnlyForRestrictedAndNever() {
        val okBuckets = listOf(5 to "Exempted", 10 to "Active", 20 to "WorkingSet", 30 to "Frequent", 40 to "Rare", 7 to "Unknown")
        for ((bucket, name) in okBuckets) {
            val report = evaluate(healthy().copy(standbyBucket = bucket))
            assertEquals(name, HealthEvaluator.standbyName(bucket))
            assertEquals(HealthStatus.OK, report.item(HealthIds.STANDBY).status)
            assertTrue(report.dump.contains("standby=OK:$name:$bucket"))
            assertFalse(report.badge)
        }
        for ((bucket, name) in listOf(45 to "Restricted", 50 to "Never")) {
            val report = evaluate(healthy().copy(standbyBucket = bucket))
            val row = report.item(HealthIds.STANDBY)
            assertEquals(HealthStatus.WARN, row.status)
            assertEquals(
                "Android has classed Do as rarely used, which can limit background work. Bucket: $name.",
                row.explanation,
            )
            assertEquals(HealthActionKind.APP_DETAILS, row.fixes.single().kind)
            assertTrue(report.dump.contains("standby=WARN:$name:$bucket"))
            assertTrue(report.badge)
        }
        val unavailable = evaluate(healthy().copy(sdkInt = 26, standbyBucket = null))
        assertEquals(HealthStatus.OK, unavailable.item(HealthIds.STANDBY).status)
        assertTrue(unavailable.dump.contains("standby=NA"))
    }

    @Test
    fun bootReceiverDisabledIsBlocked() {
        val report = evaluate(
            healthy().copy(
                boot = BootFacts(
                    receiverDisabled = true,
                    rebootedSinceInstall = true,
                    userUnlocked = true,
                    mirrorNeededPreUnlock = true,
                ),
            ),
        )
        val row = report.item(HealthIds.BOOT)
        assertEquals(HealthStatus.BLOCKED, row.status)
        assertEquals(
            "The boot receiver is disabled, so reminders will not be restored after a restart.",
            row.explanation,
        )
        assertEquals(HealthActionKind.REENABLE_BOOT, row.fixes.single().kind)
        assertTrue(report.dump.contains("boot=BLOCKED"))
    }

    @Test
    fun freshInstallWithNoRebootIsOkEvenWithoutReceipts() {
        val report = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = false,
                    userUnlocked = true,
                    unlockReceiptThisBoot = false,
                    lockedReceiptThisBoot = false,
                ),
            ),
        )
        val row = report.item(HealthIds.BOOT)
        assertEquals(HealthStatus.OK, row.status)
        assertEquals("No restart has been seen since Do was installed.", row.explanation)
        assertFalse(report.badge)
        assertTrue(report.dump.contains("unlocked=true"))
    }

    @Test
    fun aLockedPhoneIsNotItselfAFault() {
        val report = evaluate(
            healthy().copy(
                boot = BootFacts(userUnlocked = false, rebootedSinceInstall = false),
            ),
        )
        assertEquals(HealthStatus.OK, report.item(HealthIds.BOOT).status)
        assertTrue(report.dump.contains("unlocked=false"))
        assertFalse(report.badge)
    }

    @Test
    fun missedUnlockReceiptAfterRebootIsAWarning() {
        val report = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = true,
                    userUnlocked = true,
                    unlockReceiptThisBoot = false,
                    lockedReceiptThisBoot = true,
                ),
            ),
        )
        val row = report.item(HealthIds.BOOT)
        assertEquals(HealthStatus.WARN, row.status)
        assertEquals(
            "Do did not hear the unlock-after-restart event, so some reminders may not have been restored.",
            row.explanation,
        )
        assertEquals(HealthActionKind.RESTORE_ALARMS, row.fixes[0].kind)
        assertEquals(HealthActionKind.APP_DETAILS, row.fixes[1].kind)
    }

    @Test
    fun missedUnlockReceiptIsNotWarnedWhileStillLocked() {
        val report = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = true,
                    userUnlocked = false,
                    unlockReceiptThisBoot = false,
                    lockedReceiptThisBoot = true,
                    mirrorNeededPreUnlock = false,
                ),
            ),
        )
        assertEquals(HealthStatus.OK, report.item(HealthIds.BOOT).status)
    }

    @Test
    fun missedLockedBootWarnsOnlyWhenTheMirrorNeededAPreUnlockSchedule() {
        val needed = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = true,
                    userUnlocked = false,
                    unlockReceiptThisBoot = false,
                    lockedReceiptThisBoot = false,
                    mirrorNeededPreUnlock = true,
                ),
            ),
        )
        assertEquals(HealthStatus.WARN, needed.item(HealthIds.BOOT).status)
        assertTrue(
            needed.item(HealthIds.BOOT).explanation.contains(
                "Do did not hear the restart event before unlock, so a reminder due before you unlock may have been missed.",
            ),
        )

        val empty = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = true,
                    userUnlocked = true,
                    unlockReceiptThisBoot = true,
                    lockedReceiptThisBoot = false,
                    mirrorNeededPreUnlock = false,
                ),
            ),
        )
        assertEquals(HealthStatus.OK, empty.item(HealthIds.BOOT).status)
    }

    @Test
    fun bothMissedReceiptsAreNamedTogether() {
        val report = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = true,
                    userUnlocked = true,
                    unlockReceiptThisBoot = false,
                    lockedReceiptThisBoot = false,
                    mirrorNeededPreUnlock = true,
                ),
            ),
        )
        val explanation = report.item(HealthIds.BOOT).explanation
        assertTrue(explanation.contains("unlock-after-restart"))
        assertTrue(explanation.contains("restart event before unlock"))
    }

    @Test
    fun missingMirrorEntriesWarnAfterUnlock() {
        val report = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = false,
                    userUnlocked = true,
                    activeReminderCount = 2,
                    mirrorMissingActive = true,
                ),
            ),
        )
        val row = report.item(HealthIds.BOOT)
        assertEquals(HealthStatus.WARN, row.status)
        assertEquals(
            "Some reminders are missing from the restart copy, so they may not fire before the first unlock.",
            row.explanation,
        )

        val locked = evaluate(
            healthy().copy(
                boot = BootFacts(
                    userUnlocked = false,
                    activeReminderCount = 2,
                    mirrorMissingActive = true,
                ),
            ),
        )
        assertEquals(HealthStatus.OK, locked.item(HealthIds.BOOT).status)

        val covered = evaluate(
            healthy().copy(
                boot = BootFacts(
                    rebootedSinceInstall = true,
                    userUnlocked = true,
                    unlockReceiptThisBoot = true,
                    lockedReceiptThisBoot = true,
                    activeReminderCount = 2,
                    mirrorMissingActive = false,
                ),
            ),
        )
        assertEquals(HealthStatus.OK, covered.item(HealthIds.BOOT).status)
        assertEquals("Reminders can be restored after a restart.", covered.item(HealthIds.BOOT).explanation)
    }

    @Test
    fun lateBoundaryIsStrictlyGreaterThanSixtySeconds() {
        assertFalse(HealthEvaluator.isLate(scheduled = 1_000L, actual = 1_000L))
        assertFalse(HealthEvaluator.isLate(scheduled = 1_000L, actual = 1_000L + 60_000L))
        assertTrue(HealthEvaluator.isLate(scheduled = 1_000L, actual = 1_000L + 60_001L))
        assertFalse(HealthEvaluator.isLate(scheduled = 5_000L, actual = 4_999L))
        assertFalse(HealthEvaluator.isLate(scheduled = -1L, actual = 99_999L))

        val onTime = evaluate(healthy().copy(lateLagMs = 60_000L))
        assertNull(onTime.items.find { it.id == HealthIds.LATE })
        assertTrue(onTime.dump.contains("lastLate=none"))
        assertTrue(onTime.dump.contains("late=OK"))
        assertFalse(onTime.badge)

        val late = evaluate(healthy().copy(lateLagMs = 60_001L))
        val row = late.item(HealthIds.LATE)
        assertEquals(HealthStatus.WARN, row.status)
        assertEquals("A reminder fired 1 minute late.", row.explanation)
        assertEquals(HealthActionKind.CLEAR_LATE, row.fixes.single().kind)
        assertTrue(late.dump.contains("lastLate=60001"))
        assertTrue(late.dump.contains("late=WARN"))
        assertTrue(late.badge)

        val threeMinutes = evaluate(healthy().copy(lateLagMs = 180_000L))
        assertEquals("A reminder fired 3 minutes late.", threeMinutes.item(HealthIds.LATE).explanation)
    }

    @Test
    fun aLateFireOnSamsungPointsAtTheSleepingAppsSteps() {
        val report = evaluate(healthy().copy(samsung = true, lateLagMs = 120_000L))
        val explanation = report.item(HealthIds.LATE).explanation
        assertTrue(explanation.startsWith("A reminder fired 2 minutes late."))
        assertTrue(explanation.contains("Sleeping apps"))
        assertTrue(explanation.contains("Deep sleeping apps"))
        assertTrue(explanation.contains("Never sleeping apps"))
        assertTrue(explanation.contains("Put unused apps to sleep"))
        assertEquals(HealthStatus.CHECK, report.item(HealthIds.SAMSUNG).status)
        assertTrue(report.badge)
    }

    @Test
    fun clearingTheLateRecordRemovesTheWarning() {
        val report = evaluate(healthy().copy(lateLagMs = null))
        assertNull(report.items.find { it.id == HealthIds.LATE })
        assertFalse(report.badge)
        assertTrue(report.dump.contains("lastLate=none"))
    }

    @Test
    fun testOutcomeTreatsEarlyAndOverToleranceAsWarning() {
        assertEquals(HealthEvaluator.TEST_OK, HealthEvaluator.testStatus(1_000L, 1_000L))
        assertEquals(HealthEvaluator.TEST_OK, HealthEvaluator.testStatus(1_000L, 1_000L + 60_000L))
        assertEquals(HealthEvaluator.TEST_WARN, HealthEvaluator.testStatus(1_000L, 1_000L + 60_001L))
        assertEquals(HealthEvaluator.TEST_WARN, HealthEvaluator.testStatus(5_000L, 4_999L))
    }

    @Test
    fun testMessagesCoverPendingOnTimeLateAndEarly() {
        val pending = evaluate(healthy().copy(testPending = true))
        assertTrue(pending.testMessage.contains("about one minute"))
        assertEquals(HealthEvaluator.TEST_NONE, pending.testResult)

        val onTime = evaluate(healthy().copy(testResult = HealthEvaluator.TEST_OK))
        assertEquals("The last test alarm fired on time.", onTime.testMessage)
        assertTrue(onTime.dump.contains("test=OK"))

        val late = evaluate(healthy().copy(testResult = HealthEvaluator.TEST_WARN, testEarly = false))
        assertEquals("The last test alarm fired late.", late.testMessage)
        assertTrue(late.dump.contains("test=WARN"))

        val early = evaluate(healthy().copy(testResult = HealthEvaluator.TEST_WARN, testEarly = true))
        assertEquals("The last test alarm fired early.", early.testMessage)
    }

    @Test
    fun aHealthyDumpContainsEveryKeyAndDoesNotLightTheBadge() {
        val report = evaluate(healthy())
        assertFalse(report.badge)
        val dump = report.dump
        for (key in listOf(
            "notif=OK",
            "channels=OK",
            "exact=OK:USE",
            "fsi=OK",
            "dnd=OK",
            "battery=OK",
            "bgRestrict=OK",
            "samsung=NA",
            "standby=OK:Active:10",
            "boot=OK",
            "unlocked=true",
            "lastLate=none",
            "test=none",
            "late=OK",
        )) {
            assertTrue("missing $key in $dump", dump.contains(key))
        }
        assertEquals(
            listOf(
                HealthIds.NOTIFICATIONS,
                HealthIds.CHANNELS,
                HealthIds.EXACT,
                HealthIds.FULL_SCREEN,
                HealthIds.DND,
                HealthIds.BATTERY,
                HealthIds.BACKGROUND,
                HealthIds.STANDBY,
                HealthIds.BOOT,
            ),
            report.items.map { it.id },
        )
    }

    @Test
    fun checkAloneDoesNotBadgeAndBlockedOrWarningDoes() {
        assertFalse(evaluate(healthy().copy(samsung = true)).badge)
        assertTrue(evaluate(healthy().copy(notificationsGranted = false)).badge)
        assertTrue(evaluate(healthy().copy(ignoringBatteryOptimizations = false)).badge)
        assertTrue(evaluate(healthy().copy(lateLagMs = 60_001L)).badge)
        assertFalse(evaluate(healthy()).badge)
    }

    @Test
    fun storedReportRoundTripsTheBadgeAndFixes() {
        val report = evaluate(healthy().copy(samsung = true, ignoringBatteryOptimizations = false, lateLagMs = 90_000L))
        val decoded = HealthStore.decode(HealthStore.encode(report))
        assertEquals(report.badge, decoded?.badge)
        assertEquals(report.dump, decoded?.dump)
        assertEquals(report.items.map { it.id }, decoded?.items?.map { it.id })
        assertEquals(report.items.map { it.status }, decoded?.items?.map { it.status })
        assertEquals(
            report.items.map { item -> item.fixes.map { it.kind } },
            decoded?.items?.map { item -> item.fixes.map { it.kind } },
        )
        val channel = report.item(HealthIds.CHANNELS)
        assertEquals(channel.fixes, decoded?.item(HealthIds.CHANNELS)?.fixes)
    }

    private fun evaluate(facts: HealthFacts) = HealthEvaluator.evaluate(facts)

    private fun HealthReport.item(id: String) = items.first { it.id == id }

    private fun healthy(): HealthFacts = HealthFacts(channels = healthyChannels())

    private fun healthyChannels(): List<ChannelFact> = listOf(
        healthyChannel("reminders", "Reminders"),
        healthyChannel("reminders_critical", "Critical reminders"),
        healthyChannel("reminders_prominent", "Prominent reminders"),
    )

    private fun healthyChannel(id: String, name: String) = ChannelFact(
        id = id,
        name = name,
        present = true,
        importance = 4,
        blocked = false,
        shouldExist = true,
    )

    private fun channels(
        unhealthyId: String,
        importance: Int = 4,
        blocked: Boolean = false,
        present: Boolean = true,
        shouldExist: Boolean = true,
    ): List<ChannelFact> {
        return healthyChannels().map { channel ->
            if (channel.id != unhealthyId) channel
            else channel.copy(
                importance = importance,
                blocked = blocked,
                present = present,
                shouldExist = shouldExist,
            )
        }
    }
}
