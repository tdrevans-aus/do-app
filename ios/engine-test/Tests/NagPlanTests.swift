import XCTest

/// Mirrors Do's Kotlin NagScheduleTest, plus the iOS queue-ahead and budget logic.
final class NagPlanTests: XCTestCase {
    private let dueAt: Int64 = 1_700_000_000_000
    private let minute = NagPlan.minuteMillis

    func testAnchorIsDueAtPlusKIntervalsWithoutDrift() {
        for k in Int64(0)...3 {
            XCTAssertEqual(dueAt + k * minute, NagPlan.anchorAt(dueAt: dueAt, nagMinutes: 1, k: k))
        }
        XCTAssertEqual(dueAt + 180 * 60 * minute, NagPlan.anchorAt(dueAt: dueAt, nagMinutes: 60, k: 180))
    }

    func testOnTimeAndLateFiresStayOnTheAnchor() {
        XCTAssertEqual(dueAt + 60_000, NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: 1, now: dueAt))
        XCTAssertEqual(dueAt + 60_000, NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: 1, now: dueAt + 2_000))
        XCTAssertEqual(dueAt + 120_000, NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: 1, now: dueAt + 60_000))
        XCTAssertEqual(dueAt + 120_000, NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: 1, now: dueAt + 62_000))
    }

    func testMissedFireIsNowThenCadenceResumesFromTheAnchor() {
        let now = dueAt + 10 * minute + 5_000
        XCTAssertEqual(now, NagPlan.catchUp(dueAt: dueAt, now: now))
        XCTAssertEqual(dueAt + 11 * minute, NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: 1, now: now))
    }

    func testNagOffAlertsOnceThenStops() {
        XCTAssertEqual(dueAt, NagPlan.catchUp(dueAt: dueAt, now: dueAt - 30_000))
        XCTAssertNil(NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: nil, now: dueAt))
        XCTAssertNil(NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: nil, now: dueAt + 60_000))
    }

    func testEachIntervalSecondFireIsDuePlusThatInterval() {
        for minutes in [5, 10, 15, 30, 60] {
            let second = NagPlan.afterDelivery(dueAt: dueAt, nagMinutes: minutes, now: dueAt)
            XCTAssertEqual(dueAt + Int64(minutes) * minute, second)
            XCTAssertEqual(NagPlan.anchorAt(dueAt: dueAt, nagMinutes: minutes, k: 1), second)
        }
    }

    func testNormalizeTreatsZeroAndUnknownAsOff() {
        XCTAssertNil(NagPlan.normalize(nil))
        XCTAssertNil(NagPlan.normalize(0))
        XCTAssertNil(NagPlan.normalize(2))
        XCTAssertEqual(5, NagPlan.normalize(5))
        XCTAssertEqual(1, NagPlan.normalize(1))
    }

    // MARK: - iOS queue ahead

    func testUpcomingFiresBeforeDueStartAtDueWithoutDrift() {
        let fires = NagPlan.upcomingFires(dueAt: dueAt, nagMinutes: 1, now: dueAt - 30_000, count: 4, lastScheduled: nil)
        XCTAssertEqual(fires, [dueAt, dueAt + minute, dueAt + 2 * minute, dueAt + 3 * minute])
    }

    func testUpcomingFiresAfterOnTimeDeliveriesStayOnAnchor() {
        // Fires up to dueAt + 2 min were queued and delivered; it is now 2 min 7 s in.
        let now = dueAt + 2 * minute + 7_000
        let fires = NagPlan.upcomingFires(dueAt: dueAt, nagMinutes: 1, now: now, count: 3, lastScheduled: dueAt + 5 * minute)
        XCTAssertEqual(fires, [dueAt + 3 * minute, dueAt + 4 * minute, dueAt + 5 * minute])
    }

    func testUpcomingFiresCatchUpWhenTheQueueRanDry() {
        // Only the first fire was ever queued; ten minutes later nothing is pending.
        let now = dueAt + 10 * minute + 5_200
        XCTAssertTrue(NagPlan.needsCatchUp(dueAt: dueAt, nagMinutes: 1, lastScheduled: dueAt, now: now))
        let fires = NagPlan.upcomingFires(dueAt: dueAt, nagMinutes: 1, now: now, count: 3, lastScheduled: dueAt)
        XCTAssertEqual(fires, [dueAt + 10 * minute + 9_000, dueAt + 11 * minute, dueAt + 12 * minute])
    }

    func testNoCatchUpBeforeDueOrWhileTheQueueCovers() {
        XCTAssertFalse(NagPlan.needsCatchUp(dueAt: dueAt, nagMinutes: 1, lastScheduled: nil, now: dueAt - 1))
        XCTAssertFalse(NagPlan.needsCatchUp(dueAt: dueAt, nagMinutes: 1, lastScheduled: dueAt + minute, now: dueAt + minute))
        XCTAssertTrue(NagPlan.needsCatchUp(dueAt: dueAt, nagMinutes: 1, lastScheduled: nil, now: dueAt))
    }

    func testNagOffQueuesOnceAndCatchesUpOnceIfMissed() {
        XCTAssertEqual(NagPlan.upcomingFires(dueAt: dueAt, nagMinutes: nil, now: dueAt - 1_000, count: 5, lastScheduled: nil), [dueAt])
        XCTAssertEqual(NagPlan.upcomingFires(dueAt: dueAt, nagMinutes: nil, now: dueAt + 1_000, count: 5, lastScheduled: dueAt), [])
        XCTAssertEqual(NagPlan.upcomingFires(dueAt: dueAt, nagMinutes: nil, now: dueAt + 1_000, count: 5, lastScheduled: nil), [dueAt + 4_000])
    }

    func testZeroCountQueuesNothing() {
        XCTAssertEqual(NagPlan.upcomingFires(dueAt: dueAt, nagMinutes: 1, now: dueAt, count: 0, lastScheduled: nil), [])
    }

    func testRoundUpToSecond() {
        XCTAssertEqual(NagPlan.roundUpToSecond(1_000), 1_000)
        XCTAssertEqual(NagPlan.roundUpToSecond(1_001), 2_000)
    }

    // MARK: - Budget

    func testBudgetKeepsTheSoonestAcrossReminders() {
        let a = UUID(), b = UUID()
        var candidates: [NagPlan.FireCandidate] = []
        for k in Int64(0)..<10 {
            candidates.append(.init(reminderID: a, fireAt: dueAt + k * minute))
            candidates.append(.init(reminderID: b, fireAt: dueAt + 30_000 + k * minute))
        }
        let kept = NagPlan.withinBudget(candidates.shuffled(), budget: 5)
        XCTAssertEqual(kept.map(\.fireAt), [dueAt, dueAt + 30_000, dueAt + minute, dueAt + minute + 30_000, dueAt + 2 * minute])
        XCTAssertEqual(NagPlan.withinBudget(candidates, budget: 0), [])
        XCTAssertEqual(NagPlan.withinBudget(candidates, budget: 100).count, 20)
    }
}
