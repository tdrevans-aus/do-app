import Foundation
import UIKit
import UserNotifications
import BackgroundTasks
import Combine

/// The test engine: plans every active reminder, keeps the soonest fires
/// queued within the budgets, and handles Done and Snooze from every source.
@MainActor
final class Engine: ObservableObject {
    static let shared = Engine()

    let store = Store()
    let alarms = AlarmKitEngine()

    static let categoryID = "DO_NAG"
    static let doneActionID = "DO_DONE"
    static let snoozeActionID = "DO_SNOOZE"
    static let refreshTaskID = "com.evansappstudio.doenginetest.refresh"
    static let snoozeMillis: Int64 = 10 * 60_000
    private static let idPrefix = "do."

    @Published private(set) var notificationStatus = "Unknown"
    @Published private(set) var lastReplan: String = "-"

    private var started = false
    private var replanChain: Task<Void, Never>?

    private init() {}

    // MARK: - Start up

    func start(reason: String) {
        guard !started else { return }
        started = true
        store.log("App launched", note: reason)
        let center = UNUserNotificationCenter.current()
        center.setNotificationCategories([Self.category()])
        alarms.startObserving(engine: self)
        Task {
            await requestNotificationPermission()
            await replan(reason: "launch")
        }
    }

    private static func category() -> UNNotificationCategory {
        let done = UNNotificationAction(identifier: doneActionID, title: "Done", options: [])
        let snooze = UNNotificationAction(identifier: snoozeActionID, title: "Snooze 10 min", options: [])
        return UNNotificationCategory(identifier: categoryID, actions: [done, snooze], intentIdentifiers: [], options: [.customDismissAction])
    }

