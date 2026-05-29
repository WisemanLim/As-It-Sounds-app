# `Pipeline.kt` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../../../../INDEX.md)

**파일 요약**
- 역할: 단일 진입점 `Pipeline.run(...) : Flow<PipelineEvent>` — 디코드 → ASR → 음차 → 번역 → DB 저장 직렬 실행.
- LOC: 120
- exports: `Pipeline` (object), `PipelineSegment` (data), `PipelineEvent` (sealed)
- 의존 (internal): `whisper/{WhisperNative, AudioDecoder, Translator}`, `db/{AppDatabase, SegmentEntity}`, `core/ais_ffi.kt` (`transliterate`)
- 부수효과: DB write, 네이티브 자원 alloc/free
- 동시성: `flowOn(Dispatchers.IO)` 단일 스레드 컨텍스트

---

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt lines=1-12
package com.asitsounds.android.whisper

import android.content.Context
import android.net.Uri
import com.asitsounds.android.db.AppDatabase
import com.asitsounds.android.db.SegmentEntity
import com.asitsounds.core.transliterate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
```

**분석 (Level 2)**
- `com.asitsounds.core.transliterate` — UniFFI 가 생성한 Kotlin top-level 함수. UDL 의 `string transliterate(string text)` 를 `fun transliterate(text: String): String` 로 매핑.
- `UUID.randomUUID()` 사용 — segment row ID 생성에 분당 수십~수백 번 호출. Java 의 `SecureRandom` 기반이라 무거움. 큰 transcript 면 사실상 critical path.
- `flowOn(Dispatchers.IO)` — flow builder 안의 코드를 IO 디스패처에서 실행. emit 은 collector 디스패처로 자동 hop.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt lines=14-30
data class PipelineSegment(
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val textEn: String,
    val textHangul: String,
    val textKo: String = "",
)

sealed interface PipelineEvent {
    data object Decoding : PipelineEvent
    data object Transcribing : PipelineEvent
    data object Transliterating : PipelineEvent
    data class Translating(val current: Int, val total: Int) : PipelineEvent
    data class Done(val segments: List<PipelineSegment>) : PipelineEvent
    data class Failed(val message: String) : PipelineEvent
}
```

**분석 (Level 2)**
- `PipelineSegment` 6 필드 — 음원 시간 + 3-column 텍스트. `textKo = ""` 디폴트는 Translator off 시 의도.
- `sealed interface PipelineEvent` + `data object` (Kotlin 1.9+): exhaustive when 가능. 5 case + Translating 의 progress 1 case = 6 상태.
- iOS counterpart (`PipelineRunner.stage`) 는 enum `PipelineStage` 5 case + segments array — Kotlin 은 segments 가 `Done` payload, iOS 는 `@Observable` 속성 — 둘이 시맨틱 다름.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt lines=32-46
/**
 * Foreground-only pipeline runner mirroring the iOS counterpart. The whole
 * decode + transcribe + translit chain runs on [Dispatchers.IO] in one shot;
 * Phase 1B keeps it simple — Foreground Service + WorkManager for long
 * recordings is a later step.
 */
object Pipeline {

