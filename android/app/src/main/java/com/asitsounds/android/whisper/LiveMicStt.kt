package com.asitsounds.android.whisper

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Live microphone → rolling Whisper transcription on Android.
 *
 * Mirrors the iOS [LiveMicSTT.swift] approach: 16 kHz mono Float32 PCM is
 * captured directly (Android's AudioRecord can do it natively, no resample
 * needed), buffered into [chunkSeconds]-second windows, and each window is
 * fed to whisper.cpp. Output streams via [events].
 *
 * Caller must hold the [Manifest.permission.RECORD_AUDIO] permission.
 */
class LiveMicStt(
    modelPath: String,
    private val chunkSeconds: Int = 5,
) {
    sealed interface Event {
        data object Started : Event
        data class Partial(val text: String) : Event
        data object Stopped : Event
        data class Failed(val message: String) : Event
    }

    private val handle: Long = WhisperNative.nativeInitFromFile(modelPath, useGpu = true)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var captureJob: Job? = null
    private var record: AudioRecord? = null

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: Flow<Event> = _events.asSharedFlow()

    @SuppressLint("MissingPermission")  // caller is responsible
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (captureJob?.isActive == true) return
        captureJob = scope.launch { runCapture() }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        record?.runCatching { stop() }
        record?.runCatching { release() }
        record = null
        _events.tryEmit(Event.Stopped)
    }

    fun release() {
        stop()
        if (handle != 0L) WhisperNative.nativeFree(handle)
        supervisor.cancel()
    }

    // ─── Internals ────────────────────────────────────────────────────────

    private suspend fun runCapture() {
        if (handle == 0L) {
            _events.emit(Event.Failed("whisper init failed"))
            return
        }
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) {
            _events.emit(Event.Failed("AudioRecord: min buffer = $minBuf"))
            return
        }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            minBuf * 2,
        )
        record = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            _events.emit(Event.Failed("AudioRecord not initialised"))
            return
        }
        rec.startRecording()
        _events.emit(Event.Started)

        val chunkSamples = rate * chunkSeconds
        val rolling = FloatArray(chunkSamples)
        var rollingFilled = 0
        val readBuf = FloatArray(minBuf)

        try {
            while (captureJob?.isActive == true) {
                val n = rec.read(readBuf, 0, readBuf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue

                var copied = 0
                while (copied < n) {
                    val take = minOf(n - copied, chunkSamples - rollingFilled)
                    System.arraycopy(readBuf, copied, rolling, rollingFilled, take)
                    rollingFilled += take
                    copied += take

                    if (rollingFilled == chunkSamples) {
                        runWhisper(rolling.copyOf())
                        rollingFilled = 0
                    }
                }
            }
        } finally {
            rec.runCatching { stop() }
            rec.runCatching { release() }
            record = null
        }
    }

    private suspend fun runWhisper(chunk: FloatArray) {
        val segments = try {
            WhisperNative.transcribe(handle, chunk, language = null, nThreads = 4)
        } catch (t: Throwable) {
            _events.emit(Event.Failed("transcribe: ${t.message}"))
            return
        }
        for (s in segments) {
            val trimmed = s.text.trim()
            if (trimmed.isNotEmpty()) _events.emit(Event.Partial(trimmed))
        }
    }
}