    func requestNotificationPermission() async {
        var options: UNAuthorizationOptions = [.alert, .sound, .badge]
        if store.settings.useCriticalAlerts {
            // Only effective once Apple grants the Critical Alerts entitlement.
            options.insert(.criticalAlert)
        }
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: options)
            if !granted { store.log("Notification permission not granted") }
        } catch {
            store.log("Notification permission error", note: error.localizedDescription)
        }
        await refreshNotificationStatus()
    }

    func refreshNotificationStatus() async {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        let auth: String
        switch s.authorizationStatus {
        case .authorized: auth = "Allowed"
        case .denied: auth = "Denied"
        case .notDetermined: auth = "Not asked"
        case .provisional: auth = "Provisional"
        case .ephemeral: auth = "Ephemeral"
        @unknown default: auth = "Other"
        }
        let ts = s.timeSensitiveSetting == .enabled ? "on" : "off"
        let crit = s.criticalAlertSetting == .enabled ? "on" : "off"
        notificationStatus = "\(auth); Time Sensitive \(ts); Critical \(crit)"
    }

    // MARK: - Adding and changing reminders

    func addReminder(title: String, dueInSeconds: Int, nagMinutes: Int?, mechanism: Mechanism, queueAhead: Int) async {
        if mechanism == .b {
            await alarms.requestAuthorization(store: store)
        }
        let now = Clock.nowMillis()
        let due = (now / 1_000 + Int64(dueInSeconds)) * 1_000
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let reminder = TestReminder(
            id: UUID(),
            title: trimmed.isEmpty ? "Test reminder" : trimmed,
            createdAt: now,
            dueAt: due,
            anchorAt: due,
            nagMinutes: NagPlan.normalize(nagMinutes),
            mechanism: mechanism,
            queueAhead: max(1, queueAhead),
            state: .active
        )
        store.add(reminder)
        let nag = reminder.nagMinutes.map { "\($0) min" } ?? "off"
        store.log("Reminder added", reminder: reminder, planned: due, note: "nag \(nag), queue \(reminder.queueAhead)")
        await replan(reason: "add")
    }

    func markDone(_ id: UUID, source: String, planned: Int64? = nil) async {
        guard let r = store.reminder(id), !r.isFinished else {
            store.log("Done ignored (already done or deleted)", note: source)
            return
        }
        let now = Clock.nowMillis()
        store.log("Done", reminder: r, planned: planned, observed: now, note: source)
        await cancelEverything(for: r)
        store.update(id) {
            $0.state = .done
            $0.doneAt = now
            $0.queuedFires = []
        }
        await replan(reason: "done")
    }

    func snooze(_ id: UUID, source: String, planned: Int64? = nil) async {
        guard let r = store.reminder(id), !r.isFinished else { return }
        let now = Clock.nowMillis()
        let until = NagPlan.roundUpToSecond(now) + Self.snoozeMillis
        store.log("Snoozed 10 min", reminder: r, planned: planned, observed: now, note: "\(source); next \(Clock.time(until))")
        await cancelEverything(for: r)
        store.update(id) {
            $0.state = .snoozed
            $0.snoozedUntil = until
            $0.anchorAt = until
            $0.lastScheduled = nil
            $0.queuedFires = []
        }
        await replan(reason: "snooze")
    }

    func delete(_ ids: Set<UUID>) async {
        for id in ids {
            if let r = store.reminder(id) {
                await cancelEverything(for: r)
                store.log("Reminder deleted", reminder: r)
            }
        }
        store.remove(ids)
        await replan(reason: "delete")
    }

    func clearFinished() async {
        let ids = Set(store.reminders.filter(\.isFinished).map(\.id))
        store.remove(ids)
    }

    private func cancelEverything(for r: TestReminder) async {
        let center = UNUserNotificationCenter.current()
        let prefix = Self.idPrefix + r.id.uuidString + "."
        let pending = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(prefix) }
        center.removePendingNotificationRequests(withIdentifiers: pending)
        let delivered = await center.deliveredNotifications().map(\.request.identifier).filter { $0.hasPrefix(prefix) }
        center.removeDeliveredNotifications(withIdentifiers: delivered)
        await alarms.cancelAll(for: r.id, store: store)
    }

    // MARK: - Re-planning

    /// Re-plans every active reminder. Calls are serialised so two re-plans
    /// never race on the pending queue.
    func replan(reason: String) async {
        let previous = replanChain
        let task = Task { @MainActor in
            await previous?.value
            await self.performReplan(reason: reason)
        }
        replanChain = task
        await task.value
    }

    private func performReplan(reason: String) async {
        let now = Clock.nowMillis()
        await harvestDelivered()

        // Snoozes that have run out become active again.
        for r in store.reminders where r.state == .snoozed {
            if let until = r.snoozedUntil, until <= now {
                store.update(r.id) { $0.state = .active }
            }
        }

        var notificationCandidates: [NagPlan.FireCandidate] = []
        var alarmCandidates: [NagPlan.FireCandidate] = []
        for r in store.reminders where !r.isFinished {
            let lastScheduled = effectiveLastScheduled(r, now: now)
            if NagPlan.needsCatchUp(dueAt: r.anchorAt, nagMinutes: r.nagMinutes, lastScheduled: lastScheduled, now: now) {
                store.log("Catch-up planned (queue ran dry)", reminder: r, note: reason)
            }
            let fires = NagPlan.upcomingFires(
                dueAt: r.anchorAt,
                nagMinutes: r.nagMinutes,
                now: now,
                count: r.queueAhead,
                lastScheduled: lastScheduled
            )
            let candidates = fires.map { NagPlan.FireCandidate(reminderID: r.id, fireAt: $0) }
            if r.mechanism.usesNotifications {
                notificationCandidates += candidates
            } else {
                alarmCandidates += candidates
            }
        }

        let wantedNotifications = NagPlan.withinBudget(notificationCandidates, budget: store.settings.notificationBudget)
        let wantedAlarms = NagPlan.withinBudget(alarmCandidates, budget: store.settings.alarmBudget)

        let queuedNotifications = await syncNotifications(wantedNotifications)
        let queuedAlarms = await alarms.sync(wantedAlarms, store: store)

        var queuedByReminder: [UUID: [Int64]] = [:]
        for c in queuedNotifications + queuedAlarms {
            queuedByReminder[c.reminderID, default: []].append(c.fireAt)
        }
        for r in store.reminders where !r.isFinished {
            let queued = (queuedByReminder[r.id] ?? []).sorted()
            let pastOnly = pastScheduled(r, now: now)
            store.update(r.id) {
                $0.queuedFires = queued
                $0.lastScheduled = queued.last ?? pastOnly
            }
        }

        lastReplan = "\(Clock.time(now)) (\(reason)): \(queuedNotifications.count) notifications, \(queuedAlarms.count) alarms"
        store.log("Re-planned", note: "\(reason); \(queuedNotifications.count) notifications, \(queuedAlarms.count) alarms queued")
        await refreshNotificationStatus()
        scheduleBackgroundRefresh()
    }

    /// The latest fire time queued for this reminder, used to spot a queue that
    /// ran dry. Fires are always queued soonest first, so anything still queued
    /// covers the anchors before it.
    private func effectiveLastScheduled(_ r: TestReminder, now: Int64) -> Int64? {
        ([r.lastScheduled].compactMap { $0 } + r.queuedFires).max()
    }

    /// The latest queued fire that has already had its chance to fire. Used when
    /// nothing is queued any more, so a fire dropped by the budget before its
    /// time does not hide a gap.
    private func pastScheduled(_ r: TestReminder, now: Int64) -> Int64? {
        ([r.lastScheduled].compactMap { $0 } + r.queuedFires).filter { $0 <= now }.max()
    }

    // MARK: - Local notifications (A and A+)

    private static func identifier(_ c: NagPlan.FireCandidate) -> String {
        "\(idPrefix)\(c.reminderID.uuidString).\(c.fireAt)"
    }

    static func parse(identifier: String) -> NagPlan.FireCandidate? {
        guard identifier.hasPrefix(idPrefix) else { return nil }
        let parts = identifier.dropFirst(idPrefix.count).split(separator: ".")
        guard parts.count == 2, let id = UUID(uuidString: String(parts[0])), let at = Int64(parts[1]) else { return nil }
        return NagPlan.FireCandidate(reminderID: id, fireAt: at)
    }

    /// Makes the pending queue match `wanted` and returns what is now queued.
    private func syncNotifications(_ wanted: [NagPlan.FireCandidate]) async -> [NagPlan.FireCandidate] {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(Self.idPrefix) }
        let pendingSet = Set(pending)
        let wantedByID = Dictionary(wanted.map { (Self.identifier($0), $0) }, uniquingKeysWith: { a, _ in a })

        let toRemove = pending.filter { wantedByID[$0] == nil }
        if !toRemove.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: toRemove)
        }

        var queued: [NagPlan.FireCandidate] = []
        for (id, c) in wantedByID {
            if pendingSet.contains(id) {
                queued.append(c)
                continue
            }
            guard let r = store.reminder(c.reminderID) else { continue }
            let request = UNNotificationRequest(identifier: id, content: content(for: r, fireAt: c.fireAt), trigger: trigger(for: c.fireAt))
            do {
                try await center.add(request)
                queued.append(c)
            } catch {
                store.log("Notification schedule failed", reminder: r, planned: c.fireAt, note: error.localizedDescription)
            }
        }
        return queued
    }

    private func content(for r: TestReminder, fireAt: Int64) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = r.title
        let nag = r.nagMinutes.map { "nags every \($0) min" } ?? "no nag"
        content.body = "Planned \(Clock.time(fireAt)), \(r.mechanism.label), \(nag)."
        content.categoryIdentifier = Self.categoryID
        content.threadIdentifier = r.id.uuidString
        content.userInfo = [
            "reminderID": r.id.uuidString,
            "planned": NSNumber(value: fireAt),
            "mechanism": r.mechanism.rawValue,
        ]
        switch r.mechanism {
        case .aPlus:
            if store.settings.useCriticalAlerts {
                // Needs the Critical Alerts entitlement from Apple (not in the
                // entitlements file). Without it iOS ignores the critical level.
                content.interruptionLevel = .critical
                content.sound = .defaultCritical
            } else {
                content.interruptionLevel = .timeSensitive
                content.sound = .default
            }
        default:
            content.interruptionLevel = .active
            content.sound = .default
        }
        return content
    }

    private func trigger(for fireAt: Int64) -> UNNotificationTrigger {
        let comps = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute, .second],
            from: Clock.date(fireAt)
        )
        return UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
    }

    /// Logs notifications iOS has delivered that the app has not seen yet.
    /// The delivery time comes from iOS, so this records background deliveries too.
    private func harvestDelivered() async {
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
        var seen = Set(store.state.seenDelivered)
        var changed = false
        for n in delivered {
            let id = n.request.identifier
            guard !seen.contains(id), let c = Self.parse(identifier: id) else { continue }
            seen.insert(id)
            changed = true
            let observed = Int64((n.date.timeIntervalSince1970 * 1_000).rounded())
            store.log("Delivered (read from Notification Centre)", reminder: store.reminder(c.reminderID), planned: c.fireAt, observed: observed)
        }
        if changed {
            let trimmed = Array(seen.suffix(2_000))
            store.mutate { $0.seenDelivered = trimmed }
        }
    }

    // MARK: - Notification callbacks

    func notificationWillPresent(identifier: String) {
        guard let c = Self.parse(identifier: identifier) else { return }
        store.log("Delivered (app open)", reminder: store.reminder(c.reminderID), planned: c.fireAt)
        store.mutate { $0.seenDelivered.append(identifier) }
    }

    func notificationResponse(identifier: String, action: String) async {
        guard let c = Self.parse(identifier: identifier) else { return }
        switch action {
        case Self.doneActionID:
            await markDone(c.reminderID, source: "notification action", planned: c.fireAt)
        case Self.snoozeActionID:
            await snooze(c.reminderID, source: "notification action", planned: c.fireAt)
        case UNNotificationDismissActionIdentifier:
            store.log("Notification dismissed", reminder: store.reminder(c.reminderID), planned: c.fireAt)
            await replan(reason: "dismiss")
        default:
            store.log("Notification tapped", reminder: store.reminder(c.reminderID), planned: c.fireAt)
            await replan(reason: "tap")
        }
    }

    // MARK: - Background refresh

    func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Fails on the simulator and when Background App Refresh is off.
            print("Do Engine Test: could not submit refresh task: \(error)")
        }
    }

    func runBackgroundRefresh() async {
        store.log("Background refresh ran")
        await replan(reason: "background refresh")
    }

    // MARK: - Export

    func exportText() -> String {
        let device = UIDevice.current
        var lines: [String] = [
            "Do Engine Test log",
            "Exported \(Clock.stamp(Clock.nowMillis())) (\(TimeZone.current.identifier))",
            "\(device.model), \(device.systemName) \(device.systemVersion)",
            "Budgets: notifications \(store.settings.notificationBudget), alarms \(store.settings.alarmBudget); critical switch \(store.settings.useCriticalAlerts ? "on" : "off")",
            "Notification settings: \(notificationStatus)",
            "AlarmKit: \(alarms.authorizationText)",
            "",
            "observed | planned | late (s) | mechanism | event | reminder | note",
        ]
        for e in store.events {
            var late = "-"
            if let p = e.planned {
                late = String(format: "%.1f", Double(e.observed - p) / 1_000)
            }
            lines.append([
                Clock.stamp(e.observed),
                Clock.stamp(e.planned),
                late,
                e.mechanism?.rawValue ?? "-",
                e.type,
                e.title.isEmpty ? "-" : e.title,
                e.note,
            ].joined(separator: " | "))
        }
        return lines.joined(separator: "\n")
    }
}
