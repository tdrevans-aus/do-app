import Foundation

/// How a test reminder fires.
enum Mechanism: String, Codable, CaseIterable, Identifiable {
    /// Local notifications, one request per fire.
    case a = "A"
    /// Local notifications with interruption level Time Sensitive.
    case aPlus = "A+"
    /// AlarmKit, one fixed-date alarm per fire.
    case b = "B"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .a: return "A: notifications"
        case .aPlus: return "A+: Time Sensitive"
        case .b: return "B: AlarmKit"
        }
    }

    var usesNotifications: Bool { self != .b }
}

enum ReminderState: String, Codable {
    case active
    case snoozed
    case done
}

struct TestReminder: Codable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var createdAt: Int64
    /// The original due time, for display.
    var dueAt: Int64
    /// The nag anchor. Equals `dueAt` until a snooze moves it.
    var anchorAt: Int64
    /// Nil means nag off.
    var nagMinutes: Int?
    var mechanism: Mechanism
    var queueAhead: Int
    var state: ReminderState
    var snoozedUntil: Int64?
    var doneAt: Int64?
    /// Fire times currently queued with the system (soonest first).
    var queuedFires: [Int64] = []
    /// The latest fire time ever queued since the anchor was last set.
    var lastScheduled: Int64?
    /// The last thing the app saw for this reminder, for the row.
    var lastSeen: String?

    var isFinished: Bool { state == .done }
}

struct LogEvent: Codable, Identifiable, Equatable {
    var id = UUID()
    var reminderID: UUID?
    var title: String
    var mechanism: Mechanism?
    var type: String
    /// The planned fire time, when the event relates to one.
    var planned: Int64?
    /// When the app saw it (or, for notifications read back from Notification
    /// Centre, the delivery time iOS reports).
    var observed: Int64
    var note: String = ""
}

struct EngineSettings: Codable, Equatable {
    /// Pending notification requests the app may hold (iOS allows 64).
    var notificationBudget: Int = 60
    /// Scheduled AlarmKit alarms the app may hold (Apple has not published a cap).
    var alarmBudget: Int = 60
    /// Off by default. Turning it on makes A+ use `.critical`, which only works
    /// once Apple grants the Critical Alerts entitlement and it is added to the
    /// entitlements file. Without the entitlement iOS treats it as a normal alert.
    var useCriticalAlerts: Bool = false
}

/// An AlarmKit alarm this app scheduled, keyed by the alarm id.
struct AlarmFire: Codable, Equatable {
    var reminderID: UUID
    var fireAt: Int64
}

struct PersistedState: Codable {
    var reminders: [TestReminder] = []
    var events: [LogEvent] = []
    var settings = EngineSettings()
    var alarmFires: [String: AlarmFire] = [:]
    /// Notification identifiers already logged as delivered.
    var seenDelivered: [String] = []
}

enum Clock {
    static func nowMillis() -> Int64 { Int64((Date().timeIntervalSince1970 * 1_000).rounded()) }
    static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(millis) / 1_000) }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_AU")
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    private static let stampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_AU")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return f
    }()

    static func time(_ millis: Int64?) -> String {
        guard let millis else { return "-" }
        return timeFormatter.string(from: date(millis))
    }

    static func stamp(_ millis: Int64?) -> String {
        guard let millis else { return "-" }
        return stampFormatter.string(from: date(millis))
    }
}
