import SwiftUI

struct AddReminderView: View {
    @EnvironmentObject private var engine: Engine
    @Environment(\.dismiss) private var dismiss

    @State private var title = "Test reminder"
    @State private var dueIn = 60
    @State private var nag = 1
    @State private var mechanism: Mechanism = .a
    @State private var queueAhead = 10
    @State private var adding = false

    private let dueOptions = [30, 60, 120, 300]

    var body: some View {
        NavigationStack {
            Form {
                Section("Reminder") {
                    TextField("Title", text: $title)
                    Picker("Due in", selection: $dueIn) {
                        ForEach(dueOptions, id: \.self) { s in
                            Text("\(s) seconds").tag(s)
                        }
                    }
                    Picker("Nag", selection: $nag) {
                        Text("Off").tag(0)
                        Text("Every 1 minute").tag(1)
                        Text("Every 5 minutes").tag(5)
                    }
                }
                Section {
                    Picker("Mechanism", selection: $mechanism) {
                        ForEach(Mechanism.allCases) { m in
                            Text(m.label).tag(m)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Mechanism")
                } footer: {
                    Text("A uses notifications. A+ uses Time Sensitive notifications. B uses AlarmKit alarms.")
                }
                Section("Queue") {
                    Stepper(value: $queueAhead, in: 1...60) {
                        LabeledContent("Nags to queue ahead", value: "\(queueAhead)")
                    }
                }
            }
            .navigationTitle("Add test reminder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        adding = true
                        Task {
                            await engine.addReminder(
                                title: title,
                                dueInSeconds: dueIn,
                                nagMinutes: nag == 0 ? nil : nag,
                                mechanism: mechanism,
                                queueAhead: queueAhead
                            )
                            dismiss()
                        }
                    }
                    .disabled(adding)
                }
            }
        }
    }
}
