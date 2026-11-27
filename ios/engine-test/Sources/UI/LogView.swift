import SwiftUI

struct LogView: View {
    @EnvironmentObject private var engine: Engine
    @EnvironmentObject private var store: Store
    @State private var confirmingClear = false

    var body: some View {
        List {
            if store.events.isEmpty {
                Text("Nothing logged yet.").foregroundStyle(.secondary)
            }
            ForEach(store.events.reversed()) { event in
                LogRow(event: event)
            }
        }
        .navigationTitle("Event log")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                ShareLink(item: engine.exportText(), subject: Text("Do Engine Test log")) {
                    Label("Export", systemImage: "square.and.arrow.up")
                }
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("Clear log", role: .destructive) { confirmingClear = true }
            }
        }
        .confirmationDialog("Clear the whole log?", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("Clear log", role: .destructive) { store.clearLog() }
            Button("Cancel", role: .cancel) {}
        }
    }
}

struct LogRow: View {
    let event: LogEvent

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(event.type).font(.subheadline.weight(.semibold))
                Spacer()
                if let m = event.mechanism {
                    Text(m.rawValue).font(.subheadline.monospaced())
                }
            }
            if !event.title.isEmpty {
                Text(event.title).font(.footnote)
            }
            Text("Seen \(Clock.stamp(event.observed))").font(.footnote.monospaced())
            if let p = event.planned {
                Text("Planned \(Clock.stamp(p)), \(lateText(p))").font(.footnote.monospaced())
            }
            if !event.note.isEmpty {
                Text(event.note).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func lateText(_ planned: Int64) -> String {
        let seconds = Double(event.observed - planned) / 1_000
        return seconds >= 0 ? String(format: "%.1f s late", seconds) : String(format: "%.1f s early", -seconds)
    }
}
