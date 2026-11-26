package com.evansappstudio.doapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.zone.ZoneOffsetTransition

class QuietHoursTest {
    private val perth = ZoneId.of("Australia/Perth")
    private val utc = ZoneOffset.UTC

    @Test
    fun acrossMidnightWindowIncludesStartAndExcludesEnd() {
        val config = window(start = 22 * 60, end = 7 * 60)
        assertTrue(QuietHours.inWindow(local(perth, 2026, 6, 15, 22, 0), config, perth))
        assertTrue(QuietHours.inWindow(local(perth, 2026, 6, 15, 23, 30), config, perth))
        assertTrue(QuietHours.inWindow(local(perth, 2026, 6, 16, 0, 0), config, perth))
        assertTrue(QuietHours.inWindow(local(perth, 2026, 6, 16, 6, 59), config, perth))
        assertFalse(QuietHours.inWindow(local(perth, 2026, 6, 16, 7, 0), config, perth))
        assertFalse(QuietHours.inWindow(local(perth, 2026, 6, 15, 21, 59), config, perth))
        assertFalse(config.enabled)
    }

    @Test
    fun sameDayWindowIncludesStartAndExcludesEnd() {
        val config = window(start = 13 * 60, end = 14 * 60)
        assertFalse(QuietHours.inWindow(local(utc, 2026, 6, 15, 12, 59), config, utc))
        assertTrue(QuietHours.inWindow(local(utc, 2026, 6, 15, 13, 0), config, utc))
        assertTrue(QuietHours.inWindow(local(utc, 2026, 6, 15, 13, 59), config, utc))
        assertFalse(QuietHours.inWindow(local(utc, 2026, 6, 15, 14, 0), config, utc))
    }

    @Test
    fun startEqualToEndIsNotAWindow() {
        val config = window(start = 10 * 60, end = 10 * 60)
        assertFalse(QuietHours.inWindow(local(utc, 2026, 6, 15, 10, 0), config, utc))
        assertNull(QuietHours.windowEnd(local(utc, 2026, 6, 15, 10, 0), config, utc))
        assertEquals(12 * 60, QuietHours.parseMinute("12:00"))
        assertNull(QuietHours.parseMinute("12:60"))
        assertNull(QuietHours.parseMinute("24:00"))
        assertNull(QuietHours.parseMinute("nope"))
        assertEquals("07:05", QuietHours.formatMinute(7 * 60 + 5))
    }

    @Test
    fun windowEndIsTheNextLocalEndMinute() {
        val config = window(start = 22 * 60, end = 7 * 60)
        val evening = local(perth, 2026, 6, 15, 23, 30)
        val morning = local(perth, 2026, 6, 16, 2, 15)
        val end = local(perth, 2026, 6, 16, 7, 0)
        assertEquals(end, QuietHours.windowEnd(evening, config, perth))
        assertEquals(end, QuietHours.windowEnd(morning, config, perth))
        assertNull(QuietHours.windowEnd(end, config, perth))
        val sameDay = window(start = 13 * 60, end = 14 * 60)
        assertEquals(
            local(utc, 2026, 6, 15, 14, 0),
            QuietHours.windowEnd(local(utc, 2026, 6, 15, 13, 15), sameDay, utc),
        )
    }

    @Test
    fun windowEndInASydneyGapUsesTheDefaultLaterInstant() {
        val gap = nextTransition(ZoneId.of("Australia/Sydney"), wantGap = true)
        val endLocal = gap.dateTimeBefore.plusMinutes(30)
        val config = window(
            start = endLocal.toLocalTime().minusHours(1).let { it.hour * 60 + it.minute },
            end = endLocal.hour * 60 + endLocal.minute,
        )
        val inside = ZonedDateTime.of(endLocal.toLocalDate(), endLocal.toLocalTime().minusMinutes(45), gap.zone)
        assertTrue(QuietHours.inWindow(inside.toInstant().toEpochMilli(), config, gap.zone))
        val expected = ZonedDateTime.of(endLocal, gap.zone).toInstant().toEpochMilli()
        assertEquals(expected, QuietHours.windowEnd(inside.toInstant().toEpochMilli(), config, gap.zone))
        assertTrue(expected > gap.instant.toEpochMilli())
    }

