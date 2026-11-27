import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var engine: Engine
    @EnvironmentObject private var store: Store
    @EnvironmentObject private var alarms: AlarmKitEngine
    @State private var showingAdd = false

    var body: some View {
        NavigationStack {
            List {
                Section("Test reminders") {
                    if store.reminders.isEmpty {
                        Text("No test reminders yet. Tap Add to make one.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(store.reminders.reversed()) { reminder in
                        ReminderRow(reminder: reminder)
                    }
                    .onDelete { offsets in
                        let shown = Array(store.reminders.reversed())
                        let ids = Set(offsets.map { shown[$0].id })
                        Task { await engine.delete(ids) }
                    }
                }

                Section("Log") {
                    NavigationLink {
                        LogView()
                    } label: {
                        LabeledContent("Event log", value: "\(store.events.count) events")
                    }
                }

                SettingsSection()
            }
            .navigationTitle("Do Engine Test")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showingAdd = true
                    } label: {
                        Label("Add test reminder", systemImage: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingAdd) {
                AddReminderView()
            }
        }
    }
}

struct ReminderRow: View {
    let reminder: TestReminder
    @EnvironmentObject private var engine: Engine

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(reminder.title).font(.headline)
                Spacer()
                Text(stateText)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(reminder.isFinished ? Color.secondary : Color.orange)
            }
            Text("\(reminder.mechanism.label), nag \(nagText), queue \(reminder.queueAhead)")
                .font(.subheadline)
            Text("Due \(Clock.time(reminder.dueAt))" + anchorText)
                .font(.subheadline)
            if !reminder.isFinished {
                Text("Queued: \(queuedText)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if let seen = reminder.lastSeen {
                Text("Last seen: \(seen)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if !reminder.isFinished {
                HStack {
                    Button("Done") {
                        Task { await engine.markDone(reminder.id, source: "in-app button") }
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Snooze 10 min") {
                        Task { await engine.snooze(reminder.id, source: "in-app button") }
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
    }

    private var nagText: String {
        reminder.nagMinutes.map { "every \($0) min" } ?? "off"
    }

    private var stateText: String {
        switch reminder.state {
        case .active: return "Active"
        case .snoozed: return "Snoozed to \(Clock.time(reminder.snoozedUntil))"
        case .done: return "Done \(Clock.time(reminder.doneAt))"
        }
    }

    private var anchorText: String {
        reminder.anchorAt == reminder.dueAt ? "" : ", nags from \(Clock.time(reminder.anchorAt))"
    }

    private var queuedText: String {
        let fires = reminder.queuedFires
        if fires.isEmpty { return "nothing" }
        let shown = fires.prefix(3).map { Clock.time($0) }.joined(separator: ", ")
        return fires.count > 3 ? "\(shown) and \(fires.count - 3) more" : shown
    }
}

struct SettingsSection: View {
    @EnvironmentObject private var engine: Engine
    @EnvironmentObject private var store: Store
    @EnvironmentObject private var alarms: AlarmKitEngine

    var body: some View {
        Section {
            Stepper(value: setting(\.notificationBudget), in: 1...64) {
                LabeledContent("Notification budget", value: "\(store.settings.notificationBudget) of 64")
            }
            Stepper(value: setting(\.alarmBudget), in: 1...200) {
                LabeledContent("AlarmKit budget", value: "\(store.settings.alarmBudget)")
            }
            Toggle("Use critical alerts for A+", isOn: setting(\.useCriticalAlerts))
            LabeledContent("Notifications", value: engine.notificationStatus)
            Button("Ask for notification permission") {
                Task { await engine.requestNotificationPermission() }
            }
            LabeledContent("AlarmKit", value: alarms.authorizationText)
            Button("Ask for AlarmKit permission") {
                Task { await alarms.requestAuthorization(store: store) }
            }
            Button("Re-plan now") {
                Task { await engine.replan(reason: "manual") }
            }
            LabeledContent("Last re-plan", value: engine.lastReplan)
            Button("Clear finished reminders") {
                Task { await engine.clearFinished() }
            }
        } header: {
            Text("Settings")
        } footer: {
            Text("Critical alerts only work once Apple grants the entitlement and it is added to the app. Leave this off until then.")
        }
    }

    private func setting<T>(_ keyPath: WritableKeyPath<EngineSettings, T>) -> Binding<T> {
        Binding(
            get: { store.settings[keyPath: keyPath] },
            set: { newValue in
                var s = store.settings
                s[keyPath: keyPath] = newValue
                store.setSettings(s)
                Task { await engine.replan(reason: "settings changed") }
            }
        )
    }
}
