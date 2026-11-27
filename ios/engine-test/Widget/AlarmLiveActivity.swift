import SwiftUI
import WidgetKit
import ActivityKit
import AlarmKit

/// Live Activity UI for the app's AlarmKit alarms (mechanism B). The system
/// shows its own alarm alert either way; this supplies the lock screen and
/// Dynamic Island views when the system asks for them.
struct AlarmLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AlarmAttributes<DoAlarmMetadata>.self) { context in
            LockScreenAlarmView(title: Self.title(context.attributes), state: Self.stateText(context.state))
                .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "alarm.fill")
                        .foregroundStyle(context.attributes.tintColor)
                        .accessibilityHidden(true)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(Self.title(context.attributes))
                        .font(.headline)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(Self.stateText(context.state))
                        .font(.subheadline)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text("Do Engine Test, mechanism B")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "alarm.fill")
                    .foregroundStyle(context.attributes.tintColor)
                    .accessibilityLabel("Alarm")
            } compactTrailing: {
                Text(Self.title(context.attributes))
                    .lineLimit(1)
                    .frame(maxWidth: 64)
            } minimal: {
                Image(systemName: "alarm.fill")
                    .foregroundStyle(context.attributes.tintColor)
                    .accessibilityLabel("Alarm")
            }
            .keylineTint(context.attributes.tintColor)
        }
    }

    static func title(_ attributes: AlarmAttributes<DoAlarmMetadata>) -> String {
        attributes.metadata?.title ?? "Do Engine Test alarm"
    }

    static func stateText(_ state: AlarmPresentationState) -> String {
        switch state.mode {
        case .alert: return "Ringing"
        case .countdown: return "Counting down"
        case .paused: return "Paused"
        @unknown default: return "Scheduled"
        }
    }
}

struct LockScreenAlarmView: View {
    let title: String
    let state: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "alarm.fill")
                .font(.title2)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1)
                Text(state).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
    }
}
