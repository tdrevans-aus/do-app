package com.evansappstudio.doapp.backup

import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.alarm.SnoozeKind
import com.evansappstudio.doapp.alarm.SnoozeOption
import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.WeekStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class BackupLogicTest {
    @Test
    fun schema1RoundTripsRemindersAndPortableSettings() {
        val settings = sampleSettings().copy(
            weekStart = WeekStart.SUNDAY,
            snooze = listOf(
                SnoozeOption(SnoozeKind.AT_TIME, 9 * 60),
                SnoozeDue.DEFAULTS[1],
                SnoozeDue.DEFAULTS[2],
            ),
        )
        val repeating = sampleReminder(uuid = "repeat-1").copy(
            title = "Say \"bins\"",
            repeatInterval = 1,
            repeatUnit = "WEEK",
            repeatFrom = "DUE_DATE",
            repeatWeekDays = "MON,TUE,WED,THU,FRI",
            patternDueAt = 1_700_000_000_000L,
            alertLevel = AlertLevel.CRITICAL.name,
        )
        val document = BackupDocument(
            schemaVersion = 1,
            appId = BackupNames.APP_ID,
            appVersionName = "1.0",
            exportedAt = 1_700_000_111_000L,
            settings = settings,
            reminders = listOf(repeating, sampleReminder(uuid = "plain-2", title = "Milk")),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(document))
        assertEquals(document, (decoded as BackupDecode.Ok).document)
        val encoded = BackupCodec.encode(document)
        assertFalse(encoded.contains("purchaseToken"))
        assertFalse(encoded.contains("do_entitlement"))
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
    }

    @Test
    fun mergeMatchesUuidAndKeepsLocalOnlyRowsWhileReplaceSwapsTheList() {
        val localOnly = sampleReminder(uuid = "local", title = "Stay")
        val shared = sampleReminder(uuid = "shared", title = "Old title")
        val imported = shared.copy(title = "Exported title", alertLevel = AlertLevel.PROMINENT.name)
        val merged = mergeReminders(listOf(localOnly, shared), listOf(imported), onlyUuids = null)
        assertEquals(listOf("local", "shared"), merged.map { it.uuid })
        assertEquals("Stay", merged[0].title)
        assertEquals("Exported title", merged[1].title)
        assertEquals(AlertLevel.PROMINENT.name, merged[1].alertLevel)

        val extra = sampleReminder(uuid = "new", title = "New")
        val withNew = mergeReminders(merged, listOf(extra), onlyUuids = setOf("new"))
        assertEquals(listOf("local", "shared", "new"), withNew.map { it.uuid })
        assertEquals("Exported title", withNew[1].title)

        val replaced = BackupCodec.restoreReminders(
            current = listOf(localOnly, shared),
            text = BackupCodec.encode(document(listOf(imported))),
            mode = BackupMode.REPLACE,
            onlyUuids = null,
        )
        assertEquals(listOf(imported), replaced)
    }

    @Test
    fun aNewerSchemaOrABadFileLeavesRemindersUnchanged() {
        val current = listOf(sampleReminder(uuid = "keep", title = "Keep"))
        val newer = BackupCodec.encode(document(current)).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertTrue(BackupCodec.decode(newer) is BackupDecode.Rejected)
        assertEquals(
            current,
            BackupCodec.restoreReminders(current, newer, BackupMode.REPLACE, null),
        )
        val foreign = BackupCodec.encode(document(current)).replace(BackupNames.APP_ID, "com.example.other")
        val foreignDecoded = BackupCodec.decode(foreign) as BackupDecode.Rejected
        assertEquals(BackupDecode.APP, foreignDecoded.reason)
        assertEquals(current, BackupCodec.restoreReminders(current, "{", BackupMode.MERGE, null))
        assertEquals(current, BackupCodec.restoreReminders(current, "", BackupMode.REPLACE, null))
    }

    @Test
    fun importAboveTheCapKeepsTheExtrasAndStillBlocksAdds() {
        val incoming = List(6) { index -> sampleReminder(uuid = "n$index", title = "N$index") }
        val merged = mergeReminders(emptyList(), incoming, null)
        assertEquals(6, merged.count { !it.isDone })
        assertTrue(FreeTier.blocksAdd(merged.count { !it.isDone }, unlocked = false))
        assertFalse(FreeTier.blocksAdd(merged.count { !it.isDone }, unlocked = true))
    }

    @Test
    fun suggestedNamesUseLocalDigitsAndAutoBackupWaitsADay() {
        val zone = ZoneId.of("Australia/Sydney")
        val name = BackupNames.suggested(1_763_942_400_000L, zone)
        assertTrue(name.startsWith("do-backup-"))
        assertTrue(name.endsWith(".json"))
        assertFalse(name.contains(" "))
        assertFalse(BackupSchedule.due(now = 1_000, lastSuccessAt = 0, lastAutoAt = 0, folderSet = false))
        assertTrue(BackupSchedule.due(now = 1_000, lastSuccessAt = 0, lastAutoAt = 0, folderSet = true))
        val day = BackupNames.AUTO_GAP_MS
        assertFalse(BackupSchedule.due(now = day, lastSuccessAt = 1, lastAutoAt = 1, folderSet = true))
        assertTrue(BackupSchedule.due(now = day + 1, lastSuccessAt = 1, lastAutoAt = 1, folderSet = true))
    }

    @Test
    fun autoBackupRulesIncludeTheDatabaseAndSettingsAndDoNotEnableCache() {
        val rules = readProjectFile("src/main/res/xml/backup_rules.xml")
        val extraction = readProjectFile("src/main/res/xml/data_extraction_rules.xml")
        for (text in listOf(rules, extraction)) {
            assertTrue(text.contains("do.db"))
            assertTrue(text.contains("do_settings"))
            assertFalse(text.contains("domain=\"cache\""))
            assertFalse(text.contains("domain=\"code_cache\""))
            assertFalse(text.contains("path=\"cache\""))
        }
        assertTrue(extraction.contains("<cloud-backup>"))
        assertTrue(extraction.contains("<device-transfer>"))
        assertTrue(extraction.contains("do_entitlement"))
        assertFalse(extraction.contains("reminder-mirror"))
        assertTrue(rules.contains("domain=\"device_sharedpref\" path=\"do_quiet.xml\""))
        assertTrue(extraction.contains("domain=\"device_sharedpref\" path=\"do_quiet.xml\""))
        val cloud = extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(cloud.contains("domain=\"device_sharedpref\" path=\"do_quiet.xml\""))
        assertTrue(transfer.contains("domain=\"device_sharedpref\" path=\"do_quiet.xml\""))
        assertTrue(cloud.contains("domain=\"sharedpref\" path=\"do_settings.xml\""))
        assertTrue(transfer.contains("domain=\"sharedpref\" path=\"do_settings.xml\""))
    }

    @Test
    fun quietKeysAreOptionalOnSchema1() {
        val plain = BackupCodec.decode(BackupCodec.encode(document(emptyList())))
        val plainSettings = (plain as BackupDecode.Ok).document.settings
        assertEquals(null, plainSettings.quietEnabled)
        assertEquals(null, plainSettings.quietStartMinute)
        assertEquals(null, plainSettings.quietEndMinute)
        assertEquals(null, plainSettings.quietFollowDnd)
        assertEquals(null, plainSettings.quietCriticalPauses)

        val full = sampleSettings().copy(
            quietEnabled = true,
            quietStartMinute = 23 * 60,
            quietEndMinute = 6 * 60,
            quietFollowDnd = true,
            quietCriticalPauses = true,
        )
        val round = BackupCodec.decode(BackupCodec.encode(document(emptyList()).copy(settings = full)))
        assertEquals(full, (round as BackupDecode.Ok).document.settings)

        val onlyEnabled = sampleSettings().copy(quietEnabled = true)
        val partial = (BackupCodec.decode(
            BackupCodec.encode(document(emptyList()).copy(settings = onlyEnabled)),
        ) as BackupDecode.Ok).document.settings
        assertEquals(true, partial.quietEnabled)
        assertEquals(null, partial.quietStartMinute)
        assertEquals(null, partial.quietEndMinute)
        assertEquals(null, partial.quietFollowDnd)
        assertEquals(null, partial.quietCriticalPauses)

        val bad = full.copy(quietStartMinute = 2000, quietEndMinute = 2000)
        val ignored = (BackupCodec.decode(
            BackupCodec.encode(document(emptyList()).copy(settings = bad)),
        ) as BackupDecode.Ok).document.settings
        assertEquals(null, ignored.quietStartMinute)
        assertEquals(null, ignored.quietEndMinute)
        assertEquals(true, ignored.quietEnabled)
        assertEquals(true, ignored.quietFollowDnd)

        val equal = full.copy(quietStartMinute = 100, quietEndMinute = 100)
        val kept = (BackupCodec.decode(
            BackupCodec.encode(document(emptyList()).copy(settings = equal)),
        ) as BackupDecode.Ok).document.settings
        assertEquals(null, kept.quietStartMinute)
        assertEquals(null, kept.quietEndMinute)
        assertEquals(true, kept.quietFollowDnd)
    }

    private fun document(reminders: List<PortableReminder>) = BackupDocument(
        schemaVersion = 1,
        appId = BackupNames.APP_ID,
        appVersionName = "1.0",
        exportedAt = 50L,
        settings = sampleSettings(),
        reminders = reminders,
    )

    private fun sampleSettings() = PortableSettings(
        defaultNagMinutes = 5,
        snooze = SnoozeDue.DEFAULTS,
        quickTimes = QuickTimesSettings(QuickTimes.FACTORY, PostponeMode.AUTO),
        weekStart = WeekStart.MONDAY,
        parseDates = true,
        defaultAlertLevel = AlertLevel.STANDARD,
        repeatPromptMode = RepeatPromptMode.PROMPT,
    )

    private fun sampleReminder(uuid: String, title: String = "Bins") = PortableReminder(
        uuid = uuid,
        title = title,
        dueAt = 1_700_000_000_000L,
        nagMinutes = 5,
        isDone = false,
        doneAt = null,
        createdAt = 1_699_000_000_000L,
        repeatInterval = null,
        repeatUnit = null,
        repeatFrom = null,
        repeatWeekDays = null,
        repeatMonthOn = null,
        patternDueAt = null,
        alertLevel = AlertLevel.STANDARD.name,
    )

    private fun readProjectFile(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile } ?: error("missing $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
