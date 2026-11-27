package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.Actor
import com.evansappstudio.doapp.alarm.AlertLevel
import com.evansappstudio.doapp.alarm.Approval
import com.evansappstudio.doapp.alarm.Origin
import com.evansappstudio.doapp.alarm.ReminderCommands
import com.evansappstudio.doapp.alarm.ReminderSource
import com.evansappstudio.doapp.alarm.alert
import com.evansappstudio.doapp.billing.FreeTier
import com.evansappstudio.doapp.data.Reminder
import java.time.ZoneId

/**
 * Why an agent call failed. Each maps one to one onto an AppFunctions
 * exception when the service is switched on (see Build notes in Spec 12):
 * [Disabled] to AppFunctionNotSupportedException (or AppFunctionDisabledException),
 * [LimitReached] to AppFunctionLimitExceededException, [InvalidArgument] to
 * AppFunctionInvalidArgumentException, [NotFound] to AppFunctionElementNotFoundException.
 */
sealed class AgentError(message: String) : Exception(message) {
    class Disabled : AgentError("Do's assistant functions are switched off in this build")

    class LimitReached(cap: Int?) : AgentError(
        "Limit reached: the free version of Do keeps ${FreeTier.capLabel(cap)} active reminders. " +
            "Ask the person to mark one done or unlock Do, then try again.",
    )

    class InvalidArgument(message: String) : AgentError(message)

    class NotFound(id: Long) : AgentError("No active reminder with id $id")
}

/** What an agent sees of a reminder. Titles only, as in notifications; no history. */
data class AgentReminder(
    val id: Long,
    val title: String,
    val dueAt: Long,
    val overdue: Boolean,
    val repeat: String?,
    val alertLevel: String,
    val requestedAlertLevel: String?,
    val approval: String,
)

/**
 * The thin layer under the AppFunctions service: createReminder, completeReminder
 * and listReminders. Writes go through [ReminderCommands] with
 * Origin(AGENT, AGENT, caller), so the free cap and the agent hold apply.
 * [enabled] is BuildConfig.APP_FUNCTIONS_ENABLED; when false every call fails
 * with [AgentError.Disabled] and nothing is read or written.
 */
class AgentFunctions(
    private val enabled: Boolean,
    private val commands: ReminderCommands,
    private val active: suspend () -> List<Reminder>,
    private val byId: suspend (Long) -> Reminder?,
    private val defaultNagMinutes: () -> Int?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    fun origin(callerId: String?): Origin = Origin(Actor.AGENT, ReminderSource.AGENT, callerId)

    suspend fun createReminder(callerId: String?, request: AgentCreateRequest): AgentReminder {
        guard()
        val now = clock()
        val plan = when (val result = AgentRequests.plan(request, now, zone())) {
            is AgentPlanResult.Invalid -> throw AgentError.InvalidArgument(result.message)
            is AgentPlanResult.Ok -> result.plan
        }
        val id = commands.add(
            origin = origin(callerId),
            title = plan.title,
            dueAt = plan.dueAt,
            nagMinutes = defaultNagMinutes(),
            rule = plan.rule,
            alertLevel = plan.alertLevel,
        ) ?: throw AgentError.LimitReached(FreeTier.ACTIVE_REMINDER_CAP)
        val row = byId(id) ?: throw AgentError.NotFound(id)
        return view(row, clock())
    }

    suspend fun completeReminder(callerId: String?, id: Long): AgentReminder {
        guard()
        val row = byId(id)
        if (id <= 0L || row == null || row.deletedAt != null || row.isDone) throw AgentError.NotFound(id)
        commands.markDone(origin(callerId), id)
        val after = byId(id) ?: row.copy(isDone = true)
        return view(after, clock())
    }

    suspend fun listReminders(limit: Int?): List<AgentReminder> {
        guard()
        val now = clock()
        return active().take(AgentRequests.limit(limit)).map { view(it, now) }
    }

    private fun guard() {
        if (!enabled) throw AgentError.Disabled()
    }

    private fun view(row: Reminder, now: Long) = AgentReminder(
        id = row.id,
        title = row.title,
        dueAt = row.dueAt,
        overdue = !row.isDone && row.dueAt <= now,
        repeat = row.repeatRule()?.token(),
        alertLevel = row.alert().name,
        requestedAlertLevel = AlertLevel.fromName(row.requestedAlertLevel)?.name,
        approval = (Approval.fromName(row.approval) ?: Approval.DEFAULT).name,
    )
}
