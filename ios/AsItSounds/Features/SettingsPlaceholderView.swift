import SwiftUI

struct SettingsPlaceholderView: View {
    var body: some View {
        NavigationStack {
            Form {
                Section("Whisper") {
                    LabeledContent("Model", value: "(not configured)")
                }
                Section("Translation") {
                    LabeledContent("Engine", value: "(not configured)")
                }
                Section("About") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—")
                }
            }
            .navigationTitle("Settings")
        }
    }
}

#Preview {
    SettingsPlaceholderView()
}
