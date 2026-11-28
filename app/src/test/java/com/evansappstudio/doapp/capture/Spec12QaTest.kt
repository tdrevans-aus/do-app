package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.DateParser
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.alarm.alert
import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.data.RepeatUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec 12 QA (Claude test agent, 27 to 28 Nov 2026 AWST). Independent checks of the
 * capture layer. Tests marked "QA: fails until ..." expose a bug and are expected to
 * fail until the build agent fixes it.
 */
class Spec12QaTest {
    /** Wednesday 15 November 2023, 6:13 am in Perth (the Scheduler fixture's clock). */
    private val now = perth(2023, 11, 15, 6, 13)

    private fun parse(text: String, at: Long = now) =
        DateParser.parse(text, at, CaptureTimes.defaultDue(at, PERTH), PERTH)

    // ---------- DateParser "after work" ----------

    @Test
    fun afterWorkAloneIsTodayAtHalfFive() {
        val match = parse("after work drinks")!!
        assertEquals("after work", match.matched)
        assertEquals("drinks", match.title)
        assertEquals(perth(2023, 11, 15, 17, 30), match.dueAt)
    }

    @Test
    fun workOnItsOwnIsNotATime() {
        assertNull(parse("work"))
        assertNull(parse("Finish work"))
        assertNull(parse("after"))
    }

    @Test
    fun afterWorkoutIsNotAfterWork() {
        assertNull(parse("Stretch after workout"))
        assertNull(parse("after workouts"))
    }

    @Test
    fun homeworkIsNotAfterWork() {
        assertNull(parse("homework"))
        assertNull(parse("Check homework after school"))
        val match = parse("Do homework tomorrow")!!
        assertEquals("tomorrow", match.matched)
        assertEquals("Do homework", match.title)
    }

    @Test
    fun afterWorkCombinesWithDatesAndRepeats() {
        val friday = parse("Grab the dry cleaning Friday after work")!!
        assertEquals("Friday after work", friday.matched)
        assertEquals(perth(2023, 11, 17, 17, 30), friday.dueAt)
        val tomorrow = parse("Call Mum after work tomorrow")!!
        assertEquals("after work tomorrow", tomorrow.matched)
        assertEquals(perth(2023, 11, 16, 17, 30), tomorrow.dueAt)
        val hyphen = parse("Drinks after-work Friday")!!
        assertEquals(perth(2023, 11, 17, 17, 30), hyphen.dueAt)
        val daily = parse("Walk the dog daily after work")!!
        assertEquals(RepeatUnit.DAY, daily.rule!!.unit)
        assertEquals(perth(2023, 11, 15, 17, 30), daily.dueAt)
        val tuesday = parse("Bins out every Tuesday after work")!!
        assertEquals("Bins out", tuesday.title)
        assertEquals(perth(2023, 11, 21, 17, 30), tuesday.dueAt)
    }

    // QA: fails until "every day after work" is not poisoned by the unsupported "every N after ..." guard (BUG-S12-08)
    @Test
    fun everyDayAfterWorkRepeats() {
        val match = parse("Walk the dog every day after work")
        assertNotNull("no date found, so the share sheet falls back to tomorrow 9 am", match)
        assertEquals(RepeatUnit.DAY, match!!.rule!!.unit)
        assertEquals(perth(2023, 11, 15, 17, 30), match.dueAt)
    }

    @Test
    fun afterWorkOnceHalfFiveHasPassedIsInTheFuture() {
        val evening = perth(2023, 11, 15, 18, 0)
        val match = parse("Call Mum after work", evening)!!
        assertTrue("due ${match.dueAt} should be after now", match.dueAt > evening)
    }

    @Test
    fun anExplicitTimeStillWinsOverAfterWork() {
        // "at 6pm" is explicit; the parser should not leave the person at 9 am or lose the date.
        val match = parse("Drinks Friday after work at 6pm")
        assertNotNull(match)
        val due = match!!.dueAt
        assertTrue(due == perth(2023, 11, 17, 17, 30) || due == perth(2023, 11, 17, 18, 0))
    }

    // ---------- Share text edge cases ----------

