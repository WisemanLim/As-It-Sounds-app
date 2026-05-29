import SwiftUI

struct LibraryPlaceholderView: View {
    var body: some View {
        ContentUnavailableView(
            "Library coming in Phase 1",
            systemImage: "rectangle.stack",
            description: Text("Projects, media files, and pipeline runs will live here.")
        )
    }
}

#Preview {
    LibraryPlaceholderView()
}
