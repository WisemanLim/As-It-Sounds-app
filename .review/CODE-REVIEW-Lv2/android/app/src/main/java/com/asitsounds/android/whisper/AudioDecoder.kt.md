# `AudioDecoder.kt` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../../../../INDEX.md)

**파일 요약**
- 역할: 임의 Android media URI → 16 kHz mono Float32 PCM 디코드 (MediaExtractor + MediaCodec + 선형 리샘플링).
- LOC: 141
- exports: `AudioDecoder.decodeToPCM16k(context, uri): FloatArray`
- 의존: Android Framework (`MediaExtractor`, `MediaCodec`, `MediaFormat`)
- 부수효과: Android NDK 의 디코더 native lib 메모리 alloc
- 동시성: `withContext(Dispatchers.IO)` 단일 스레드 직렬

---

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/AudioDecoder.kt lines=27-45
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
```

**분석 (Level 2)**
- `private const val TARGET_RATE = 16_000`: whisper.cpp 의 고정 입력 sample rate. 다른 값 받으면 inference 실패.
- `openFileDescriptor(uri, "r")`: SAF / content URI 모두 처리. `?.use { ... }` 패턴은 ParcelFileDescriptor 자동 close 보장.
- `MediaExtractor.setDataSource(pfd.fileDescriptor)` — uri 가 content://, file://, http(s):// 무엇이든 PFD 받아서 처리.
- `try/finally` 가 ex.release() 보장 — MediaExtractor 의 native 자원 leak 방지.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/AudioDecoder.kt lines=46-77
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
```

**분석 (Level 2)**
- `selectAudioTrack(ex)` 가 첫 audio track 만 선택 — multi-track 미디어 (multi-lingual 자막 등) 의 비-기본 track 무시.
- `MediaCodec.createDecoderByType(mime)`: mime 으로 decoder lookup. mp3/aac/opus 등 자동.
- `codec.configure(format, null, null, 0)`: surface 없음 (오디오만), crypto 없음, flag 없음.
- `pcmShorts = ArrayList<Short>(16 * 1024)`: 초기 capacity 16K. **위험 hotspot**: 30분 mp3 = 16k 샘플/sec × 1800 sec × 2 ch ≈ 58 M shorts ≈ 116 MB. ArrayList 가 Short boxing → 각 element JVM heap 24 bytes (Short class + reference) → 1.4 GB. OOM 거의 확실. INDEX §4.3 hotspot #1.
- `dequeueInputBuffer(10_000)` 10 ms timeout — 짧음. busy loop 회피.
- `BUFFER_FLAG_END_OF_STREAM`: 디코더에게 입력 끝 알림. 디코더가 출력 queue 에 EOS 표시.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/AudioDecoder.kt lines=78-101
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
```

**분석 (Level 2)**
- `dequeueOutputBuffer` 반환:
  - ≥ 0: 유효 buffer index
  - `INFO_TRY_AGAIN_LATER` (= -1): timeout
  - `INFO_OUTPUT_FORMAT_CHANGED` (= -2): format change (보통 시작 시 1회)
  - `INFO_OUTPUT_BUFFERS_CHANGED` (= -3): deprecated, API 21+ 자동
  - 코드는 `< 0` 통째로 break — 정상이지만 OUTPUT_FORMAT_CHANGED 누락 시 첫 buffer 가 누락될 수 있음 (다행히 codec 가 일반적으로 1st sample 까지 dequeue 함).
- `outBuf.order(LITTLE_ENDIAN).asShortBuffer()` 후 `.add()`: 한 element 씩 boxing — 사실 가장 큰 OOM 위험 지점. `pcmShorts.add(shortBuf.get())` 가 매번 `Short.valueOf(int)` boxing + GC pressure.
- `releaseOutputBuffer(outputIndex, false)`: render=false (오디오 surface 없음).
- `try { while ... } finally { codec.stop/release }`: codec native 자원 leak 방지 — OK.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/AudioDecoder.kt lines=102-141
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
```

**분석 (Level 2)**
- mono 변환:
  - 1ch: 직접 `Short.toInt() / 32768f` → [-1.0, 1.0).
  - multi-ch: 평균 fold to mono. `sum / srcChannels` 가 int division — 32-bit overflow 가능 (srcChannels ≤ 8 이면 안전). 평균 후 / 32768 — Float32 정밀도 충분.
- `srcRate == TARGET_RATE` 분기: 16k 음원 (마이크 직접 녹음 일부 포맷) 은 리샘플 skip.
- `resampleLinear`: 단순 linear interpolation. 음질 측면 sinc 보다 떨어지나 STT 정확도 영향 미미. `input.size.toLong() * dstRate / srcRate` 가 32-bit overflow 방지용 toLong cast — input.size 가 큰 경우 (50M 샘플 × 16k) overflow 가능, 단 long 이라 OK.
- `min(srcIdx + 1, input.size - 1)` 가 마지막 sample 의 b 가 out-of-range 인 케이스 가드.

**짚어볼 점 / Watch-outs**
- **OOM hotspot**: `ArrayList<Short>` boxing 으로 30분+ 음원에서 OOM 거의 확실. **즉시 fix**: `ShortBuffer` 또는 `IntArray` 누적 → 30분 mp3 에서 ~58 MB Int16 buffer = 안전.
- 모노 변환 후 `pcmShorts` 가 GC 대상이지만 누적 동안 메모리 peak.
- linear resample 의 품질 — 22 kHz → 16 kHz 같은 small ratio 면 aliasing 미미. 44.1 kHz → 16 kHz 처럼 ratio 2.75 면 high-frequency aliasing 가능. STT 영향 미미 (Whisper 가 robust) 지만 음악·신호 분석에는 부적합.
- HW decoder fallback: `createDecoderByType(mime)` 가 SW decoder 만 반환할 수 있음 (특히 일부 codec). 성능 저하 가능.
- `info.presentationTimeUs` 미사용 — segment 시작 시간 매핑은 whisper 측 timestamp 가 책임. 정상.

**파일 수준 평가**
- 강점:
  - MediaExtractor + MediaCodec 표준 패턴 — Android best practice.
  - try/finally 자원 정리 견고.
  - SAF URI 까지 PFD 통해 처리.
- 위험:
  - **OOM**: ArrayList<Short> 가 boxing 으로 큰 음원에서 메모리 폭증.
  - resample 품질 (낮은 우선순위).
- 개선 권고:
  1. **즉시**:
     ```kotlin
     val pcmShorts = java.nio.ShortBuffer.allocate(initialCapacity = 256 * 1024)
     // 또는 ByteArrayOutputStream 에 raw bytes 누적 후 한번에 ShortBuffer wrap
     ```
  2. resample 알고리즘 `oboe` 또는 `libsamplerate` 도입 검토 (Phase 3).
  3. 청크 단위 처리 — 30 초 단위로 디코드 → Whisper → segments 누적 → 메모리 peak 1/N 절감.
- 재읽기 권고 레벨: 3

**Cross-references**
- 호출처: `whisper/Pipeline.kt:50`
- iOS counterpart: `ios/.../Core/AudioDecoder.swift` — AVFoundation 의 `AVAssetReader` 사용. native 측 메모리 효율 더 좋음 (Float32 buffer 직접 emit).