    @Test
    fun veryLongTextIsCutAndStillParses() {
        val text = "Pay the electricity bill tomorrow at 5pm " + "and more ".repeat(2_000)
        val draft = CaptureDrafts.fromText(text, now, PERTH)!!
        assertTrue(draft.text.length <= ShareText.MAX_LENGTH)
        assertEquals(perth(2023, 11, 16, 17, 0), draft.dueAt)
    }

    @Test
    fun multiLineTextKeepsTheFirstLineOnly() {
        val draft = CaptureDrafts.fromText("Check this out:\nhttps://example.com/x\nFriday 3pm", now, PERTH)!!
        assertEquals("Check this out:", draft.title)
        assertTrue(draft.defaulted)
    }

    @Test
    fun emojiAndRtlTextSurvive() {
        val emoji = CaptureDrafts.fromText("🛒 Buy milk tomorrow 🥛", now, PERTH)!!
        assertEquals("🛒 Buy milk 🥛", emoji.title)
        assertEquals("tomorrow", emoji.matched)
        val arabic = CaptureDrafts.fromText("اشتري الحليب", now, PERTH)!!
        assertEquals("اشتري الحليب", arabic.title)
        assertTrue(arabic.defaulted)
        val hebrew = CaptureDrafts.fromText("לקנות חלב tomorrow", now, PERTH)!!
        assertEquals("לקנות חלב", hebrew.title)
        assertEquals(perth(2023, 11, 16, 9, 0), hebrew.dueAt)
    }

    // QA: fails until ShareText.clean treats bidi and other invisible format marks as blank (BUG-S12-05)
    @Test
    fun invisibleOnlyTextIsRefused() {
        assertNull(ShareText.clean("‏"))
        assertNull(ShareText.clean("‎ ⁦⁩"))
    }

    // QA: fails until the 200-character cut keeps whole grapheme clusters, not just surrogate pairs (BUG-S12-06)
    @Test
    fun cutNeverLeavesHalfAFlag() {
        val text = "a".repeat(198) + "🇦🇺" + " tail"
        val cut = ShareText.clean(text)!!
        assertEquals("a".repeat(198), cut)
    }

    @Test
    fun plainUrlWithoutDateWordsIsKeptWhole() {
        val url = "https://www.example.com/watch?v=dQw4w9WgXcQ"
        val draft = CaptureDrafts.fromText(url, now, PERTH)!!
        assertEquals(url, draft.title)
        assertTrue(draft.defaulted)
    }

    // QA: fails until a shared link is not parsed for dates (BUG-S12-01)
    @Test
    fun urlOnlyShareIsNotReadAsADate() {
        val urls = listOf(
            "https://www.theguardian.com/australia-news/2026/nov/27/perth-heatwave-warning",
            "https://www.abc.net.au/news/2026-11-27/perth-heatwave/105012345",
            "https://example.com/events/12/05/market",
        )
        for (url in urls) {
            val draft = CaptureDrafts.fromText(url, now, PERTH)!!
            assertEquals(url, url, draft.title)
            assertTrue("$url read as a date from \"${draft.matched}\"", draft.defaulted)
        }
    }

    @Test
    fun emptyShareIsRefused() {
        assertNull(CaptureDrafts.fromText(null, now, PERTH))
        assertNull(CaptureDrafts.fromText("", now, PERTH))
        assertNull(CaptureDrafts.fromText("\n\n\t", now, PERTH))
    }

    @Test
    fun shareAtTheCapWritesNothingAndSavesAfterUnlock() = runBlocking {
        val cx = CaptureFixture()
        cx.fx.paid = false
        repeat(FreeTier.ACTIVE_REMINDER_CAP!!) { cx.capture.add(CaptureEntry.APP, "R$it", cx.later, null) }
        val draft = CaptureDrafts.fromText("Grab the dry cleaning Friday after work", cx.fx.clockMillis, PERTH)!!
        assertEquals(
            CaptureResult.NeedsUnlock,
            cx.capture.saveShare(draft, draft.title, null, 5, AlertLevel.PROMINENT, "com.whatsapp"),
        )
        assertEquals(5, cx.fx.dao.activeCount())
        assertTrue(cx.fx.events.all().none { it.sourceId == "com.whatsapp" })
        cx.fx.paid = true
        assertTrue(cx.capture.saveShare(draft, draft.title, null, 5, AlertLevel.PROMINENT, "com.whatsapp") is CaptureResult.Saved)
    }

