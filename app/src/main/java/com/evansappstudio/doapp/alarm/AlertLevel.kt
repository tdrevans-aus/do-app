package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.data.Reminder

/**
 * Due's three alert types. They are mutually exclusive.
 * Standard is the factory default for a new reminder.
 */
enum class AlertLevel {
    STANDARD,
    CRITICAL,
    PROMINENT,
    ;

    companion object {
        val DEFAULT: AlertLevel = STANDARD

        fun fromName(raw: String?): AlertLevel? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

fun Reminder.alert(): AlertLevel = AlertLevel.fromName(alertLevel) ?: AlertLevel.DEFAULT

/** Why a fire did not get the alert behaviour the reminder asked for. */
enum class AlertFallbackReason {
    NO_FSI,
    NO_DND,
    NO_CHANNEL,
}

/**
 * What a fire actually does, given the permissions the device has granted.
 * Prominent without full-screen intent becomes Critical-style.
 * Critical without Do Not Disturb access still posts, but cannot promise to break through.
 * A missing Critical channel falls back to Standard. A fire is never skipped.
 */
data class AlertDecision(
    val requested: AlertLevel,
    val effective: AlertLevel,
    val fullScreen: Boolean,
    val bypassDnd: Boolean,
    val loopUntilAttended: Boolean,
    val reasons: List<AlertFallbackReason>,
)

object AlertPolicy {
    fun decide(
        requested: AlertLevel,
        canUseFullScreenIntent: Boolean,
        hasDndAccess: Boolean,
        criticalChannelAvailable: Boolean = true,
    ): AlertDecision {
        val reasons = mutableListOf<AlertFallbackReason>()
        var effective = requested
        if (requested == AlertLevel.PROMINENT && !canUseFullScreenIntent) {
            reasons += AlertFallbackReason.NO_FSI
            effective = if (criticalChannelAvailable) AlertLevel.CRITICAL else AlertLevel.STANDARD
        }
        if (effective == AlertLevel.CRITICAL && !criticalChannelAvailable) {
            effective = AlertLevel.STANDARD
            reasons += AlertFallbackReason.NO_CHANNEL
        }
        if (effective == AlertLevel.STANDARD &&
            !criticalChannelAvailable &&
            (requested == AlertLevel.CRITICAL || requested == AlertLevel.PROMINENT) &&
            AlertFallbackReason.NO_CHANNEL !in reasons
        ) {
            reasons += AlertFallbackReason.NO_CHANNEL
        }
        val wantsBypass = effective == AlertLevel.CRITICAL || effective == AlertLevel.PROMINENT
        if ((requested == AlertLevel.CRITICAL || requested == AlertLevel.PROMINENT) && !hasDndAccess) {
            reasons += AlertFallbackReason.NO_DND
        }
        val fullScreen = effective == AlertLevel.PROMINENT && canUseFullScreenIntent
        // FLAG_INSISTENT can keep a heads-up sounding, so a Prominent request still
        // waits for the user even when it has fallen back off the full-screen activity.
        // Standard cannot loop, and a channel failure lands there.
        val loop = requested == AlertLevel.PROMINENT && effective != AlertLevel.STANDARD
        return AlertDecision(
            requested = requested,
            effective = effective,
            fullScreen = fullScreen,
            bypassDnd = wantsBypass && hasDndAccess,
            loopUntilAttended = loop,
            reasons = reasons,
        )
    }
}
