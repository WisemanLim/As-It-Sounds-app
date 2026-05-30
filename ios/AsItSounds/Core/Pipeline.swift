import Foundation
import Observation
import os

private let aisLog = Logger(subsystem: "com.asitsounds.ios", category: "pipeline")

/// One Whisper segment plus its Hangul transliteration. Korean translation
/// will be filled in by a later phase once the Translator protocol lands.
public struct PipelineSegment: Identifiable, Sendable, Hashable {
    public let id: Int
    public let startMs: Int64
    public let endMs: Int64
    public let textEn: String
    public let textHangul: String
    public var textKo: String  // Filled in by Translator. Empty when disabled / fails.
}

public enum PipelineStage: Sendable, Equatable {
    case idle
    case decoding
    case transcribing
    case transliterating
    case done
    case failed(String)
}

/// Foreground-only pipeline runner. Phase 1B keeps it serial since the demo
/// only loads a short audio file; background, BGTask, and resumable runs
/// land in a later phase.
///
/// The actual decode + whisper_full + translit work runs inside a single
/// `Task.detached` body — that keeps `WhisperEngine` (an Obj-C class that
/// isn't Sendable) on one isolation domain end-to-end.
@MainActor
@Observable
public final class PipelineRunner {
    public private(set) var stage: PipelineStage = .idle
    public private(set) var segments: [PipelineSegment] = []

    public init() {}

    public func run(
        modelPath: String,
        audioURL: URL,
        language: String? = nil,
        translator: any Translator = DisabledTranslator()
    ) async {
        segments.removeAll()
        stage = .decoding

        // Hop the heavy work off the main actor.
        aisLog.info("run start model=\(modelPath, privacy: .public) audio=\(audioURL.lastPathComponent, privacy: .public)")
        let result: Result<[PipelineSegment], Swift.Error> = await Task.detached(priority: .userInitiated) {
            do {
                let pcm = try AudioDecoder.decodeToPCM16k(url: audioURL)
                aisLog.notice("decoded PCM bytes=\(pcm.count) samples=\(pcm.count / 4)")
                guard let engine = WhisperEngine(modelPath: modelPath, useGpu: true) else {
                    throw NSError(domain: "AisPipeline", code: 1, userInfo: [
                        NSLocalizedDescriptionKey: "WhisperEngine init failed"
                    ])
                }
                var nsError: NSError?
                let raw = engine.transcribePCM(pcm, language: language, nThreads: 4, onError: &nsError)
                if let nsError { throw nsError }
                aisLog.notice("whisper segments=\(raw.count)")

                var out: [PipelineSegment] = []
                out.reserveCapacity(raw.count)
                for (i, s) in raw.enumerated() {
                    let trimmed = s.text.trimmingCharacters(in: .whitespacesAndNewlines)
                    let han = transliterate(text: trimmed)
                    out.append(.init(id: i, startMs: s.startMs, endMs: s.endMs,
                                     textEn: trimmed, textHangul: han, textKo: ""))
                }
                return .success(out)
            } catch {
                return .failure(error)
            }
        }.value

        switch result {
        case .success(let segs):
            // Phase A: show transcripts + Hangul immediately.
            segments = segs
            stage = .transliterating  // reuse stage as "translating"

            // Phase B: translate each segment sequentially. Failures fall back
            // to empty string and never abort the pipeline.
            if !(translator is DisabledTranslator) {
                for i in segs.indices {
                    do {
                        let ko = try await translator.translate(segs[i].textEn, from: "en", to: "ko")
                        segments[i].textKo = ko
                    } catch {
                        segments[i].textKo = "(translate failed)"
                    }
                }
            }
            // Persist when caller supplied a target file row.
            if let persistFileId {
                let recs = segments.map {
                    SegmentRecord(id: UUID().uuidString, fileId: persistFileId, idx: $0.id,
                                  startMs: $0.startMs, endMs: $0.endMs,
                                  textEn: $0.textEn, textHangul: $0.textHangul,
                                  textKo: $0.textKo.isEmpty ? nil : $0.textKo)
                }
                do {
                    try AppDatabase.shared.saveSegments(recs, fileId: persistFileId)
                    aisLog.info("persisted \(recs.count) segments file=\(persistFileId, privacy: .public)")
                } catch {
                    aisLog.error("saveSegments failed: \(error.localizedDescription, privacy: .public)")
                }
            }
            aisLog.notice("run done segments=\(segs.count)")
            stage = .done
        case .failure(let err):
            aisLog.error("run failed: \(err.localizedDescription, privacy: .public)")
            stage = .failed(err.localizedDescription)
        }
    }

    @ObservationIgnored
    public var persistFileId: String?
}