    @Test
    fun shareKeepsThePersonsAlertLevelAndAnEditedTitle() = runBlocking {
        val cx = CaptureFixture()
        val draft = CaptureDrafts.fromText("Bins tomorrow 7pm", cx.fx.clockMillis, PERTH)!!
        val id = (cx.capture.saveShare(draft, "  Wheelie bins ", null, 5, AlertLevel.PROMINENT, null) as CaptureResult.Saved).id
        val row = cx.fx.dao.getById(id)!!
        assertEquals("Wheelie bins", row.title)
        assertEquals(AlertLevel.PROMINENT, row.alert())
        assertNull(row.requestedAlertLevel)
        assertEquals(perth(2023, 11, 16, 19, 0), row.dueAt)
        assertEquals(perth(2023, 11, 16, 19, 0), cx.fx.alarms.at(id))
    }

    @Test
    fun longCallerIdIsClipped() = runBlocking {
        val cx = CaptureFixture()
        val draft = CaptureDrafts.fromText("Milk", cx.fx.clockMillis, PERTH)!!
        val id = (cx.capture.saveShare(draft, draft.title, null, null, AlertLevel.STANDARD, "p".repeat(500)) as CaptureResult.Saved).id
        assertEquals(Origin.MAX_SOURCE_ID, cx.fx.dao.getById(id)!!.sourceId!!.length)
    }

    // ---------- Widget Done ----------

    @Test
    fun widgetDoneCancelsTheAlarmLikeNotificationDone() = runBlocking {
        val cx = CaptureFixture()
        val widgetId = (cx.capture.add(CaptureEntry.APP, "Widget", cx.later, 5) as CaptureResult.Saved).id
        val noteId = (cx.capture.add(CaptureEntry.APP, "Note", cx.later, 5) as CaptureResult.Saved).id
        assertNotNull(cx.fx.alarms.at(widgetId))
        cx.capture.widgetDone(widgetId)
        cx.fx.commands.markDone(Origin(Actor.NOTIFICATION), noteId)
        assertNull(cx.fx.alarms.at(widgetId))
        assertNull(cx.fx.alarms.at(noteId))
        val widgetRow = cx.fx.dao.getById(widgetId)!!
        val noteRow = cx.fx.dao.getById(noteId)!!
        assertTrue(widgetRow.isDone && noteRow.isDone)
        val event = cx.fx.events.forUuid(widgetRow.uuid).last()
        assertEquals(Actor.USER.name, event.actor)
    }

    @Test
    fun widgetDoneOnARepeatMovesItOn() = runBlocking {
        val cx = CaptureFixture()
        val draft = CaptureDrafts.fromText("Water plants every day at 7pm", cx.fx.clockMillis, PERTH)!!
        val id = (cx.capture.saveShare(draft, draft.title, null, null, AlertLevel.STANDARD, null) as CaptureResult.Saved).id
        val before = cx.fx.dao.getById(id)!!.dueAt
        cx.capture.widgetDone(id)
        val after = cx.fx.dao.getById(id)!!
        assertFalse(after.isDone)
        assertTrue(after.dueAt > before)
        assertEquals(after.dueAt, cx.fx.alarms.at(id))
    }

    @Test
    fun widgetDoneBeforeUnlockGoesToTheMirrorNotRoom() = runBlocking {
        val cx = CaptureFixture()
        val id = (cx.capture.add(CaptureEntry.APP, "Locked", cx.later, 5) as CaptureResult.Saved).id
        cx.fx.unlocked = false
        cx.capture.widgetDone(id)
        assertNull(cx.fx.alarms.at(id))
        assertFalse(cx.fx.dao.getById(id)!!.isDone)
        assertTrue(cx.fx.mirror.get(id)!!.pendingDone)
    }

