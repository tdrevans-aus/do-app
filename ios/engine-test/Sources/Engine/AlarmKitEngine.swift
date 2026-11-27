import Foundation
import SwiftUI
import AlarmKit
import ActivityKit
import Combine

/// Mechanism B: each fire is its own fixed-date AlarmKit alarm. The stop
/// button is Done (runs `DoneAlarmIntent`); the secondary button is Snooze
/// 10 min (custom behaviour, runs `SnoozeAlarmIntent`).
@MainActor
final class AlarmKitEngine: ObservableObject {
    @Published private(set) var authorizationText = "Not asked"

    private var lastStates: [UUID: String] = [:]
    private var observing = false

    private var manager: AlarmManager { AlarmManager.shared }

    func refreshAuthorization() {
        switch manager.authorizationState {
        case .authorized: authorizationText = "Allowed"
        case .denied: authorizationText = "Denied"
        case .notDetermined: authorizationText = "Not asked"
        @unknown default: authorizationText = "Other"
        }
    }

    func requestAuthorization(store: Store) async {
        refreshAuthorization()
        guard manager.authorizationState == .notDetermined else { return }
        do {
            let state = try await manager.requestAuthorization()
            store.log("AlarmKit permission answered", mechanism: .b, note: "\(state)")
        } catch {
            store.log("AlarmKit permission error", mechanism: .b, note: error.localizedDescription)
        }
        refreshAuthorization()
    }

    /// Logs every alarm state change while the app is running.
    func startObserving(engine: Engine) {
        refreshAuthorization()
        guard !observing else { return }
        observing = true
        if let current = try? manager.alarms {
            for alarm in current { lastStates[alarm.id] = Self.describe(alarm.state) }
        }
        Task { [weak self] in
            for await alarms in AlarmManager.shared.alarmUpdates {
                self?.handleUpdate(alarms, store: engine.store)
            }
        }
    }

    private func handleUpdate(_ alarms: [Alarm], store: Store) {
        var seen = Set<UUID>()
        for alarm in alarms {
            seen.insert(alarm.id)
            let state = Self.describe(alarm.state)
            if lastStates[alarm.id] != state {
                lastStates[alarm.id] = state
                let fire = store.state.alarmFires[alarm.id.uuidString]
                store.log(
                    "AlarmKit state: \(state)",
                    reminder: fire.flatMap { store.reminder($0.reminderID) },
                    mechanism: .b,
                    planned: fire?.fireAt
                )
            }
        }
        for (id, _) in lastStates where !seen.contains(id) {
            lastStates[id] = nil
            let fire = store.state.alarmFires[id.uuidString]
            store.log(
                "AlarmKit alarm gone (stopped, snoozed or cancelled)",
                reminder: fire.flatMap { store.reminder($0.reminderID) },
                mechanism: .b,
                planned: fire?.fireAt
            )
        }
    }

    private static func describe(_ state: Alarm.State) -> String {
        switch state {
        case .scheduled: return "scheduled"
        case .countdown: return "countdown"
        case .paused: return "paused"
        case .alerting: return "alerting"
        @unknown default: return "\(state)"
        }
    }

    /// Makes the scheduled alarms match `wanted` and returns what is now scheduled.
    func sync(_ wanted: [NagPlan.FireCandidate], store: Store) async -> [NagPlan.FireCandidate] {
        refreshAuthorization()
        let existing = (try? manager.alarms) ?? []
        let existingByID = Dictionary(existing.map { ($0.id.uuidString, $0) }, uniquingKeysWith: { a, _ in a })

        // Forget mapped alarms the system no longer holds (they fired and were
        // stopped, or were cancelled).
        var fires = store.state.alarmFires.filter { existingByID[$0.key] != nil }

        let wantedSet = Set(wanted)
        var queued: [NagPlan.FireCandidate] = []
        for (alarmID, fire) in fires {
            let candidate = NagPlan.FireCandidate(reminderID: fire.reminderID, fireAt: fire.fireAt)
            guard let alarm = existingByID[alarmID], let uuid = UUID(uuidString: alarmID) else { continue }
            if wantedSet.contains(candidate) {
                queued.append(candidate)
            } else if case .alerting = alarm.state {
                // Leave a ringing alarm alone; Done or Snooze will stop it.
                continue
            } else {
                try? manager.cancel(id: uuid)
                fires[alarmID] = nil
            }
        }

        let queuedSet = Set(queued)
        if !wanted.isEmpty, manager.authorizationState != .authorized {
            store.log("AlarmKit not allowed; alarms not scheduled", mechanism: .b, note: authorizationText)
            store.mutate { $0.alarmFires = fires }
            return queued
        }
        for c in wanted where !queuedSet.contains(c) {
            guard let r = store.reminder(c.reminderID) else { continue }
            let alarmID = UUID()
            do {
                _ = try await manager.schedule(id: alarmID, configuration: configuration(for: r, alarmID: alarmID, fireAt: c.fireAt))
                fires[alarmID.uuidString] = AlarmFire(reminderID: r.id, fireAt: c.fireAt)
                lastStates[alarmID] = "scheduled"
                queued.append(c)
            } catch {
                store.log("AlarmKit schedule failed", reminder: r, mechanism: .b, planned: c.fireAt, note: "\(error)")
            }
        }
        store.mutate { $0.alarmFires = fires }
        return queued
    }

    private func configuration(for r: TestReminder, alarmID: UUID, fireAt: Int64) -> AlarmManager.AlarmConfiguration<DoAlarmMetadata> {
        let done = AlarmButton(text: "Done", textColor: .white, systemImageName: "checkmark.circle.fill")
        let snooze = AlarmButton(text: "Snooze 10 min", textColor: .white, systemImageName: "zzz")
        let alert = AlarmPresentation.Alert(
            title: LocalizedStringResource(stringLiteral: r.title),
            stopButton: done,
            secondaryButton: snooze,
            secondaryButtonBehavior: .custom
        )
        let attributes = AlarmAttributes<DoAlarmMetadata>(
            presentation: AlarmPresentation(alert: alert),
            metadata: DoAlarmMetadata(reminderID: r.id.uuidString, title: r.title, mechanism: r.mechanism.rawValue, planned: fireAt),
            tintColor: .orange
        )
        return .alarm(
            schedule: .fixed(Clock.date(fireAt)),
            attributes: attributes,
            stopIntent: DoneAlarmIntent(reminderID: r.id.uuidString, alarmID: alarmID.uuidString, planned: Int(fireAt)),
            secondaryIntent: SnoozeAlarmIntent(reminderID: r.id.uuidString, alarmID: alarmID.uuidString, planned: Int(fireAt)),
            sound: .default
        )
    }

    /// Stops and cancels every alarm this app holds for the reminder.
    func cancelAll(for reminderID: UUID, store: Store) async {
        var fires = store.state.alarmFires
        for (alarmID, fire) in fires where fire.reminderID == reminderID {
            if let uuid = UUID(uuidString: alarmID) {
                try? manager.stop(id: uuid)
                try? manager.cancel(id: uuid)
            }
            fires[alarmID] = nil
        }
        store.mutate { $0.alarmFires = fires }
    }
}
