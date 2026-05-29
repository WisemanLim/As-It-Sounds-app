import SwiftUI

struct RootView: View {
    var body: some View {
        TabView {
            PipelineView()
                .tabItem { Label("tab.transcribe", systemImage: "waveform") }

            LibraryView()
                .tabItem { Label("tab.library", systemImage: "rectangle.stack") }

            TranslitDemoView()
                .tabItem { Label("tab.translit", systemImage: "text.bubble") }

            SettingsView()
                .tabItem { Label("tab.settings", systemImage: "gear") }
        }
    }
}

#Preview {
    RootView()
}
