import Foundation
import AlarmKit

/// Metadata attached to every AlarmKit alarm the app schedules. Compiled into
/// both the app and the widget extension, so the Live Activity can show it.
struct DoAlarmMetadata: AlarmMetadata {
    var reminderID: String
    var title: String
    var mechanism: String
    var planned: Int64
}
