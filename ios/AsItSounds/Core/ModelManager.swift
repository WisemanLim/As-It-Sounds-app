import Foundation
import Observation

/// One of the GGUF whisper models hosted by ggerganov/whisper.cpp on Hugging Face.
/// We default to multilingual `base` (~142 MB) per the iPhone PRD.
public enum WhisperModel: String, CaseIterable, Sendable {
    case tiny         = "tiny"
    case base         = "base"
    case small        = "small"
    case medium       = "medium"
    case largeV3Turbo = "large-v3-turbo"  // 추천: 최고 품질/속도 균형

    var estimatedSize: Int64 {
        switch self {
        case .tiny:         return    77 * 1024 * 1024   //   75 MB
        case .base:         return   148 * 1024 * 1024   //  142 MB
        case .small:        return   488 * 1024 * 1024   //  466 MB
        case .medium:       return 1_534 * 1024 * 1024   // 1534 MB
        case .largeV3Turbo: return   809 * 1024 * 1024   //  809 MB
        }
    }

    var displayName: String {
        switch self {
        case .tiny:         return "Tiny (75 MB)"
        case .base:         return "Base (142 MB)"
        case .small:        return "Small (466 MB)"
        case .medium:       return "Medium (1.5 GB)"
        case .largeV3Turbo: return "Large-v3-Turbo (809 MB) ★"
        }
    }

    var fileName: String { "ggml-\(rawValue).bin" }

    var huggingFaceURL: URL {
        URL(string: "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/\(fileName)")!
    }
}

public enum ModelStatus: Sendable, Equatable {
    case notDownloaded
    case downloading(received: Int64, total: Int64)
    case ready(URL)
    case failed(String)
}

@MainActor
@Observable
public final class ModelManager: NSObject, URLSessionDownloadDelegate {
    public private(set) var status: ModelStatus = .notDownloaded
    public let model: WhisperModel

    @ObservationIgnored
    private lazy var session: URLSession = URLSession(
        configuration: .default,
        delegate: self,
        delegateQueue: OperationQueue()
    )
    @ObservationIgnored
    private var task: URLSessionDownloadTask?

    public init(model: WhisperModel = .base) {
        self.model = model
        super.init()
        if let existing = localURLIfExists() {
            status = .ready(existing)
        }
    }

    public func localURLIfExists() -> URL? {
        let url = modelsDirectory.appendingPathComponent(model.fileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    public func startDownload() {
        if case .ready = status { return }
        if case .downloading = status { return }

        try? FileManager.default.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)

        status = .downloading(received: 0, total: model.estimatedSize)
        let req = URLRequest(url: model.huggingFaceURL)
        let t = session.downloadTask(with: req)
        task = t
        t.resume()
    }

    public func cancel() {
        task?.cancel()
        task = nil
        status = .notDownloaded
    }

    private var modelsDirectory: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return appSupport.appendingPathComponent("models", isDirectory: true)
    }

    // MARK: - URLSessionDownloadDelegate (background thread)

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                           didWriteData bytesWritten: Int64,
                           totalBytesWritten: Int64,
                           totalBytesExpectedToWrite: Int64) {
        let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : model.estimatedSize
        Task { @MainActor in
            self.status = .downloading(received: totalBytesWritten, total: total)
        }
    }

    public func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                           didFinishDownloadingTo location: URL) {
        let dest = modelsDirectory.appendingPathComponent(model.fileName)
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: location, to: dest)
            // Hint iOS not to back this up to iCloud.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutableDest = dest
            try? mutableDest.setResourceValues(values)
            Task { @MainActor in self.status = .ready(dest) }
        } catch {
            Task { @MainActor in self.status = .failed(error.localizedDescription) }
        }
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        // .cancelled comes through as code -999; ignore — UI already reset.
        if (error as NSError).code == NSURLErrorCancelled { return }
        Task { @MainActor in self.status = .failed(error.localizedDescription) }
    }
}
