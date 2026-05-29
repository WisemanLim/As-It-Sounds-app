import BackgroundTasks
import Foundation
import UIKit

/// Minimal BGProcessingTask integration. Keeps the running pipeline alive
/// after the user backgrounds the app and reschedules itself if there's
/// still work pending.
///
/// PRD §6.6 calls for long-running file transcription to survive backgrounding;
/// Phase 1B keeps the actual pipeline foreground-only, but this scaffolding
/// lets a Phase-2+ implementation submit work via `submit(pendingFileId:)`
/// and have iOS schedule us under power constraints.
public enum BackgroundTaskCoordinator {
    public static let identifier = "com.asitsounds.ios.transcribe"

    /// Call from `AsItSoundsApp.init` / scene lifecycle. Safe to call multiple times.
    public static func registerOnce() {
        struct Once { static var done = false }
        guard !Once.done else { return }
        Once.done = true

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: identifier,
            using: nil
        ) { task in
            handle(task: task as! BGProcessingTask)
        }
    }

    /// Ask iOS to wake us up later to finish the transcript for `fileId`.
    public static func submit(pendingFileId: String) {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        // Persist the queue so `handle(task:)` knows what to do on wake.
        pending.write([pendingFileId])
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // The simulator and unsigned builds reject submission — that's
            // expected; just log.
            NSLog("[AisBG] submit failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Internals

    private static func handle(task: BGProcessingTask) {
        let queue = pending.read()
        // Always reschedule before doing work — keeps the queue alive across
        // OS-initiated kills.
        if !queue.isEmpty {
            submit(pendingFileId: queue.first ?? "")
        }

        task.expirationHandler = {
            // OS asked us to stop. Mark current file with a checkpoint so the
            // next launch can pick up where we left off. (Phase-2+ work.)
            task.setTaskCompleted(success: false)
        }

        // No real pipeline yet — just signal success so the OS doesn't burn
        // its quota on us. Phase 1B's pipeline can hook in via the queue.
        task.setTaskCompleted(success: true)
    }

    private enum pending {
        static let key = "ais.bg.pendingFileIds"
        static func read() -> [String] {
            UserDefaults.standard.array(forKey: key) as? [String] ?? []
        }
        static func write(_ ids: [String]) {
            UserDefaults.standard.set(ids, forKey: key)
        }
    }
}
