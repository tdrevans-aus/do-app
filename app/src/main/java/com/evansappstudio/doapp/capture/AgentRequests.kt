package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.Approval
import com.evansappstudio.doapp.alarm.DateParser
import com.evansappstudio.doapp.data.RepeatRule
import java.time.ZoneId

// Spec 12 pure agent mapping. No Android imports: this file is in CorePurityTest's list.

/**
 * Agent alert requests are held at Normal until the person approves.
 * A person's own add is untouched. An agent that asks for Normal gets Normal
 * with no approval needed; [requested] still records what it asked for.
 */
object AgentHold {
    data class Held(val alertLevel: AlertLevel, val requested: AlertLevel?, val approval: Approval)

    fun apply(fromAgent: Boolean, asked: AlertLevel): Held = when {
        !fromAgent -> Held(asked, null, Approval.NONE)
        asked == AlertLevel.STANDARD -> Held(AlertLevel.STANDARD, AlertLevel.STANDARD, Approval.NONE)
        else -> Held(AlertLevel.STANDARD, asked, Approval.PENDING)
    }
}

/** What an agent sent to createReminder, before checking. Omitted values arrive as null, 0 or "". */
data class AgentCreateRequest(
    val text: String?,
    val dueAt: Long? = null,
    val repeat: String? = null,
    val alertLevel: String? = null,
)

/** A checked request, ready for ReminderCommands.add. [alertLevel] is what was asked for. */
data class AgentPlan(
    val title: String,
    val dueAt: Long,
    val rule: RepeatRule?,
    val alertLevel: AlertLevel,
)

sealed interface AgentPlanResult {
    data class Ok(val plan: AgentPlan) : AgentPlanResult

    data class Invalid(val message: String) : AgentPlanResult
}

object AgentRequests {
    const val DEFAULT_LIMIT = 10
    const val MAX_LIMIT = 50

    /** How far in the past a dueAt may be and still count as "now" (clock skew). */
    const val PAST_GRACE_MS = 60_000L

    /** A dueAt further ahead than this is refused: it would never fire but would hold a free slot. */
    const val MAX_YEARS_AHEAD = 10L

    fun latestDue(now: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(now).atZone(zone).plusYears(MAX_YEARS_AHEAD).toInstant().toEpochMilli()

    fun plan(request: AgentCreateRequest, now: Long, zone: ZoneId): AgentPlanResult {
        val text = ShareText.clean(request.text) ?: return AgentPlanResult.Invalid("text is required")
        val level = alertLevel(request.alertLevel)
            ?: return AgentPlanResult.Invalid("alertLevel must be Normal, Loud or Alarm")
        val raw = request.dueAt
        if (raw != null && raw < 0L) return AgentPlanResult.Invalid("dueAt must be a time in epoch milliseconds")
        // 0 is how AppFunctions sends an omitted Long.
        val asked = raw?.takeIf { it > 0L }
        if (asked != null && asked < now - PAST_GRACE_MS) {
            return AgentPlanResult.Invalid("dueAt is in the past")
        }
        if (asked != null && asked > latestDue(now, zone)) {
            return AgentPlanResult.Invalid("dueAt is more than $MAX_YEARS_AHEAD years ahead")
        }
        var title = text
        var due = asked
        var rule: RepeatRule? = null
        if (due == null) {
            val draft = CaptureDrafts.fromText(text, now, zone) ?: return AgentPlanResult.Invalid("text is required")
            title = draft.title
            due = draft.dueAt
            rule = draft.rule
        }
        val repeatText = request.repeat?.trim().orEmpty()
        if (repeatText.isNotEmpty()) {
            rule = DateParser.parse(repeatText, now, due, zone)?.rule
                ?: return AgentPlanResult.Invalid("repeat was not understood: try \"every day\", \"every Tuesday\" or \"every month\"")
        }
        return AgentPlanResult.Ok(AgentPlan(title = title, dueAt = due, rule = rule, alertLevel = level))
    }

    /**
     * Normal, Loud and Alarm (the names people see) and the stored names
     * Standard, Critical and Prominent, in any case. Blank means Normal.
     * Anything else is null, which [plan] refuses.
     */
    fun alertLevel(raw: String?): AlertLevel? = when (raw?.trim()?.lowercase()) {
        null, "", "normal", "standard" -> AlertLevel.STANDARD
        "loud", "critical" -> AlertLevel.CRITICAL
        "alarm", "prominent" -> AlertLevel.PROMINENT
        else -> null
    }

    /** 0 or less (an omitted parameter) is [DEFAULT_LIMIT]; at most [MAX_LIMIT]. */
    fun limit(raw: Int?): Int = when {
        raw == null || raw <= 0 -> DEFAULT_LIMIT
        else -> minOf(raw, MAX_LIMIT)
    }
}
