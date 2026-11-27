package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.data.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextTest {
    private val now = perth(2023, 11, 15, 6, 13)

    // AT-1 (first non-blank line only, white space made single)
    @Test
    fun at1KeepsOnlyTheFirstLine() {
        assertEquals("Grab the dry cleaning", ShareText.clean("Grab the dry cleaning\nand the shoes\r\nthanks"))
        assertEquals("Call Mum", ShareText.clean("\n\n   Call   Mum \t\r\nlater"))
        assertEquals("Pay rent", ShareText.clean("Pay rent\r\rignored"))
    }

    // AT-1 (cut to 200 characters, never splitting an emoji)
    @Test
    fun at1CutsToTwoHundredCharacters() {
        val long = "a".repeat(250)
        assertEquals(200, ShareText.clean(long)!!.length)
        val emoji = "a".repeat(199) + "🛒" + "tail"
        val cut = ShareText.clean(emoji)!!
        assertEquals("a".repeat(199), cut)
        assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals(200, ShareText.clean("b".repeat(200))!!.length)
    }

    // AT-1 (empty text is refused)
    @Test
    fun at1EmptyTextIsRefused() {
        assertNull(ShareText.clean(null))
        assertNull(ShareText.clean(""))
        assertNull(ShareText.clean("   \n\t \r\n  "))
        assertNull(ShareText.clean("​ "))
        assertNull(CaptureDrafts.fromText("  \n ", now, PERTH))
    }

    // AT-1 (parse result and the words it came from; AT-7's chat message)
    @Test
    fun at1ParsesTheDateAndKeepsItsWords() {
        val draft = CaptureDrafts.fromText("Grab the dry cleaning Friday after work\nsee you", now, PERTH)!!
        assertEquals("Grab the dry cleaning Friday after work", draft.text)
        assertEquals("Grab the dry cleaning", draft.title)
        assertEquals("Friday after work", draft.matched)
        assertEquals(perth(2023, 11, 17, 17, 30), draft.dueAt)
        assertFalse(draft.defaulted)
        assertNull(draft.rule)
    }

    // AT-1 (a repeat in the shared text is kept)
    @Test
    fun at1KeepsARepeatPhrase() {
        val draft = CaptureDrafts.fromText("Bins out every Tuesday at 7am", now, PERTH)!!
        assertEquals("Bins out", draft.title)
        assertEquals("every Tuesday at 7am", draft.matched)
        assertEquals(perth(2023, 11, 21, 7, 0), draft.dueAt)
        assertEquals(RepeatUnit.WEEK, draft.rule!!.unit)
        assertEquals(1, draft.rule!!.interval)
    }

    // AT-1 (no date found: tomorrow at 9 am, shown as the default)
    @Test
    fun at1NoDateMeansTomorrowNineAm() {
        val draft = CaptureDrafts.fromText("Grab the dry cleaning", now, PERTH)!!
        assertEquals("Grab the dry cleaning", draft.title)
        assertEquals(perth(2023, 11, 16, 9, 0), draft.dueAt)
        assertTrue(draft.defaulted)
        assertNull(draft.matched)
        val off = CaptureDrafts.fromText("Call Mum tomorrow 5pm", now, PERTH, parseDates = false)!!
        assertEquals("Call Mum tomorrow 5pm", off.title)
        assertTrue(off.defaulted)
    }

    // AT-1 (text that is only a date keeps the whole text as the title)
    @Test
    fun at1DateOnlyTextKeepsItsTitle() {
        val draft = CaptureDrafts.fromText("tomorrow at 5pm", now, PERTH)!!
        assertEquals("tomorrow at 5pm", draft.title)
        assertEquals(perth(2023, 11, 16, 17, 0), draft.dueAt)
    }

    @Test
    fun quickChoicesLandWhereTheSheetSays() {
        assertEquals(perth(2023, 11, 15, 19, 0), CaptureTimes.tonight(now, PERTH))
        val late = perth(2023, 11, 15, 20, 30)
        assertEquals(late + CaptureTimes.HOUR_MS, CaptureTimes.tonight(late, PERTH))
        assertEquals(perth(2023, 11, 16, 9, 0), CaptureTimes.tomorrow(now, PERTH))
        assertEquals(perth(2023, 11, 18, 9, 0), CaptureTimes.weekend(now, PERTH))
        assertEquals(perth(2023, 11, 18, 9, 0), CaptureTimes.weekend(perth(2023, 11, 18, 8, 0), PERTH))
        assertEquals(perth(2023, 11, 25, 9, 0), CaptureTimes.weekend(perth(2023, 11, 18, 10, 0), PERTH))
        assertEquals(perth(2023, 11, 25, 9, 0), CaptureTimes.weekend(perth(2023, 11, 19, 10, 0), PERTH))
    }
}