    @Test
    fun widgetDoneOnAMissingIdChangesNothing() = runBlocking {
        val cx = CaptureFixture()
        val id = (cx.capture.add(CaptureEntry.APP, "Keep", cx.later, 5) as CaptureResult.Saved).id
        cx.capture.widgetDone(9_999L)
        assertFalse(cx.fx.dao.getById(id)!!.isDone)
        assertEquals(cx.later, cx.fx.alarms.at(id))
    }

    // ---------- Agent adapter ----------

    @Test
    fun flagOffRefusesBeforeCheckingArguments() = runBlocking {
        val cx = CaptureFixture(enabled = false)
        for (call in listOf<suspend () -> Unit>(
            { cx.agent.createReminder(null, AgentCreateRequest(text = null)) },
            { cx.agent.createReminder("g", AgentCreateRequest(text = "x", alertLevel = "siren")) },
            { cx.agent.completeReminder("g", -1L) },
            { cx.agent.listReminders(-5) },
        )) {
            try {
                call()
                fail("expected Disabled")
            } catch (expected: AgentError.Disabled) {
                // refused
            }
        }
        assertEquals(0, cx.fx.events.all().size)
    }

    @Test
    fun agentLevelWordsInAnyCaseAreHeld() = runBlocking {
        val cx = CaptureFixture()
        for ((word, requested) in listOf(" ALARM " to "PROMINENT", "Prominent" to "PROMINENT", "LOUD" to "CRITICAL", "critical" to "CRITICAL")) {
            val row = cx.fx.dao.getById(cx.agent.createReminder("g", AgentCreateRequest("Level $word", cx.later, alertLevel = word)).id)!!
            assertEquals(word, requested, row.requestedAlertLevel)
            assertEquals(word, "STANDARD", row.alertLevel)
            assertEquals(word, "PENDING", row.approval)
        }
    }

    @Test
    fun agentCountsTowardsTheCapForEveryone() = runBlocking {
        val cx = CaptureFixture()
        cx.fx.paid = false
        repeat(FreeTier.ACTIVE_REMINDER_CAP!!) { cx.agent.createReminder("g", AgentCreateRequest("A$it", cx.later)) }
        assertEquals(CaptureResult.NeedsUnlock, cx.capture.add(CaptureEntry.WIDGET, "Mine", cx.later, null))
        try {
            cx.agent.createReminder("g", AgentCreateRequest("A6", cx.later, alertLevel = "Alarm"))
            fail("expected LimitReached")
        } catch (expected: AgentError.LimitReached) {
            // refused
        }
        assertEquals(5, cx.fx.dao.activeCount())
    }

    @Test
    fun agentLimitsAreClamped() = runBlocking {
        val cx = CaptureFixture()
        repeat(60) { cx.agent.createReminder("g", AgentCreateRequest("R$it", cx.later + it)) }
        assertEquals(AgentRequests.DEFAULT_LIMIT, cx.agent.listReminders(-5).size)
        assertEquals(AgentRequests.DEFAULT_LIMIT, cx.agent.listReminders(null).size)
        assertEquals(AgentRequests.MAX_LIMIT, cx.agent.listReminders(Int.MAX_VALUE).size)
        assertEquals(1, cx.agent.listReminders(1).size)
    }

    @Test
    fun agentTextAndCallerAreCut() = runBlocking {
        val cx = CaptureFixture()
        val made = cx.agent.createReminder("c".repeat(500), AgentCreateRequest("t".repeat(1_000), cx.later))
        val row = cx.fx.dao.getById(made.id)!!
        assertEquals(ShareText.MAX_LENGTH, row.title.length)
        assertEquals(Origin.MAX_SOURCE_ID, row.sourceId!!.length)
        assertEquals(ReminderSource.AGENT.name, row.source)
    }

    @Test
    fun agentCompleteCancelsTheAlarmAndRefusesTwice() = runBlocking {
        val cx = CaptureFixture()
        val made = cx.agent.createReminder("g", AgentCreateRequest("Once", cx.later))
        assertNotNull(cx.fx.alarms.at(made.id))
        cx.agent.completeReminder("g", made.id)
        assertNull(cx.fx.alarms.at(made.id))
        try {
            cx.agent.completeReminder("g", made.id)
            fail("expected NotFound")
        } catch (expected: AgentError.NotFound) {
            // refused
        }
    }

