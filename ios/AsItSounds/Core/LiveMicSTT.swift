import AVFoundation
import Foundation

/// Live microphone → rolling Whisper transcription.
///
/// Design (Phase 3, MVP):
///   1. AVAudioEngine taps the input node at the hardware sample rate.
///   2. Samples are converted to 16 kHz mono Float32 in real time.
///   3. Every ~5 s we slice off the oldest chunk and hand it to Whisper.
///   4. Returned segments stream out via `AsyncStream`.
///
/// Whisper isn't a streaming model — it expects ≥ 1 s of audio per call —
/// so the chunk approach is the standard pattern. A proper VAD + overlap
/// would land in Phase 3+.
@MainActor
public final class LiveMicSTT {

    public enum Event: Sendable {
        case started
        case partial(text: String)     // best-effort, may overlap with next
        case stopped
        case error(String)
    }

    public private(set) var isRunning = false

    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private var rollingPCM: [Float] = []
    private var continuation: AsyncStream<Event>.Continuation?
    private var whisperEngine: WhisperEngine?
    private let chunkSeconds: Int

    /// `modelPath` must point at a downloaded GGUF model. `chunkSeconds`
    /// controls how often we invoke `whisper_full` on the rolling buffer.
    public init(modelPath: String, chunkSeconds: Int = 5) {
        self.chunkSeconds = max(2, chunkSeconds)
        self.whisperEngine = WhisperEngine(modelPath: modelPath, useGpu: true)
    }

    /// Starts capture. Caller must request `AVCaptureDevice.requestAccess`
    /// for `.audio` beforehand.
    public func start() -> AsyncStream<Event> {
        AsyncStream { continuation in
            self.continuation = continuation
            do {
                try configureAndStartEngine()
                continuation.yield(.started)
                isRunning = true
            } catch {
                continuation.yield(.error(error.localizedDescription))
                continuation.finish()
            }
        }
    }

    public func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRunning = false
        continuation?.yield(.stopped)
        continuation?.finish()
        continuation = nil
    }

    // MARK: - Engine

    private func configureAndStartEngine() throws {
        let input = engine.inputNode
        let hwFormat = input.outputFormat(forBus: 0)
        guard let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                            sampleRate: 16_000,
                                            channels: 1,
                                            interleaved: false) else {
            throw NSError(domain: "LiveMic", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "16k mono format unavailable"])
        }
        converter = AVAudioConverter(from: hwFormat, to: outFormat)

        // ~0.5 s buffers — small enough to keep latency low, large enough
        // that converter setup overhead is negligible.
        let bufferFrames = AVAudioFrameCount(hwFormat.sampleRate * 0.5)
        input.installTap(onBus: 0, bufferSize: bufferFrames, format: hwFormat) { [weak self] buffer, _ in
            guard let self else { return }
            Task { @MainActor in self.handle(input: buffer, target: outFormat) }
        }

        try engine.start()
    }

    private func handle(input: AVAudioPCMBuffer, target: AVAudioFormat) {
        guard let converter else { return }
        let ratio = target.sampleRate / input.format.sampleRate
        let outCapacity = AVAudioFrameCount(Double(input.frameLength) * ratio + 32)
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: outCapacity) else { return }

        var supplied = false
        var error: NSError?
        let status = converter.convert(to: out, error: &error) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return input
        }
        if status == .error { return }

        let count = Int(out.frameLength)
        if let ch = out.floatChannelData?.pointee {
            rollingPCM.append(contentsOf: UnsafeBufferPointer(start: ch, count: count))
        }

        // Drain a chunk every `chunkSeconds` of buffered audio.
        let need = chunkSeconds * 16_000
        if rollingPCM.count >= need {
            let chunk = Array(rollingPCM.prefix(need))
            rollingPCM.removeFirst(need)
            runWhisper(chunk: chunk)
        }
    }

    private func runWhisper(chunk: [Float]) {
        guard let engine = whisperEngine else { return }
        let cont = continuation
        Task.detached(priority: .userInitiated) {
            let data = chunk.withUnsafeBufferPointer {
                Data(buffer: $0)
            }
            var nsError: NSError?
            let segs = engine.transcribePCM(data, language: nil, nThreads: 4, onError: &nsError)
            for s in segs {
                let trimmed = s.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { cont?.yield(.partial(text: trimmed)) }
            }
        }
    }
}
