import AVFoundation
import Foundation

/// Decodes any AVFoundation-supported media file (mp3/m4a/wav/mp4/mov/…)
/// into 16 kHz mono Float32 PCM — exactly what whisper.cpp expects.
public enum AudioDecoder {

    public enum Error: Swift.Error, LocalizedError {
        case noAudioTrack
        case readerCreationFailed(String)
        case readFailed(String)
        case converterCreationFailed
        case conversionFailed(String)

        public var errorDescription: String? {
            switch self {
            case .noAudioTrack: return "Media has no audio track."
            case .readerCreationFailed(let s): return "Reader: \(s)"
            case .readFailed(let s): return "Read: \(s)"
            case .converterCreationFailed: return "Could not create audio converter."
            case .conversionFailed(let s): return "Convert: \(s)"
            }
        }
    }

    /// Synchronously decodes `url` into Float32 PCM. Designed for offline files
    /// (no streaming). Throws on the first error encountered.
    public static func decodeToPCM16k(url: URL) throws -> Data {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .audio).first else {
            throw Error.noAudioTrack
        }

        let outSettings: [String: Any] = [
            AVFormatIDKey:            kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey:   32,
            AVLinearPCMIsFloatKey:    true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
            AVSampleRateKey:          Float(track.naturalTimeScale > 0 ? track.naturalTimeScale : 44_100),
            AVNumberOfChannelsKey:    1,
        ]

        let reader: AVAssetReader
        do { reader = try AVAssetReader(asset: asset) }
        catch { throw Error.readerCreationFailed(error.localizedDescription) }

        let output = AVAssetReaderTrackOutput(track: track, outputSettings: outSettings)
        guard reader.canAdd(output) else {
            throw Error.readerCreationFailed("cannot add track output")
        }
        reader.add(output)
        guard reader.startReading() else {
            throw Error.readFailed(reader.error?.localizedDescription ?? "startReading false")
        }

        // Source sample rate (taken from the first sample buffer's format) +
        // accumulated Float32 mono samples.
        var sourceSampleRate: Double = 0
        var raw = Data()
        raw.reserveCapacity(1024 * 1024)

        while reader.status == .reading, let sample = output.copyNextSampleBuffer() {
            if sourceSampleRate == 0,
               let desc = CMSampleBufferGetFormatDescription(sample),
               let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc) {
                sourceSampleRate = asbd.pointee.mSampleRate
            }
            guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
            var length = 0
            var ptr: UnsafeMutablePointer<Int8>?
            let status = CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                                                     totalLengthOut: &length, dataPointerOut: &ptr)
            guard status == kCMBlockBufferNoErr, let p = ptr, length > 0 else { continue }
            raw.append(UnsafeBufferPointer(start: UnsafePointer<UInt8>(OpaquePointer(p)), count: length))
        }

        if reader.status == .failed {
            throw Error.readFailed(reader.error?.localizedDescription ?? "reader failed")
        }
        if sourceSampleRate == 0 { sourceSampleRate = 44_100 }

        // Resample to 16 kHz mono Float32 using AVAudioConverter.
        return try resample(raw: raw, fromSampleRate: sourceSampleRate)
    }

    private static func resample(raw: Data, fromSampleRate srcRate: Double) throws -> Data {
        let inputFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: srcRate,
            channels: 1,
            interleaved: false)!
        let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: 16_000,
            channels: 1,
            interleaved: false)!

        let frameCount = AVAudioFrameCount(raw.count / MemoryLayout<Float32>.size)
        guard frameCount > 0 else { return Data() }

        let inputBuffer = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: frameCount)!
        inputBuffer.frameLength = frameCount
        raw.withUnsafeBytes { rawBuf in
            guard let src = rawBuf.baseAddress?.assumingMemoryBound(to: Float32.self) else { return }
            memcpy(inputBuffer.floatChannelData![0], src, Int(frameCount) * MemoryLayout<Float32>.size)
        }

        guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            throw Error.converterCreationFailed
        }

        let ratio = outputFormat.sampleRate / inputFormat.sampleRate
        let outFrameCapacity = AVAudioFrameCount(Double(frameCount) * ratio + 1024)
        let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: outFrameCapacity)!

        var supplied = false
        var error: NSError?
        let status = converter.convert(to: outputBuffer, error: &error) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return inputBuffer
        }

        if status == .error {
            throw Error.conversionFailed(error?.localizedDescription ?? "unknown")
        }

        let outFrames = Int(outputBuffer.frameLength)
        var out = Data(count: outFrames * MemoryLayout<Float32>.size)
        out.withUnsafeMutableBytes { dst in
            memcpy(dst.baseAddress!, outputBuffer.floatChannelData![0], outFrames * MemoryLayout<Float32>.size)
        }
        return out
    }
}
