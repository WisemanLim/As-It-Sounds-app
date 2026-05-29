package com.asitsounds.android.whisper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.min

/**
 * Decodes a media [Uri] into 16 kHz mono Float32 PCM — what whisper.cpp wants.
 *
 * Strategy:
 *  1. MediaExtractor selects the first audio track.
 *  2. MediaCodec decodes it to Int16 PCM at the source sample-rate and
 *     channel-count.
 *  3. Channels are folded to mono (mean of all channels).
 *  4. The result is resampled to 16 kHz with a simple linear interpolation,
 *     which is "good enough" for ASR — Whisper itself is robust to imperfect
 *     resampling at small ratios.
 */
object AudioDecoder {

    private const val TARGET_RATE = 16_000

    /** End-to-end decode helper. */
    suspend fun decodeToPCM16k(context: Context, uri: Uri): FloatArray = withContext(Dispatchers.IO) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not openFileDescriptor for $uri")
        fd.use { pfd ->
            val ex = MediaExtractor()
            ex.setDataSource(pfd.fileDescriptor)
            try {
                decode(ex)
            } finally {
                ex.release()
            }
        }
    }

    private fun decode(ex: MediaExtractor): FloatArray {
        val (trackIndex, format) = selectAudioTrack(ex)
            ?: error("No audio track in source media.")

        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("audio track has no mime")
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        ex.selectTrack(trackIndex)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        val pcmShorts = ArrayList<Short>(16 * 1024)
        var sawEOS = false

        try {
            while (!sawEOS) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val read = ex.readSampleData(inputBuffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, read, ex.sampleTime, 0)
                        ex.advance()
                    }
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex < 0) break
                    val outBuf = codec.getOutputBuffer(outputIndex)!!
                    if (info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shortBuf: ShortBuffer = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shortBuf.hasRemaining()) {
                            pcmShorts.add(shortBuf.get())
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEOS = true
                        break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        // Int16 → Float32 mono.
        val mono = if (srcChannels == 1) {
            FloatArray(pcmShorts.size) { pcmShorts[it].toInt() / 32_768f }
        } else {
            val frames = pcmShorts.size / srcChannels
            FloatArray(frames) { i ->
                var sum = 0
                for (c in 0 until srcChannels) sum += pcmShorts[i * srcChannels + c].toInt()
                (sum / srcChannels) / 32_768f
            }
        }

        return if (srcRate == TARGET_RATE) mono else resampleLinear(mono, srcRate, TARGET_RATE)
    }

    private fun selectAudioTrack(ex: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to fmt
        }
        return null
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty()) return input
        val outLen = (input.size.toLong() * dstRate / srcRate).toInt()
        val out = FloatArray(outLen)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = (srcPos - srcIdx).toFloat()
            val a = input[srcIdx]
            val b = input[min(srcIdx + 1, input.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
