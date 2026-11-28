package com.evansappstudio.doapp.backup

import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.PostponeMode
import com.evansappstudio.doapp.alarm.QuickTimes
import com.evansappstudio.doapp.alarm.QuickTimesSettings
import com.evansappstudio.doapp.alarm.SnoozeDue
import com.evansappstudio.doapp.data.RepeatPromptMode
import com.evansappstudio.doapp.data.WeekStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 11 sections B and C: region and onboarding profile are optional backup settings keys. */
class SysdBackupKeysTest {
    private val base = PortableSettings(
        defaultNagMinutes = 5,
        snooze = SnoozeDue.DEFAULTS,
        quickTimes = QuickTimesSettings(QuickTimes.FACTORY, PostponeMode.AUTO),
        weekStart = WeekStart.MONDAY,
        parseDates = true,
        defaultAlertLevel = AlertLevel.STANDARD,
        repeatPromptMode = RepeatPromptMode.PROMPT,
    )

    private fun round(settings: PortableSettings): PortableSettings {
        val document = BackupDocument(
            schemaVersion = BackupNames.SCHEMA_VERSION,
            appId = BackupNames.APP_ID,
            appVersionName = "1.0",
            exportedAt = 50L,
            settings = settings,
            reminders = emptyList(),
        )
        return (BackupCodec.decode(BackupCodec.encode(document)) as BackupDecode.Ok).document.settings
    }

    @Test
    fun regionAndAreasRoundTrip() {
        val full = base.copy(sysdRegion = "AU-WA", sysdRegionSource = "USER", sysdAreas = listOf("home", "car"))
        assertEquals(full, round(full))
        val outside = base.copy(sysdRegion = "none", sysdRegionSource = "USER", sysdAreas = emptyList())
        assertEquals(outside, round(outside))
    }

    @Test
    fun filesWithoutTheKeysStillImport() {
        val plain = round(base)
        assertNull(plain.sysdRegion)
        assertNull(plain.sysdRegionSource)
        assertNull(plain.sysdAreas)
        val document = BackupDocument(BackupNames.SCHEMA_VERSION, BackupNames.APP_ID, "1.0", 50L, base, emptyList())
        assertFalse(BackupCodec.encode(document).contains("sysd"))
    }

    @Test
    fun badValuesAreIgnored() {
        val bad = base.copy(sysdRegion = "AU-XX", sysdRegionSource = "SOMETIMES", sysdAreas = listOf("home", "spaceship", "home"))
        val decoded = round(bad)
        assertNull(decoded.sysdRegion)
        assertNull(decoded.sysdRegionSource)
        assertEquals(listOf("home"), decoded.sysdAreas)
    }
}
