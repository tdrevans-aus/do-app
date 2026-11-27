import Foundation

/// Nag maths for the iPhone engine test. Mirrors Do's Kotlin `NagSchedule`
/// (app/src/main/java/com/evansappstudio/doapp/alarm/NagSchedule.kt).
///
/// Fire times are anchored at `dueAt`: dueAt, then dueAt + k * interval.
/// A late delivery does not move the anchor, so the cadence does not drift.
/// A missed fire is delivered at "now"; the following fires return to the anchor.
/// Nag off alerts once.
///
/// All times are epoch milliseconds, as in the Kotlin code. This file is pure
/// (Foundation only) so the unit test target can compile it on its own.
enum NagPlan {
    static let minuteMillis: Int64 = 60_000

    static let allowedMinutes: Set<Int> = [1, 5, 10, 15, 30, 60]

    /// Nil and 0 mean nag off. Any other value outside `allowedMinutes` is off.
    static func normalize(_ minutes: Int?) -> Int? {
        guard let minutes, minutes != 0, allowedMinutes.contains(minutes) else { return nil }
        return minutes
    }

    static func intervalMillis(_ nagMinutes: Int) -> Int64 {
        Int64(nagMinutes) * minuteMillis
    }

    /// The k-th fire instant. k = 0 is `dueAt`.
    static func anchorAt(dueAt: Int64, nagMinutes: Int, k: Int64) -> Int64 {
        precondition(k >= 0, "k must be >= 0")
        precondition(nagMinutes > 0, "nagMinutes must be positive")
        return dueAt + k * intervalMillis(nagMinutes)
    }

    /// First schedule, or a catch-up when no alarm is outstanding.
    /// A future due time stays put. A due time already passed fires now.
    static func catchUp(dueAt: Int64, now: Int64) -> Int64 {
        now < dueAt ? dueAt : now
    }

    /// Smallest anchor strictly after `now`. Used after a delivery so the fire
    /// that just happened is not scheduled again.
    static func nextAnchorStrictlyAfter(dueAt: Int64, nagMinutes: Int, now: Int64) -> Int64 {
        let interval = intervalMillis(nagMinutes)
        if now < dueAt { return dueAt }
        let elapsed = now - dueAt
        let steps = elapsed / interval
        let boundary = dueAt + steps * interval
        return boundary > now ? boundary : boundary + interval
    }

    /// Next alarm after a notification has been posted at `now`.
    /// Nag off returns nil: the reminder alerts once and does not reschedule.
    static func afterDelivery(dueAt: Int64, nagMinutes: Int?, now: Int64) -> Int64? {
        guard let minutes = nagMinutes, minutes > 0 else { return nil }
        return nextAnchorStrictlyAfter(dueAt: dueAt, nagMinutes: minutes, now: now)
    }

    // MARK: - iOS additions (queueing ahead, because iOS cannot wake us per fire)

    /// The latest anchor at or before `now`, or nil if `now` is before `dueAt`.
    /// Nag off has one anchor, `dueAt`.
    static func latestAnchorAtOrBefore(dueAt: Int64, nagMinutes: Int?, now: Int64) -> Int64? {
        if now < dueAt { return nil }
        guard let minutes = nagMinutes, minutes > 0 else { return dueAt }
        let interval = intervalMillis(minutes)
        return dueAt + ((now - dueAt) / interval) * interval
    }

    /// True when a fire that should already have happened was never queued
    /// (the queue ran dry, for example because the budget was full or the app
    /// was not opened). `lastScheduled` is the latest fire time ever queued for
    /// this reminder, or nil if nothing has been queued.
    static func needsCatchUp(dueAt: Int64, nagMinutes: Int?, lastScheduled: Int64?, now: Int64) -> Bool {
        guard let latest = latestAnchorAtOrBefore(dueAt: dueAt, nagMinutes: nagMinutes, now: now) else {
            return false
        }
        guard let lastScheduled else { return true }
        return lastScheduled < latest
    }

    /// Up to `count` fire times to queue from `now`, soonest first, all strictly
    /// after `now`. If a fire was missed, the first entry is a catch-up at
    /// `now + catchUpLeadMillis` (rounded up to a whole second), then the
    /// anchors resume.
    static func upcomingFires(
        dueAt: Int64,
        nagMinutes: Int?,
        now: Int64,
        count: Int,
        lastScheduled: Int64?,
        catchUpLeadMillis: Int64 = 3_000
    ) -> [Int64] {
        guard count > 0 else { return [] }
        var fires: [Int64] = []
        var from = now
        if needsCatchUp(dueAt: dueAt, nagMinutes: nagMinutes, lastScheduled: lastScheduled, now: now) {
            let catchUpAt = roundUpToSecond(now + max(catchUpLeadMillis, 1))
            fires.append(catchUpAt)
            from = catchUpAt
        }
        guard let minutes = nagMinutes, minutes > 0 else {
            if fires.isEmpty && now < dueAt { fires.append(dueAt) }
            return Array(fires.prefix(count))
        }
        let interval = intervalMillis(minutes)
        var next = nextAnchorStrictlyAfter(dueAt: dueAt, nagMinutes: minutes, now: from)
        while fires.count < count {
            fires.append(next)
            next += interval
        }
        return fires
    }

    static func roundUpToSecond(_ millis: Int64) -> Int64 {
        let r = millis % 1_000
        return r == 0 ? millis : millis + (1_000 - r)
    }

    // MARK: - Budget across reminders

    struct FireCandidate: Hashable {
        let reminderID: UUID
        let fireAt: Int64
    }

    /// Keeps the soonest `budget` fires across all reminders. Ties are broken by
    /// reminder id so the result is stable between re-plans.
    static func withinBudget(_ candidates: [FireCandidate], budget: Int) -> [FireCandidate] {
        let sorted = candidates.sorted {
            $0.fireAt != $1.fireAt ? $0.fireAt < $1.fireAt : $0.reminderID.uuidString < $1.reminderID.uuidString
        }
        return Array(sorted.prefix(max(0, budget)))
    }
}
