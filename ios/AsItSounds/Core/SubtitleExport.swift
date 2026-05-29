import Foundation

public enum SubtitleFormat: String, CaseIterable, Sendable {
    case srt, vtt, txt

    public var fileExtension: String { rawValue }

    public func render(_ segments: [PipelineSegment]) -> String {
        switch self {
        case .srt: return renderSRT(segments)
        case .vtt: return renderVTT(segments)
        case .txt: return renderTXT(segments)
        }
    }

    private func renderSRT(_ segs: [PipelineSegment]) -> String {
        segs.enumerated().map { i, s in
            "\(i + 1)\n\(timecode(s.startMs, srt: true)) --> \(timecode(s.endMs, srt: true))\n\(body(s))\n"
        }.joined(separator: "\n")
    }

    private func renderVTT(_ segs: [PipelineSegment]) -> String {
        var out = "WEBVTT\n\n"
        for s in segs {
            out += "\(timecode(s.startMs, srt: false)) --> \(timecode(s.endMs, srt: false))\n\(body(s))\n\n"
        }
        return out
    }

    private func renderTXT(_ segs: [PipelineSegment]) -> String {
        segs.map { s in
            let times = "[\(timecode(s.startMs, srt: false)) — \(timecode(s.endMs, srt: false))]"
            return "\(times)\n\(body(s))"
        }.joined(separator: "\n\n")
    }

    /// Build the multi-line subtitle body. Always EN; appends 한 / KO if filled.
    private func body(_ s: PipelineSegment) -> String {
        var lines = [s.textEn]
        if !s.textHangul.isEmpty { lines.append(s.textHangul) }
        if !s.textKo.isEmpty     { lines.append(s.textKo) }
        return lines.joined(separator: "\n")
    }

    /// `srt = true` uses `HH:MM:SS,mmm`; `false` (VTT/TXT) uses `HH:MM:SS.mmm`.
    private func timecode(_ ms: Int64, srt: Bool) -> String {
        let totalMs = max(0, ms)
        let h = totalMs / 3_600_000
        let m = (totalMs / 60_000) % 60
        let s = (totalMs / 1_000) % 60
        let mss = totalMs % 1_000
        let sep = srt ? "," : "."
        return String(format: "%02d:%02d:%02d%@%03d", h, m, s, sep, mss)
    }
}
