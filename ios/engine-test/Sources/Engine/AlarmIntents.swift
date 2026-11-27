import Foundation
import AppIntents
import AlarmKit

/// Runs when the AlarmKit stop button (labelled Done) is pressed.
/// AlarmKit runs the intent in the app's process, launching it in the
/// background if needed.
struct DoneAlarmIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Done"
    static var description = IntentDescription("Marks a Do Engine Test reminder done and cancels its nags.")
    static var openAppWhenRun = false

    @Parameter(title: "Reminder ID") var reminderID: String
    @Parameter(title: "Alarm ID") var alarmID: String
    @Parameter(title: "Planned") var planned: Int

    init() {}

    init(reminderID: String, alarmID: String, planned: Int) {
        self.reminderID = reminderID
        self.alarmID = alarmID
        self.planned = planned
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        let engine = Engine.shared
        engine.start(reason: "AlarmKit Done")
        if let id = UUID(uuidString: reminderID) {
            await engine.markDone(id, source: "AlarmKit Done button", planned: Int64(planned))
        }
        return .result()
    }
}

/// Runs when the AlarmKit secondary button (Snooze 10 min) is pressed.
struct SnoozeAlarmIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Snooze 10 min"
    static var description = IntentDescription("Snoozes a Do Engine Test reminder for 10 minutes.")
    static var openAppWhenRun = false

    @Parameter(title: "Reminder ID") var reminderID: String
    @Parameter(title: "Alarm ID") var alarmID: String
    @Parameter(title: "Planned") var planned: Int

    init() {}

    init(reminderID: String, alarmID: String, planned: Int) {
        self.reminderID = reminderID
        self.alarmID = alarmID
        self.planned = planned
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        let engine = Engine.shared
        engine.start(reason: "AlarmKit Snooze")
        if let alarm = UUID(uuidString: alarmID) {
            try? AlarmManager.shared.stop(id: alarm)
        }
        if let id = UUID(uuidString: reminderID) {
            await engine.snooze(id, source: "AlarmKit Snooze button", planned: Int64(planned))
        }
        return .result()
    }
}
