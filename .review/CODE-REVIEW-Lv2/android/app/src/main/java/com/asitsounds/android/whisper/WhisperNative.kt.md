# `WhisperNative.kt` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../../../../INDEX.md)

**파일 요약**
- 역할: JNI native 함수 4종에 대한 Kotlin facade. `Object[]` flatten 결과를 `List<WhisperSegment>` 로 재조립.
- LOC: 51

---

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/WhisperNative.kt lines=1-13
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
```

**분석 (Level 2)**
- `data class WhisperSegment(...)`: equals/hashCode/copy 자동. Kotlin 의 표준 immutable value class.
- `object WhisperNative`: singleton (JVM 의 static 동등). state 가 native 측 handle 이므로 stateless wrapper 적합.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/WhisperNative.kt lines=14-23
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
```

**분석 (Level 2)**
- `init { System.loadLibrary("ais_whisper") }`: object 첫 사용 시점에 `libais_whisper.so` 로드. lazy class init → 앱 시작 시 load 안 함, 첫 Whisper 사용 시 ~100 ms 지연 발생.
- `@JvmStatic external fun ...`:
  - `external` — Kotlin 의 native method 마커. Java 의 `native` 키워드 동등.
  - `@JvmStatic` — JVM 측에서 static method 로 노출 → JNI 가 `jclass clazz` 인자 받음 (vs instance method 의 `jobject thiz`).
- `nativeFullTranscribe` 의 `Array<Any>?` 반환: JVM 의 `Object[]`. nullable → JNI 측에서 `nullptr` 반환 가능.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/WhisperNative.kt lines=25-50
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
```

**분석 (Level 2)**
- `nativeFullTranscribe(...) ?: return emptyList()`: null 시 fail-fast 빈 list. caller (Pipeline) 가 빈 segments 로 진행 — fail 로그는 JNI 측에서 이미 logcat 에 출력됨.
- `ArrayList<WhisperSegment>(raw.size / 3)`: 초기 용량 정확히 알고 alloc → 내부 array resize 회피.
- `while (i + 2 < raw.size)`: index 3씩 증가하면서 `i + 2` 까지 안전하게 접근. 마지막 trio 손상된 경우 (`raw.size % 3 != 0`) 자동 skip → 부분 결과라도 보존.
- `raw[i] as Long`: Kotlin smart cast — `Array<Any>` 의 element 가 `Long` 인스턴스라 가정. JNI 가 잘못된 type 박으면 `ClassCastException`. JNI 측에서 항상 `Long`/`String` 만 박으므로 안전.

**짚어볼 점 / Watch-outs**
- `System.loadLibrary("ais_whisper")` 가 실패 (예: jniLibs 경로에 .so 부재) 시 `UnsatisfiedLinkError`. init 블록 안의 throw 는 object 자체 초기화 실패로 이어져 **첫 호출 시점에 ExceptionInInitializerError**. 사용자 친화적 fallback 없음.
- `handle == 0L` 시 caller 가 `transcribe(0L, ...)` 호출하면 JNI 측 가드 (line 11) 에서 null 반환 → empty list 로 빠짐. 안전하지만 caller 가 fail 케이스 구분 어려움.
- `raw[i] as Long` 캐스트 자체는 inline JVM bytecode `CHECKCAST` 한 줄 — overhead 없음.

**파일 수준 평가**
- 강점:
  - 51 LOC 로 native 호출 + 결과 재조립 깔끔.
  - 초기 capacity 명시 + flatten 패턴 으로 GC 압력 최소화.
- 위험:
  - `System.loadLibrary` 실패 시 fallback 없음.
  - handle 미검증.
- 개선 권고:
  1. `transcribe` 가 `Result<List<WhisperSegment>>` 반환하도록 변경 — caller 가 fail vs empty 구분.
  2. init 블록의 `loadLibrary` 를 try/catch + UI 측 graceful error 표시 검토.
- 재읽기 권고 레벨: 2

**Cross-references**
- JNI 구현: `android/.../cpp/ais_whisper_jni.cpp:18-130`
- 호출처: `android/.../whisper/Pipeline.kt:57,64,70`
- iOS counterpart: `ios/.../Core/WhisperEngine.{h,mm}` — Obj-C `WhisperEngine` 클래스가 instance method 로 model handle 보유 (Swift dealloc 자동 cleanup). Kotlin 측은 명시적 handle Long 운반 — caller (Pipeline) 책임.