    fun run(
        context: Context,
        modelPath: String,
        audioUri: Uri,
        language: String? = null,
        translator: Translator = DisabledTranslator(),
        persistFileId: String? = null,
    ): Flow<PipelineEvent> = flow {
        emit(PipelineEvent.Decoding)
```

**분석 (Level 2)**
- `object Pipeline { fun run(...) }`: stateless singleton. dependency injection 없음 — `AppDatabase.get(context)` 가 내부 lookup. 테스트 시 mock 어려움.
- 디폴트 인자 3종 (`language=null`, `translator=DisabledTranslator()`, `persistFileId=null`) 가 caller 의 4 시나리오 (auto / lang+translate / lang+no-translate / persist on/off) 자연스럽게 노출.
- `flow { ... }`: cold flow builder. `collect` 호출마다 본문 재실행 — 동일 인자로 두 번 collect 시 두 번 실행. caller (UI) 가 한 번만 collect 함.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt lines=48-72
        val pcm = try {
            AudioDecoder.decodeToPCM16k(context, audioUri)
        } catch (t: Throwable) {
            emit(PipelineEvent.Failed("decode: ${t.message ?: t::class.simpleName}"))
            return@flow
        }

        emit(PipelineEvent.Transcribing)
        val handle = WhisperNative.nativeInitFromFile(modelPath, useGpu = true)
        if (handle == 0L) {
            emit(PipelineEvent.Failed("whisper_init_from_file failed"))
            return@flow
        }

        val raw = try {
            WhisperNative.transcribe(handle, pcm, language, nThreads = 4)
        } catch (t: Throwable) {
            WhisperNative.nativeFree(handle)
            emit(PipelineEvent.Failed("transcribe: ${t.message ?: t::class.simpleName}"))
            return@flow
        } finally {
            WhisperNative.nativeFree(handle)
        }
```

**분석 (Level 2)**
- 디코드 try/catch: 실패 시 Failed emit + early return. fail-fast.
- **`handle` resource leak 가드**: try/catch 안에서 finally 가 nativeFree 호출, catch 안에도 명시 호출. **이중 호출 위험**: catch 에서 nativeFree → finally 에서 또 nativeFree → JNI 측은 `handle == 0` 가드 없으나 우리는 caller 측에서 handle 변수 자체를 `0L` 로 안 바꿈 → 같은 ctx 를 두 번 free → double-free → undefined behavior (SIGSEGV 가능).
- **Bug**: try/catch/finally 의 양쪽에서 free 호출. 정상 흐름에서도 finally 가 한 번, catch 는 fail 시만. 정상 흐름: 1회 ✓. 실패 흐름: catch 1회 + finally 1회 = **2회** ❌.
- 수정: catch 에서 free 제거 (finally 가 모두 처리), 또는 catch 에서 free 후 finally 의 free 를 `handle = 0L` 로 가드 (val 라 reassign 불가 → var 로 변경 필요).

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt lines=73-99
        emit(PipelineEvent.Transliterating)
        val transliterated = raw.mapIndexed { i, seg ->
            val trimmed = seg.text.trim()
            PipelineSegment(
                idx = i,
                startMs = seg.startMs,
                endMs = seg.endMs,
                textEn = trimmed,
                textHangul = transliterate(trimmed),
            )
        }

        val out: List<PipelineSegment> = if (translator is DisabledTranslator) {
            transliterated
        } else {
            val acc = transliterated.toMutableList()
            for (i in acc.indices) {
                emit(PipelineEvent.Translating(current = i + 1, total = acc.size))
                val ko = try {
                    translator.translate(acc[i].textEn, "en", "ko")
                } catch (t: Throwable) {
                    "(translate failed)"
                }
                acc[i] = acc[i].copy(textKo = ko)
            }
            acc
        }
```

**분석 (Level 2)**
- `mapIndexed` + `transliterate(trimmed)` 가 직렬. UniFFI Kotlin 호출 한번당 JNA 통한 FFI hop — segment 당 ~10–100 µs. 100 segment 면 10 ms — 무관.
- **번역 직렬화**: `for (i in acc.indices) ... await ... copy(textKo = ...)`. concurrency 1. 100 segment × MyMemory ~200 ms/call = 20 초. iOS 측도 동일. INDEX §4.6 의 개선 권고 #2 가 가리키는 hotspot.
- `translator is DisabledTranslator` instanceof 체크: 모든 인터페이스 impl 이 같은 패키지 안에 있어 OK. 미래에 다른 모듈에서 새 `Translator` 구현 추가 시 깜빡 잊고 분기 빠질 가능성 (Translator.translate 가 empty string 반환만 하면 무관, 그러나 명확성 떨어짐).

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt lines=101-120
        if (persistFileId != null) {
            val dao = AppDatabase.get(context).segmentDao()
            dao.clearForFile(persistFileId)
            dao.insertAll(out.map {
                SegmentEntity(
                    id = UUID.randomUUID().toString(),
                    fileId = persistFileId,
                    idx = it.idx,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    textEn = it.textEn,
                    textHangul = it.textHangul,
                    textKo = it.textKo.ifEmpty { null },
                )
            })
        }

        emit(PipelineEvent.Done(out))
    }.flowOn(Dispatchers.IO)
}
```

**분석 (Level 2)**
- `dao.clearForFile(persistFileId)` + `dao.insertAll(...)`: 명시적 replace 패턴. 같은 fileId 에 대해 idempotent — 재실행해도 같은 결과.
- DB 호출이 두 번 (clear + insertAll) — 트랜잭션 안 묶임. 중간에 실패하면 segments 가 비어있는 상태로 남음. SegmentDao.insertAll 내부에 `beginTransaction` 있음 (review §AppDatabase.kt) — clearForFile 까지 묶이지는 않음.
- `textKo.ifEmpty { null }`: empty string ("(translate failed)" 가 들어가도 .ifEmpty 는 ""만 trigger, 즉 "(translate failed)" 는 그대로 저장됨) — 의도된 동작인지 확인 필요.
- `emit(Done(out))` 후 flow builder 종료 → collector 의 `.collect { ... }` for-each 도 종료.

**짚어볼 점 / Watch-outs**
- **Critical: double-free risk (line 63-71)**: catch 에서 nativeFree + finally 에서 nativeFree → 같은 ctx 2회 해제. JNI 측 `whisper_free` 에 null/double-free 가드가 없으면 crash.
- 번역 직렬 → 대용량 음원에서 사용자 체감 느림.
- `transliterate` (UniFFI) 가 panic 가능성 없는지 가정 — translit.rs 는 모든 입력 graceful. 그러나 향후 알고리즘 변경 시 panic 가능 → catch 가 없으면 Pipeline 통째 abort.
- `WhisperNative.nativeInitFromFile` 실패 시 단순 "whisper_init_from_file failed" 메시지 — 디스크 부족 / 파일 손상 / 모델 형식 잘못 등 원인 unknown. 향후 errno 반환 검토.
- `AppDatabase.get(context).segmentDao()` 가 main thread 에서 호출되지는 않으나, 본 flow 가 IO 디스패처에 있으므로 안전.

**파일 수준 평가**
- 강점:
  - 단일 진입점 + sealed event 패턴 — UI 측 collect 가 매우 깔끔.
  - cold flow 라 lifecycle aware (collector 종료 시 자동 cancel).
  - try/catch 가 각 단계 분리 — 어디서 실패했는지 메시지로 보고.
- 위험:
  - **Double-free** (line 63-71) — Critical fix 필요.
  - 번역 sequential — 사용자 체감 성능 hotspot.
  - DB clear+insert 트랜잭션 미보장.
- 개선 권고:
  1. **즉시**: catch 블록의 `WhisperNative.nativeFree(handle)` 제거 (finally 만 남김):
     ```kotlin
     val raw = try {
         WhisperNative.transcribe(...)
     } catch (t: Throwable) {
         emit(PipelineEvent.Failed("transcribe: ${t.message}"))
         return@flow  // finally 가 free 처리
     } finally {
         WhisperNative.nativeFree(handle)
     }
     ```
  2. 번역 `flatMapMerge(concurrency = 4)` 또는 `coroutineScope { launch ... }` 로 동시 호출.
  3. clear + insertAll 을 single transaction 으로 묶기.
- 재읽기 권고 레벨: 3 (Level 3 면 KCSP suspending fun + retry 패턴 까지 검토 가치)

**Cross-references**
- caller: `android/.../ui/PipelineScreen.kt` (collect)
- callee: `whisper/AudioDecoder.kt`, `whisper/WhisperNative.kt`, `whisper/Translator.kt`, `db/AppDatabase.kt`, `core/ais_ffi.kt`
- iOS counterpart: `ios/.../Core/Pipeline.swift:42-95` (`PipelineRunner.run`) — Swift 측은 Task.detached 단일 블록으로 동등 흐름, double-free 동일 패턴 없음 (Obj-C dealloc 이 일회).