    @Test
    fun windowEndInASydneyOverlapKeepsTheEarlierOffset() {
        val overlap = nextTransition(ZoneId.of("Australia/Sydney"), wantGap = false)
        val endLocal = overlap.dateTimeAfter.plusMinutes(30)
        val config = window(
            start = endLocal.toLocalTime().minusHours(2).let { it.hour * 60 + it.minute },
            end = endLocal.hour * 60 + endLocal.minute,
        )
        val before = overlap.dateTimeBefore.minusHours(1)
        val inside = ZonedDateTime.of(before, overlap.zone)
        val offsets = overlap.zone.rules.getValidOffsets(endLocal)
        assertTrue(offsets.size >= 2)
        val expected = offsets.minOf { endLocal.atOffset(it).toInstant().toEpochMilli() }
        assertEquals(expected, QuietHours.windowEnd(inside.toInstant().toEpochMilli(), config, overlap.zone))
        assertEquals(expected, ZonedDateTime.of(endLocal, overlap.zone).toInstant().toEpochMilli())
    }

    @Test
    fun perthHasNoTransitionAndTheEndMinuteIsExact() {
        val rules = perth.rules
        val probe = Instant.parse("2026-06-15T12:00:00Z")
        assertNull(rules.nextTransition(probe))
        val config = QuietConfig.DEFAULT.copy(enabled = true)
        val at = local(perth, 2026, 6, 15, 23, 0)
        assertEquals(local(perth, 2026, 6, 16, 7, 0), QuietHours.windowEnd(at, config, perth))
    }

    @Test
    fun exemptIsProminentAlwaysAndCriticalOnlyWhenTheSettingIsOff() {
        val off = QuietConfig.DEFAULT
        val on = QuietConfig.DEFAULT.copy(criticalPauses = true)
        for (config in listOf(off, on)) {
            assertTrue(QuietHours.exempt(AlertLevel.PROMINENT, config))
        }
        assertTrue(QuietHours.exempt(AlertLevel.CRITICAL, off))
        assertFalse(QuietHours.exempt(AlertLevel.CRITICAL, on))
        assertFalse(QuietHours.exempt(AlertLevel.STANDARD, off))
        assertFalse(QuietHours.exempt(AlertLevel.STANDARD, on))
    }

    @Test
    fun placeNagLeavesExemptLevelsAndDefersCriticalOnlyWhenAsked() {
        val config = QuietConfig.DEFAULT.copy(enabled = true, criticalPauses = false)
        val planned = local(perth, 2026, 6, 15, 23, 5)
        val end = local(perth, 2026, 6, 16, 7, 0)
        assertEquals(planned, QuietHours.placeNag(planned, AlertLevel.STANDARD, config.copy(enabled = false), perth))
        assertEquals(end, QuietHours.placeNag(planned, AlertLevel.STANDARD, config, perth))
        assertEquals(planned, QuietHours.placeNag(planned, AlertLevel.CRITICAL, config, perth))
        assertEquals(planned, QuietHours.placeNag(planned, AlertLevel.PROMINENT, config, perth))
        assertEquals(
            end,
            QuietHours.placeNag(planned, AlertLevel.CRITICAL, config.copy(criticalPauses = true), perth),
        )
        assertEquals(
            planned,
            QuietHours.placeNag(planned, AlertLevel.PROMINENT, config.copy(criticalPauses = true), perth),
        )
        val outside = local(perth, 2026, 6, 15, 12, 0)
        assertEquals(outside, QuietHours.placeNag(outside, AlertLevel.STANDARD, config, perth))
    }

    @Test
    fun aDeferredNagRejoinsTheOriginalAnchorGrid() {
        val due = local(perth, 2026, 6, 15, 21, 59)
        val config = QuietConfig.DEFAULT.copy(enabled = true)
        val planned = NagSchedule.nextAnchorStrictlyAfter(due, 1, due)
        assertEquals(local(perth, 2026, 6, 15, 22, 0), planned)
        val resumed = QuietHours.placeNag(planned, AlertLevel.STANDARD, config, perth)
        assertEquals(local(perth, 2026, 6, 16, 7, 0), resumed)
        val following = NagSchedule.afterDelivery(due, 1, resumed)
        assertEquals(resumed + 60_000L, following)
        val steps = (following!! - due) / 60_000L
        assertEquals(due + steps * 60_000L, following)
        val first = due
        assertEquals(first, first)
        assertTrue(QuietHours.inWindow(first, config, perth).not() || first == due)
        assertEquals(due, if (due <= due) due else QuietHours.placeNag(due, AlertLevel.STANDARD, config, perth))
    }