    @Test
    fun agentUnsupportedRepeatIsRefused() = runBlocking {
        val cx = CaptureFixture()
        for (repeat in listOf("every week", "every 3 days", "tomorrow")) {
            try {
                cx.agent.createReminder("g", AgentCreateRequest("Bins", cx.later, repeat = repeat))
                fail("expected InvalidArgument for $repeat")
            } catch (expected: AgentError.InvalidArgument) {
                // refused
            }
        }
        assertEquals(0, cx.fx.dao.activeCount())
    }

    // QA: fails until AgentRequests.plan refuses a dueAt too far ahead to schedule (BUG-S12-02)
    @Test
    fun agentAbsurdDueAtIsRefused() = runBlocking {
        val cx = CaptureFixture()
        for (due in listOf(Long.MAX_VALUE, perth(9999, 12, 31, 23, 59))) {
            try {
                cx.agent.createReminder("g", AgentCreateRequest("Far", due))
                fail("expected InvalidArgument for dueAt $due")
            } catch (expected: AgentError.InvalidArgument) {
                // refused
            } catch (other: AgentError) {
                fail("expected InvalidArgument, got ${other::class.simpleName}")
            }
        }
        assertEquals(0, cx.fx.dao.activeCount())
    }

    // QA: fails until a negative dueAt is refused instead of treated as omitted (BUG-S12-03)
    @Test
    fun agentNegativeDueAtIsRefused() = runBlocking {
        val cx = CaptureFixture()
        try {
            cx.agent.createReminder("g", AgentCreateRequest("Negative", -1L))
            fail("expected InvalidArgument")
        } catch (expected: AgentError.InvalidArgument) {
            // refused
        }
    }

    // QA: fails until the agent hold also covers update and setAlertLevel from Actor.AGENT (BUG-S12-04)
    @Test
    fun agentCannotRaiseTheLevelAfterCreate() = runBlocking {
        val cx = CaptureFixture()
        val made = cx.agent.createReminder("g", AgentCreateRequest("Held", cx.later, alertLevel = "Alarm"))
        cx.fx.commands.setAlertLevel(cx.agent.origin("g"), made.id, AlertLevel.PROMINENT)
        val row = cx.fx.dao.getById(made.id)!!
        assertEquals(AlertLevel.STANDARD, row.alert())
        assertEquals("PENDING", row.approval)
    }

    // ---------- Deep links ----------

    @Test
    fun deepLinkEdgeCases() {
        assertEquals(DeepLink.Add("🛒🛒"), DeepLinks.parse("do://add?text=%F0%9F%9B%92%F0%9F%9B%92"))
        assertEquals(DeepLink.Add("שלום"), DeepLinks.parse("do://add?text=%D7%A9%D7%9C%D7%95%D7%9D"))
        assertEquals(DeepLink.Add(null), DeepLinks.parse("do://add?text=%0A%0A"))
        assertEquals(DeepLink.Add("Milk"), DeepLinks.parse("do://add?text=Milk#frag"))
        assertEquals(DeepLink.Add("a=b"), DeepLinks.parse("do://add?text=a%3Db"))
        assertNull(DeepLinks.parse("do://addx?text=a"))
        assertNull(DeepLinks.parse("do://user@add?text=a"))
        val long = (DeepLinks.parse("do://add?text=" + "%F0%9F%9B%92".repeat(150)) as DeepLink.Add).text!!
        assertTrue(long.length <= ShareText.MAX_LENGTH)
        assertFalse(Character.isHighSurrogate(long.last()))
    }

    // QA: fails until a VIEW link from outside cannot claim the Share or Widget source (BUG-S12-07)
    @Test
    fun aLinkCannotClaimAnotherSurface() {
        for (claimed in listOf("SHARE", "WIDGET")) {
            val request = LaunchRequests.from("android.intent.action.VIEW", "do://add?text=x", claimed, null) as LaunchRequest.Add
            assertEquals(claimed, CaptureEntry.DEEP_LINK, request.entry)
        }
    }
}
