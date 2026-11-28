package com.evansappstudio.doapp.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    // AT-5 (spaces, as %20 and as +)
    @Test
    fun at5DecodesSpaces() {
        assertEquals(DeepLink.Add("Buy milk tomorrow"), DeepLinks.parse("do://add?text=Buy%20milk%20tomorrow"))
        assertEquals(DeepLink.Add("Buy milk tomorrow"), DeepLinks.parse("do://add?text=Buy+milk+tomorrow"))
        assertEquals(DeepLink.Add("Buy milk"), DeepLinks.parse("do://add?text=%20%20Buy%20%20milk%20"))
        assertEquals(DeepLink.Add("50% off"), DeepLinks.parse("do://add?text=50%25%20off"))
    }

    // AT-5 (emoji, percent-encoded UTF-8 and raw)
    @Test
    fun at5DecodesEmoji() {
        assertEquals(DeepLink.Add("Shop 🛒 now"), DeepLinks.parse("do://add?text=Shop%20%F0%9F%9B%92%20now"))
        assertEquals(DeepLink.Add("Shop 🛒"), DeepLinks.parse("do://add?text=Shop%20🛒"))
        val built = DeepLinks.addLink("Feed the 🐈 at 6pm")
        assertEquals(DeepLink.Add("Feed the 🐈 at 6pm"), DeepLinks.parse(built))
    }

    // AT-5 (an empty or missing text opens an empty Add)
    @Test
    fun at5EmptyTextOpensAnEmptyAdd() {
        assertEquals(DeepLink.Add(null), DeepLinks.parse("do://add?text="))
        assertEquals(DeepLink.Add(null), DeepLinks.parse("do://add?text=%20%0A"))
        assertEquals(DeepLink.Add(null), DeepLinks.parse("do://add"))
        assertEquals(DeepLink.Add(null), DeepLinks.parse("do://add/"))
        assertEquals(DeepLink.Add(null), DeepLinks.parse("do://add?other=1"))
        assertEquals(DeepLink.Add(null), DeepLinks.parse("do://add?text"))
    }

    // AT-5 (the same rules as Share: first line, 200 characters)
    @Test
    fun at5TextIsCleanedLikeShare() {
        assertEquals(DeepLink.Add("First"), DeepLinks.parse("do://add?text=First%0ASecond"))
        val long = (DeepLinks.parse("do://add?text=" + "x".repeat(300)) as DeepLink.Add).text!!
        assertEquals(ShareText.MAX_LENGTH, long.length)
        assertEquals(DeepLink.Add("a&b"), DeepLinks.parse("do://add?from=x&text=a%26b&text=ignored"))
        assertEquals(DeepLink.Add("100%zz"), DeepLinks.parse("do://add?text=100%zz"))
    }

    // AT-5 (other links are not ours)
    @Test
    fun at5OtherLinksAreRefused() {
        assertEquals(DeepLink.Today, DeepLinks.parse("do://today"))
        assertEquals(DeepLink.Add("Hi"), DeepLinks.parse("DO://ADD?text=Hi"))
        assertNull(DeepLinks.parse(null))
        assertNull(DeepLinks.parse(""))
        assertNull(DeepLinks.parse("https://add?text=x"))
        assertNull(DeepLinks.parse("doapp://open/3"))
        assertNull(DeepLinks.parse("do://delete?id=3"))
        assertNull(DeepLinks.parse("do://add/extra?text=x"))
        assertNull(DeepLinks.parse("do:add?text=x"))
    }

    // AT-5 (how MainActivity reads the intents)
    @Test
    fun at5LaunchRequestsPickTheEntry() {
        assertEquals(
            LaunchRequest.Add(CaptureEntry.DEEP_LINK, "Buy milk"),
            LaunchRequests.from("android.intent.action.VIEW", "do://add?text=Buy%20milk", null, null),
        )
        assertEquals(
            LaunchRequest.Add(CaptureEntry.SHORTCUT, null),
            LaunchRequests.from("android.intent.action.VIEW", "do://add", "SHORTCUT", null),
        )
        assertEquals(LaunchRequest.Today, LaunchRequests.from("android.intent.action.VIEW", "do://today", "SHORTCUT", null))
        assertEquals(
            LaunchRequest.Add(CaptureEntry.WIDGET, null),
            LaunchRequests.from(LaunchRequests.ACTION_ADD, null, "WIDGET", null, trusted = true),
        )
        assertEquals(
            LaunchRequest.Add(CaptureEntry.APP, "x"),
            LaunchRequests.from(LaunchRequests.ACTION_ADD, null, "NOPE", " x \n y", trusted = true),
        )
        // From outside the app (not through the internal alias) the entry is ignored.
        assertEquals(
            LaunchRequest.Add(CaptureEntry.APP, null),
            LaunchRequests.from(LaunchRequests.ACTION_ADD, null, "WIDGET", null),
        )
        assertNull(LaunchRequests.from("android.intent.action.MAIN", null, null, null))
        assertNull(LaunchRequests.from("android.intent.action.VIEW", "doapp://open/4", null, null))
    }
}
