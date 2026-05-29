import AVFoundation
import AVKit
import SwiftUI

/// Tiny player + scroll-to-highlight view. Drives an `AVPlayer` and exposes
/// the current playback time as `@State` so the segment list can render which
/// row should be in focus. Tapping a segment seeks the player.
struct PlayerView: View {
    let url: URL
    let segments: [PipelineSegment]

    @State private var player: AVPlayer = AVPlayer()
    @State private var currentMs: Int64 = 0
    @State private var timeObserver: Any?

    var body: some View {
        VStack(spacing: 0) {
            VideoPlayer(player: player)
                .frame(height: 200)

            ScrollViewReader { proxy in
                List {
                    ForEach(segments) { seg in
                        SegmentRow(segment: seg, isActive: isActive(seg))
                            .id(seg.id)
                            .onTapGesture { seek(to: seg.startMs) }
                    }
                }
                .onChange(of: activeId) { _, new in
                    guard let new else { return }
                    withAnimation { proxy.scrollTo(new, anchor: .center) }
                }
            }
        }
        .onAppear {
            let item = AVPlayerItem(url: url)
            player.replaceCurrentItem(with: item)
            // Sample the playback time ~10 Hz to drive the highlight.
            timeObserver = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 0.1, preferredTimescale: 600),
                queue: .main
            ) { t in
                currentMs = Int64(CMTimeGetSeconds(t) * 1_000)
            }
            player.play()
        }
        .onDisappear {
            if let obs = timeObserver { player.removeTimeObserver(obs); timeObserver = nil }
            player.pause()
        }
    }

    private var activeId: Int? {
        segments.first { currentMs >= $0.startMs && currentMs < $0.endMs }?.id
    }

    private func isActive(_ seg: PipelineSegment) -> Bool {
        currentMs >= seg.startMs && currentMs < seg.endMs
    }

    private func seek(to ms: Int64) {
        let target = CMTime(seconds: Double(ms) / 1_000, preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        player.play()
    }
}

private struct SegmentRow: View {
    let segment: PipelineSegment
    let isActive: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(format(ms: segment.startMs)) — \(format(ms: segment.endMs))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            Text("EN  " + segment.textEn).font(.body)
            Text("한  " + segment.textHangul).font(.body).foregroundStyle(Color.accentColor)
            if !segment.textKo.isEmpty {
                Text("KO  " + segment.textKo).font(.body).foregroundStyle(Color(.systemGreen))
            }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 4)
        .listRowBackground(isActive ? Color.accentColor.opacity(0.18) : Color.clear)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(
            format: NSLocalizedString("a11y.segment.row", comment: ""),
            segment.id + 1, format(ms: segment.startMs), segment.textEn, segment.textHangul
        ))
    }

    private func format(ms: Int64) -> String {
        let totalSec = ms / 1_000
        let m = totalSec / 60
        let s = totalSec % 60
        let cs = (ms % 1_000) / 10
        return String(format: "%02d:%02d.%02d", m, s, cs)
    }
}
