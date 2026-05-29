import ActivityKit
import Foundation

/// Live Activity attributes for the transcription progress card that shows on
/// the lock screen and in the Dynamic Island.
///
/// **Important:** Live Activities can only be rendered by a Widget Extension
/// target. This file declares the shared `Attributes` struct that both the
/// main app (starts/updates/ends the activity) and the Widget Extension
/// (renders the UI) import. The Widget Extension target is added in Xcode
/// (`File → New → Target → Widget Extension`, then "Include Live Activity")
/// and should consume this struct via shared sources or a tiny SwiftPM
/// package — see `.doc/live-activity.md` for the step-by-step.
@available(iOS 16.1, *)
public struct TranscribeActivityAttributes: ActivityAttributes {
    public typealias ContentState = LiveState

    public struct LiveState: Codable, Hashable {
        public let phase: String          // "decoding", "transcribing", …
        public let progress: Double       // 0.0 — 1.0
        public let processedSeconds: Int  // audio seconds completed
        public let totalSeconds: Int      // total expected
        public init(phase: String, progress: Double, processedSeconds: Int, totalSeconds: Int) {
            self.phase = phase; self.progress = progress
            self.processedSeconds = processedSeconds; self.totalSeconds = totalSeconds
        }
    }

    public let projectName: String
    public let fileName: String

    public init(projectName: String, fileName: String) {
        self.projectName = projectName
        self.fileName = fileName
    }
}

/// Façade the main app calls into. No-ops gracefully on iOS < 16.1 or when
/// the Live Activity entitlement is missing.
@available(iOS 16.1, *)
public enum LiveActivityCoordinator {
    private static var current: Activity<TranscribeActivityAttributes>?

    public static func start(projectName: String, fileName: String, totalSeconds: Int) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            current = try Activity.request(
                attributes: TranscribeActivityAttributes(projectName: projectName,
                                                        fileName: fileName),
                contentState: .init(phase: "decoding", progress: 0,
                                    processedSeconds: 0, totalSeconds: totalSeconds),
                pushType: nil
            )
        } catch {
            NSLog("[LiveActivity] start failed: %@", error.localizedDescription)
        }
    }

    public static func update(phase: String, progress: Double,
                              processed: Int, total: Int) async {
        guard let activity = current else { return }
        await activity.update(using: .init(phase: phase, progress: progress,
                                           processedSeconds: processed,
                                           totalSeconds: total))
    }

    public static func end() async {
        guard let activity = current else { return }
        await activity.end(dismissalPolicy: .immediate)
        current = nil
    }
}
