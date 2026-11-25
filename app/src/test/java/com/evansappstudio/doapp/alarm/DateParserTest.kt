package com.evansappstudio.doapp.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DateParserTest {
    private val zone: ZoneId = ZoneId.of("Australia/Perth")
    private val now: Long = at(2026, 11, 25, 18, 3)
    private val draft: Long = at(2026, 11, 25, 18, 8)

    @Test
    fun parserTable() {
        cases.forEach { row ->
            val match = DateParser.parse(row.input, now, draft, zone)
            if (row.title == null) {
                assertNull("case ${row.n} ${row.input}", match)
            } else {
                checkNotNull(match) { "case ${row.n} ${row.input}" }
                assertEquals("case ${row.n} title", row.title, match.title)
                assertEquals("case ${row.n} due", row.due, format(match.dueAt))
                assertEquals("case ${row.n} rule", row.rule, DateParser.debugRule(match.rule))
                if (row.matched != null) {
                    assertEquals("case ${row.n} span", row.matched, match.matched)
                }
                assertEquals(
                    "case ${row.n} leftover",
                    row.title,
                    leftover(row.input, match.start, match.end),
                )
            }
        }
    }

    @Test
    fun mappedRepeatPhrasesAndRolls() {
        expect("daily", "", "2026-11-25 18:08", "1 DAY")
        expect("every 1 day", "", "2026-11-25 18:08", "1 DAY")
        expect("every weekday", "", "2026-11-25 18:08", "1 WEEK MON,TUE,WED,THU,FRI")
        expect("Wednesdays", "", "2026-11-25 18:08", "1 WEEK WED")
        expect("every Wednesday", "", "2026-11-25 18:08", "1 WEEK WED")
        expect("weekly on wed", "", "2026-11-25 18:08", "1 WEEK WED")
        expect("hourly", "", "2026-11-25 18:08", "1 HOUR")
        expect("every 2 hours", "", "2026-11-25 18:08", "2 HOUR")
        expect("every 15 minutes", "", "2026-11-25 18:08", "15 MINUTE")
        expect("every 30 min", "", "2026-11-25 18:08", "30 MINUTE")
        expect("monthly", "", "2026-11-25 18:08", "1 MONTH DOM:25")
        expect("every month", "", "2026-11-25 18:08", "1 MONTH DOM:25")
        expect("25th each month", "", "2026-11-25 18:08", "1 MONTH DOM:25")
        expect("every 25th", "", "2026-11-25 18:08", "1 MONTH DOM:25")
        expect("monthly on the 25th", "", "2026-11-25 18:08", "1 MONTH DOM:25")
        expect("last Friday of the month", "", "2026-11-27 18:08", "1 MONTH NTH:LAST,FRIDAY")
        expect("2nd Tuesday each month", "", "2026-12-08 18:08", "1 MONTH NTH:2,TUESDAY")
        expect("monthly on the 2nd tue", "", "2026-12-08 18:08", "1 MONTH NTH:2,TUESDAY")
        expect("first day of the month", "", "2026-12-01 18:08", "1 MONTH NTH:1,DAY")
        expect("last day of the month", "", "2026-11-30 18:08", "1 MONTH NTH:LAST,DAY")
        expect("yearly", "", "2026-11-25 18:08", "1 YEAR")
        expect("every year", "", "2026-11-25 18:08", "1 YEAR")
        expect("every 25 Nov", "", "2026-11-25 18:08", "1 YEAR")
        expect("from completion every 3 months", "", "2026-11-25 18:08", "3 MONTH from COMPLETION")
        expect("22-8", "", "2027-08-22 18:08", "OFF")
        expect("22.8", "", "2027-08-22 18:08", "OFF")
        expect("4 p.m.", "", "2026-11-26 16:00", "OFF")
        expect("in 20 minutes", "", "2026-11-25 18:23", "OFF")
        expect("the 25th of December", "", "2026-12-25 18:08", "OFF")
        expect("every hour on weekdays at 3pm", "every hour on weekdays", "2026-11-26 15:00", "OFF")
    }

    @Test
    fun doesNotInventADueFromOrdinaryWords() {
        listOf(
            "tonight",
            "yesterday",
            "25",
            "weekly shop",
            "every 3 months after I do it",
            "May I call her",
            "Buy milk",
            "8/22",
            "12/25",
            "every hour on weekdays",
        ).forEach { input ->
            assertNull(input, DateParser.parse(input, now, draft, zone))
        }
    }

    private fun expect(input: String, title: String, due: String, rule: String) {
        val match = DateParser.parse(input, now, draft, zone)
        checkNotNull(match) { input }
        assertEquals("$input title", title, match.title)
        assertEquals("$input due", due, format(match.dueAt))
        assertEquals("$input rule", rule, DateParser.debugRule(match.rule))
    }

    private fun format(millis: Long): String {
        val zoned = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        return "%04d-%02d-%02d %02d:%02d".format(
            zoned.year,
            zoned.monthValue,
            zoned.dayOfMonth,
            zoned.hour,
            zoned.minute,
        )
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    }

    private fun leftover(text: String, start: Int, end: Int): String {
        val left = text.substring(0, start).trim()
        val right = text.substring(end).trim()
        return listOf(left, right).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private data class Row(
        val n: Int,
        val input: String,
        val title: String?,
        val due: String?,
        val rule: String?,
        val matched: String? = null,
    )

    private val cases = listOf(
        Row(1, "Collect passport next Monday 4pm", "Collect passport", "2026-11-30 16:00", "OFF", "next Monday 4pm"),
        Row(2, "Make dinner reservation at 14:00 tomorrow", "Make dinner reservation", "2026-11-26 14:00", "OFF", "at 14:00 tomorrow"),
        Row(3, "Wish Sally happy birthday on Aug 22 at 8 AM", "Wish Sally happy birthday", "2027-08-22 08:00", "OFF", "on Aug 22 at 8 AM"),
        Row(4, "Phone contract expires May 30 2030", "Phone contract expires", "2030-05-30 18:08", "OFF", "May 30 2030"),
        Row(5, "Kids' dentist Fri 4:15 PM", "Kids' dentist", "2026-11-27 16:15", "OFF", "Fri 4:15 PM"),
        Row(6, "4pm", "", "2026-11-26 16:00", "OFF"),
        Row(7, "4:00 PM", "", "2026-11-26 16:00", "OFF"),
        Row(8, "16:00", "", "2026-11-26 16:00", "OFF"),
        Row(9, "Aug 22", "", "2027-08-22 18:08", "OFF"),
        Row(10, "22 August", "", "2027-08-22 18:08", "OFF"),
        Row(11, "in 20 mins", "", "2026-11-25 18:23", "OFF"),
        Row(12, "in 6 months", "", "2027-05-25 18:03", "OFF"),
        Row(13, "in 1h 20m", "", "2026-11-25 19:23", "OFF"),
        Row(14, "2.5 hours", "", "2026-11-25 20:33", "OFF"),
        Row(15, "3p Thu", "", "2026-11-26 15:00", "OFF"),
        Row(16, "Dentist 3p Thu", "Dentist", "2026-11-26 15:00", "OFF", "3p Thu"),
        Row(17, "Meeting in 1h 20m", "Meeting", "2026-11-25 19:23", "OFF", "in 1h 20m"),
        Row(18, "Laundry 2.5 hours", "Laundry", "2026-11-25 20:33", "OFF", "2.5 hours"),
        Row(19, "6pm", "", "2026-11-26 18:00", "OFF"),
        Row(20, "18:30", "", "2026-11-25 18:30", "OFF"),
        Row(21, "noon", "", "2026-11-26 12:00", "OFF"),
        Row(22, "midnight", "", "2026-11-26 00:00", "OFF"),
        Row(23, "in 20 min", "", "2026-11-25 18:23", "OFF"),
        Row(24, "in 2 hours", "", "2026-11-25 20:03", "OFF"),
        Row(25, "tomorrow", "", "2026-11-26 18:08", "OFF"),
        Row(26, "today", "", "2026-11-25 18:08", "OFF"),
        Row(27, "fri", "", "2026-11-27 18:08", "OFF"),
        Row(28, "next tue", "", "2026-12-01 18:08", "OFF"),
        Row(29, "next Monday", "", "2026-11-30 18:08", "OFF"),
        Row(30, "3 dec", "", "2026-12-03 18:08", "OFF"),
        Row(31, "the 25th", "", "2026-11-25 18:08", "OFF"),
        Row(32, "22/8", "", "2027-08-22 18:08", "OFF"),
        Row(33, "8/22", null, null, null),
        Row(34, "22/8/2030", "", "2030-08-22 18:08", "OFF"),
        Row(35, "12/25", null, null, null),
        Row(36, "25/12", "", "2026-12-25 18:08", "OFF"),
        Row(37, "2030-05-30", "", "2030-05-30 18:08", "OFF"),
        Row(38, "Take bins out every day", "Take bins out", "2026-11-25 18:08", "1 DAY", "every day"),
        Row(39, "weekdays", "", "2026-11-25 18:08", "1 WEEK MON,TUE,WED,THU,FRI"),
        Row(40, "every 2 weeks on wed", "", "2026-11-25 18:08", "2 WEEK WED"),
        Row(41, "every hour", "", "2026-11-25 18:08", "1 HOUR"),
        Row(42, "monthly on the last fri", "", "2026-11-27 18:08", "1 MONTH NTH:LAST,FRIDAY"),
        Row(43, "Bins out every Tuesday at 7am", "Bins out", "2026-12-01 07:00", "1 WEEK TUE", "every Tuesday at 7am"),
        Row(44, "every 3 months after completion", "", "2026-11-25 18:08", "3 MONTH from COMPLETION"),
        Row(45, "Buy milk", null, null, null),
        Row(46, "May I call her", null, null, null),
        Row(47, "Sit in the meeting tomorrow", "Sit in the meeting", "2026-11-26 18:08", "OFF", "tomorrow"),
        Row(48, "tomorrow noon", "", "2026-11-26 12:00", "OFF"),
        Row(49, "every hour on weekdays", null, null, null),
        Row(50, "NEXT MONDAY 4PM", "", "2026-11-30 16:00", "OFF"),
    )
}
