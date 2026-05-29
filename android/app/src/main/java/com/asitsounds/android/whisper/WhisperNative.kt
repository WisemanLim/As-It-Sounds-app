package com.asitsounds.android.whisper

data class WhisperSegment(val startMs: Long, val endMs: Long, val text: String)

/**
 * JNI surface to whisper.cpp.
 *
 * `nativeFullTranscribe` flattens segments into a `Object[]` of length
 * `3 * n_segments` — each triple is `[Long startMs, Long endMs, String text]`.
 * This avoids the overhead of declaring a Java Segment class for JNI to
 * construct; [transcribe] re-hydrates the trios on the Kotlin side.
 */
object WhisperNative {
    init {
        System.loadLibrary("ais_whisper")
    }

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeInitFromFile(modelPath: String, useGpu: Boolean): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeFullTranscribe(
        handle: Long,
        pcm: FloatArray,
        language: String?,
        nThreads: Int,
    ): Array<Any>?

    /**
     * High-level wrapper. `pcm` must be 16 kHz mono Float32. Returns an empty
     * list on failure (errors are logged on the C++ side).
     */
    fun transcribe(
        handle: Long,
        pcm: FloatArray,
        language: String? = null,
        nThreads: Int = 4,
    ): List<WhisperSegment> {
        val raw = nativeFullTranscribe(handle, pcm, language, nThreads) ?: return emptyList()
        val out = ArrayList<WhisperSegment>(raw.size / 3)
        var i = 0
        while (i + 2 < raw.size) {
            out += WhisperSegment(
                startMs = raw[i] as Long,
                endMs = raw[i + 1] as Long,
                text = raw[i + 2] as String,
            )
            i += 3
        }
        return out
    }
}
