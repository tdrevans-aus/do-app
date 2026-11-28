package com.evansappstudio.doapp.cloud

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.Approval
import com.evansappstudio.doapp.alarm.ApprovalDecision
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderCommands
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.alarm.cutText
import com.evansappstudio.doapp.capture.AgentCreateRequest
import com.evansappstudio.doapp.capture.AgentError
import com.evansappstudio.doapp.capture.AgentFunctions
import com.evansappstudio.doapp.data.Reminder

/**
 * Spec 13: applies one assistant request from the inbox, through Spec 12's
 * [AgentFunctions] (so [ReminderCommands] runs with
 * Origin(AGENT, AGENT, agentId), the free cap and the hold rule apply), and
 * turns the outcome into the [RequestResult] completeRequest expects.
 *
 * [agent] is built per request so its enabled flag follows the assistants switch:
 * off answers `disabled`. [askBeforeRingThrough] off allows a held Loud or Alarm
 * request at once (the person's standing rule), so it is not held.
 *
 * A failure that is not an [AgentError] (a database error, the user still
 * locked) is thrown: the request stays in the inbox and is pulled again.
 */
class CloudInbox(
    private val agent: () -> AgentFunctions,
    private val commands: ReminderCommands,
    private val byId: suspend (Long) -> Reminder?,
    private val byUuid: suspend (String) -> Reminder?,
    private val askBeforeRingThrough: () -> Boolean,
) {
    suspend fun apply(request: InboxRequest): RequestResult {
        return try {
            when (val args = request.args) {
                is InboxArgs.Create -> create(request.agentId, args)
                is InboxArgs.Complete -> complete(request.agentId, args)
                InboxArgs.Unknown -> RequestResult.Failed(PhoneErrorCode.INVALID_ARGUMENT, "Unknown request kind")
            }
        } catch (error: AgentError) {
            failure(error)
        }
    }

    private suspend fun create(agentId: String, args: InboxArgs.Create): RequestResult {
        val made = agent().createReminder(
            agentId,
            AgentCreateRequest(text = args.text, dueAt = args.dueAt, repeat = args.repeat, alertLevel = args.alertLevel),
        )
        var row = byId(made.id) ?: throw AgentError.NotFound(made.id)
        if (row.approval == Approval.PENDING.name && !askBeforeRingThrough()) {
            commands.resolveApproval(RULE_ORIGIN, row.id, ApprovalDecision.ALLOW)
            row = byId(made.id) ?: row
        }
        return RequestResult.Done(wire(row))
    }

    private suspend fun complete(agentId: String, args: InboxArgs.Complete): RequestResult {
        val uuid = args.reminderUuid?.trim()
        if (uuid.isNullOrEmpty()) return RequestResult.Failed(PhoneErrorCode.INVALID_ARGUMENT, "reminderUuid is required")
        val row = byUuid(uuid)
        // Only a reminder this assistant created, still active. Anything else looks the same: not found.
        if (row == null || !ownedBy(row, agentId) || row.deletedAt != null || row.isDone) {
            return RequestResult.Failed(PhoneErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE)
        }
        agent().completeReminder(agentId, row.id)
        return RequestResult.Done(wire(byId(row.id) ?: row.copy(isDone = true)))
    }

    companion object {
        const val NOT_FOUND_MESSAGE = "No active reminder with that id"
        const val DISABLED_MESSAGE = "The person has turned assistants off in Do"

        /** Who allows a held request when "Ask me before it can ring through" is off. */
        val RULE_ORIGIN = Origin(Actor.SYSTEM, ReminderSource.USER, "assistant-rule")

        fun ownedBy(row: Reminder, agentId: String): Boolean =
            row.source == ReminderSource.AGENT.name && row.sourceId == agentId

        /** CONTRACT.md's error table, one to one. Never a title in the message. */
        fun failure(error: AgentError): RequestResult.Failed = when (error) {
            is AgentError.LimitReached -> RequestResult.Failed(PhoneErrorCode.LIMIT_REACHED, error.message)
            is AgentError.NotFound -> RequestResult.Failed(PhoneErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE)
            is AgentError.InvalidArgument -> RequestResult.Failed(PhoneErrorCode.INVALID_ARGUMENT, error.message)
            is AgentError.Disabled -> RequestResult.Failed(PhoneErrorCode.DISABLED, DISABLED_MESSAGE)
        }

        /** A reminder as CONTRACT.md's `<Reminder>`. */
        fun wire(row: Reminder): WireReminder = WireReminder(
            uuid = row.uuid,
            title = cutText(row.title, WireReminder.MAX_TITLE),
            dueAt = row.dueAt,
            status = when {
                row.deletedAt != null -> WireReminder.DELETED
                row.isDone -> WireReminder.DONE
                else -> WireReminder.ACTIVE
            },
            alertLevel = row.alertLevel,
            requestedAlertLevel = row.requestedAlertLevel,
            approval = (Approval.fromName(row.approval) ?: Approval.DEFAULT).name,
            repeat = row.repeatRule()?.token()?.let { cutText(it, WireReminder.MAX_REPEAT) },
            version = row.version,
            updatedAt = row.updatedAt,
        )
    }
}
