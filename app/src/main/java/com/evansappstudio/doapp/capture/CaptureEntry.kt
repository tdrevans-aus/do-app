package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderSource

// Spec 12 pure. No Android imports: this file is in CorePurityTest's list.

/**
 * Which capture surface a person used to reach Add. Each maps to the [Origin]
 * the command layer stores. The tile, shortcuts and deep link are the person
 * opening Add, so they store [ReminderSource.USER]; only Share and the widget
 * have their own source (Spec 9 already has both).
 */
enum class CaptureEntry {
    APP,
    SHARE,
    WIDGET,
    TILE,
    SHORTCUT,
    DEEP_LINK,
    ;

    fun origin(sourceId: String? = null): Origin = when (this) {
        SHARE -> Origin(Actor.USER, ReminderSource.SHARE, sourceId)
        WIDGET -> Origin(Actor.USER, ReminderSource.WIDGET)
        APP, TILE, SHORTCUT, DEEP_LINK -> Origin(Actor.USER, ReminderSource.USER)
    }

    companion object {
        fun fromName(raw: String?): CaptureEntry? = entries.firstOrNull { it.name == raw }
    }
}

/** What MainActivity was opened for by a capture surface. */
sealed interface LaunchRequest {
    data class Add(val entry: CaptureEntry, val text: String?) : LaunchRequest

    data object Today : LaunchRequest
}

object LaunchRequests {
    /** The tile and the widgets open MainActivity with this action and an [EXTRA_ENTRY]. */
    const val ACTION_ADD = "com.evansappstudio.doapp.action.ADD"
    const val EXTRA_ENTRY = "com.evansappstudio.doapp.extra.ENTRY"
    const val EXTRA_TEXT = "com.evansappstudio.doapp.extra.TEXT"

    /**
     * [action], [data] (the intent's data URI as a string) and the two extras.
     * A do:// link with no [EXTRA_ENTRY] is a deep link; the static shortcuts
     * send the same links with entry SHORTCUT. Anything else is null.
     */
    fun from(action: String?, data: String?, entryName: String?, text: String?): LaunchRequest? {
        val entry = CaptureEntry.fromName(entryName)
        if (action == ACTION_ADD) {
            return LaunchRequest.Add(entry ?: CaptureEntry.APP, ShareText.clean(text))
        }
        return when (val link = DeepLinks.parse(data)) {
            is DeepLink.Add -> LaunchRequest.Add(entry ?: CaptureEntry.DEEP_LINK, link.text)
            DeepLink.Today -> LaunchRequest.Today
            null -> null
        }
    }
}
