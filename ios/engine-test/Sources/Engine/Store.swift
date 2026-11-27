import Foundation
import Combine

/// Test reminders, settings and the event log, kept in one JSON file in
/// Application Support. Written atomically after every change.
@MainActor
final class Store: ObservableObject {
    @Published private(set) var state: PersistedState

    private let fileURL: URL
    private static let maxEvents = 5_000

    init() {
        let fm = FileManager.default
        let dir = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        fileURL = dir.appendingPathComponent("engine-test-state.json")
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode(PersistedState.self, from: data) {
            state = decoded
        } else {
            state = PersistedState()
        }
    }

    var reminders: [TestReminder] { state.reminders }
    var events: [LogEvent] { state.events }
    var settings: EngineSettings { state.settings }

    func reminder(_ id: UUID) -> TestReminder? {
        state.reminders.first { $0.id == id }
    }

    func update(_ id: UUID, _ change: (inout TestReminder) -> Void) {
        guard let i = state.reminders.firstIndex(where: { $0.id == id }) else { return }
        change(&state.reminders[i])
        save()
    }

    func add(_ reminder: TestReminder) {
        state.reminders.append(reminder)
        save()
    }

    func remove(_ ids: Set<UUID>) {
        state.reminders.removeAll { ids.contains($0.id) }
        save()
    }

    func mutate(_ change: (inout PersistedState) -> Void) {
        change(&state)
        save()
    }

    func setSettings(_ settings: EngineSettings) {
        guard settings != state.settings else { return }
        state.settings = settings
        save()
    }

    func log(
        _ type: String,
        reminder: TestReminder? = nil,
        mechanism: Mechanism? = nil,
        planned: Int64? = nil,
        observed: Int64 = Clock.nowMillis(),
        note: String = ""
    ) {
        let event = LogEvent(
            reminderID: reminder?.id,
            title: reminder?.title ?? "",
            mechanism: mechanism ?? reminder?.mechanism,
            type: type,
            planned: planned,
            observed: observed,
            note: note
        )
        state.events.append(event)
        if state.events.count > Self.maxEvents {
            state.events.removeFirst(state.events.count - Self.maxEvents)
        }
        if let id = reminder?.id, let i = state.reminders.firstIndex(where: { $0.id == id }) {
            state.reminders[i].lastSeen = "\(type) at \(Clock.time(observed))"
        }
        save()
    }

    func clearLog() {
        state.events.removeAll()
        save()
    }

    private func save() {
        do {
            let data = try JSONEncoder().encode(state)
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            // Nothing sensible to do in a test app beyond keeping the in-memory copy.
            print("Do Engine Test: could not save state: \(error)")
        }
    }
}