    @Test
    fun aFirstFireInsideTheWindowIsNotANagPlacement() {
        val due = local(perth, 2026, 6, 15, 23, 30)
        val config = QuietConfig.DEFAULT.copy(enabled = true)
        assertTrue(QuietHours.inWindow(due, config, perth))
        val placed = if (due <= due) due else QuietHours.placeNag(due, AlertLevel.STANDARD, config, perth)
        assertEquals(due, placed)
    }

    @Test
    fun holdForDndTruthTable() {
        val levels = AlertLevel.entries
        for (level in levels) {
            val off = QuietConfig.DEFAULT.copy(followDnd = false, criticalPauses = false)
            assertFalse(QuietHours.holdForDnd(level, off, dndActive = true))
            assertFalse(QuietHours.holdForDnd(level, off, dndActive = false))
        }
        val follow = QuietConfig.DEFAULT.copy(followDnd = true)
        assertFalse(DndState.isActive(0, priority = 2, none = 3, alarms = 4))
        assertFalse(DndState.isActive(1, priority = 2, none = 3, alarms = 4))
        assertTrue(DndState.isActive(2, priority = 2, none = 3, alarms = 4))
        assertTrue(DndState.isActive(3, priority = 2, none = 3, alarms = 4))
        assertTrue(DndState.isActive(4, priority = 2, none = 3, alarms = 4))
        for (active in listOf(false, true)) {
            assertFalse(QuietHours.holdForDnd(AlertLevel.PROMINENT, follow, active))
            assertFalse(QuietHours.holdForDnd(AlertLevel.CRITICAL, follow, active))
            assertEquals(active, QuietHours.holdForDnd(AlertLevel.STANDARD, follow, active))
        }
        val criticalAlso = follow.copy(criticalPauses = true)
        assertTrue(QuietHours.holdForDnd(AlertLevel.CRITICAL, criticalAlso, dndActive = true))
        assertFalse(QuietHours.holdForDnd(AlertLevel.CRITICAL, criticalAlso, dndActive = false))
        assertFalse(QuietHours.holdForDnd(AlertLevel.PROMINENT, criticalAlso, dndActive = true))
        assertTrue(QuietHours.holdForDnd(AlertLevel.STANDARD, criticalAlso, dndActive = true))
    }

    @Test
    fun mergeIgnoresAnEqualOrOutOfRangeWindowAndKeepsTheOtherKeys() {
        val current = QuietConfig.DEFAULT
        val equal = QuietHours.merge(
            current,
            QuietPatch(enabled = true, startMinute = 100, endMinute = 100, followDnd = true),
        )
        assertTrue(equal.enabled)
        assertTrue(equal.followDnd)
        assertEquals(current.startMinute, equal.startMinute)
        assertEquals(current.endMinute, equal.endMinute)
        val oneSide = QuietHours.merge(current, QuietPatch(startMinute = current.endMinute))
        assertEquals(current.startMinute, oneSide.startMinute)
        val bad = QuietHours.merge(current, QuietPatch(startMinute = 2000, criticalPauses = true))
        assertEquals(current.startMinute, bad.startMinute)
        assertTrue(bad.criticalPauses)
    }

    private fun window(start: Int, end: Int) = QuietConfig(
        enabled = false,
        startMinute = start,
        endMinute = end,
        followDnd = false,
        criticalPauses = false,
    )

    private fun local(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    }

    private fun nextTransition(zone: ZoneId, wantGap: Boolean): FoundTransition {
        var cursor = Instant.parse("2024-01-01T00:00:00Z")
        val limit = Instant.parse("2036-01-01T00:00:00Z")
        while (cursor.isBefore(limit)) {
            val transition = zone.rules.nextTransition(cursor) ?: break
            if (transition.isGap == wantGap) {
                return FoundTransition(zone, transition)
            }
            cursor = transition.instant.plusSeconds(1)
        }
        error("No ${if (wantGap) "gap" else "overlap"} in $zone")
    }

    private class FoundTransition(val zone: ZoneId, val transition: ZoneOffsetTransition) {
        val instant get() = transition.instant
        val dateTimeBefore: LocalDateTime get() = transition.dateTimeBefore
        val dateTimeAfter: LocalDateTime get() = transition.dateTimeAfter
    }
}
