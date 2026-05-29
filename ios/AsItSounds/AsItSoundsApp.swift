import SwiftUI

@main
struct AsItSoundsApp: App {
    init() {
        BackgroundTaskCoordinator.registerOnce()
    }

    @State private var settings = AppSettings.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(settings.preferredColorScheme.colorScheme)
        }
    }
}
