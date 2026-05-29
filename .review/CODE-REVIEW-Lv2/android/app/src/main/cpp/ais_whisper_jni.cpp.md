# `android/app/src/main/cpp/ais_whisper_jni.cpp` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../INDEX.md)

**파일 요약**
- 역할: Kotlin `WhisperNative` 의 4 외부 함수에 매핑되는 JNI 구현.
- LOC: 132
- exports (JNI mangled):
  - `Java_com_asitsounds_android_whisper_WhisperNative_nativeVersion`
  - `_nativeInitFromFile`
  - `_nativeFree`
  - `_nativeFullTranscribe`
- 의존: `<jni.h>`, `<android/log.h>`, `<whisper.h>`
- 부수효과: heap alloc (whisper_context), JVM JNI object alloc (NewObject)
- 동시성: caller (Pipeline.run) 가 `Dispatchers.IO` 단일 스레드에서 직렬 호출 — 본 코드는 thread-safe 가정 안 함

---

```cpp path=android/app/src/main/cpp/ais_whisper_jni.cpp lines=1-21
// JNI bridge for whisper.cpp on Android.
//   - nativeVersion()             : verifies linking, returns whisper_print_system_info
//   - nativeInitFromFile(path,gpu): loads a GGUF model, returns opaque handle
//   - nativeFree(handle)          : releases context
//   - nativeFullTranscribe(...)   : runs whisper_full on 16 kHz mono Float32 PCM,
//                                   returns Segment[] {startMs, endMs, text}

#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOG_TAG "AisWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeVersion(JNIEnv *env, jclass /*clazz*/) {
    const char *v = whisper_print_system_info();
    return env->NewStringUTF(v ? v : "");
}
```

**분석 (Level 2)**
- 헤더 주석이 4 함수 계약 명시 — JNI 측에서 Kotlin 측 시그니처 추적 가능하게 함.
- `extern "C" { ... }`: C++ name mangling 비활성. JNI 가 정확한 `Java_<package>_<class>_<method>` 심볼명을 요구하므로 필수.
- `Java_..._nativeVersion`:
  - `JNIEnv *env`: thread-local JNI 환경. JVM 이 호출 시 자동 전달.
  - `jclass /*clazz*/`: `@JvmStatic` static method 의 owning class. 사용 안 하므로 주석 처리.
  - `whisper_print_system_info()` — whisper.cpp 가 빌드 시 사용한 SIMD/Metal 등 정보 (예: `system_info: n_threads = 1 / 8 | AVX2 = 0 ...`). 작동 검증 (smoke) 용.
  - `env->NewStringUTF(v ? v : "")` — UTF-8 → modified UTF-8 변환 후 JVM heap 의 jstring 생성. nullptr 가드.

```cpp path=android/app/src/main/cpp/ais_whisper_jni.cpp lines=23-44
JNIEXPORT jlong JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeInitFromFile(
        JNIEnv *env, jclass /*clazz*/, jstring modelPath, jboolean useGpu) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu ? true : false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    LOGI("whisper context initialised: %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeFree(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    whisper_free(reinterpret_cast<struct whisper_context *>(handle));
}
```

**분석 (Level 2)**
- `GetStringUTFChars(modelPath, nullptr)` ↔ `ReleaseStringUTFChars`: JNI 의 짝맞춤 contract. Get 이 alloc/copy 한 native string 을 Release 가 free. 두 번째 인자 (`isCopy`) 가 nullptr — copy 여부 알 필요 없음.
- `whisper_context_default_params()` 가 stack-allocated POD struct 반환. `use_gpu` 만 override.
- `reinterpret_cast<jlong>(ctx)` 패턴: native pointer 를 64-bit Long 으로 캐스팅하여 Kotlin 으로 전달. **32-bit ABI (armeabi-v7a) 에서도 `void*` 가 32-bit 이지만 jlong 은 64-bit** — 잘림 없음. 다만 `reinterpret_cast<whisper_context *>(handle)` 의 역변환은 컴파일러가 jlong → 32-bit 절단 시 sign-extension OK (포인터는 32-bit 영역 안에 있으므로).
- `nativeFree`: `handle == 0` 가드. Kotlin 측에서 `nativeInitFromFile` 실패 시 0 반환받고 `nativeFree(0)` 호출해도 안전.

```cpp path=android/app/src/main/cpp/ais_whisper_jni.cpp lines=49-90
JNIEXPORT jobjectArray JNICALL
Java_com_asitsounds_android_whisper_WhisperNative_nativeFullTranscribe(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jfloatArray pcm,
        jstring language,    // nullable
        jint nThreads) {

    if (handle == 0 || pcm == nullptr) {
        LOGE("nativeFullTranscribe: handle=0 or null pcm");
        return nullptr;
    }
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(handle);

    jsize n_samples = env->GetArrayLength(pcm);
    if (n_samples <= 0) {
        LOGE("nativeFullTranscribe: empty PCM");
        return nullptr;
    }

    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_special    = false;
    params.print_timestamps = true;
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
    params.n_threads        = nThreads > 0 ? nThreads : 4;
```

**분석 (Level 2)**
- 입력 검증: handle, pcm null/empty 가드. fail 시 `nullptr` 반환 → Kotlin 측 `?.let` 분기.
- `GetFloatArrayElements(pcm, nullptr)` — JNI 가 JVM heap 의 float[] 을 native 메모리로 **pinning** (또는 복사). 후속 `ReleaseFloatArrayElements` 짝맞춤 필수.
- `whisper_full_params` 의 8개 설정:
  - `print_*` 4개 false — stdout 로깅 비활성 (Android logcat 으로 전송 안 됨, 무의미한 stdout 소비만)
  - `print_timestamps = true` — segment 별 t0/t1 추적
  - `translate = false` — Whisper 의 자체 translation 기능 비활성 (영어 외 입력 → 영어 출력 막음). 우리는 원어 그대로 받아 별도 Translator 로 한국어 변환.
  - `no_context = true` — 이전 segment 의 context 를 다음 inference 에 전파 안 함. 음원이 잘려있는 케이스 회피, 정확도 약간 떨어질 수 있으나 안전.
  - `suppress_blank = true` — 빈 segment 출력 차단.
  - `n_threads` — Pipeline.run 이 4 전달. ggml CPU 백엔드 thread pool 크기.

```cpp path=android/app/src/main/cpp/ais_whisper_jni.cpp lines=92-104
    std::string lang_str;
    if (language != nullptr) {
        const char *lang = env->GetStringUTFChars(language, nullptr);
        lang_str = lang ? lang : "auto";
        env->ReleaseStringUTFChars(language, lang);
    } else {
        lang_str = "auto";
    }
    params.language       = lang_str.c_str();
    params.detect_language = lang_str == "auto";

    int rc = whisper_full(ctx, params, samples, (int)n_samples);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
```

**분석 (Level 2)**
- `std::string lang_str` 가 RAII 로 `params.language` 의 lifetime 보장. `params.language = lang_str.c_str()` 가 가리키는 buffer 가 `whisper_full` 호출 동안 유효해야 함 — 함수 scope 안에서 OK.
- `detect_language = lang_str == "auto"`: auto 모드 enable. 명시 언어 (예: `"en"`) 면 false.
- `whisper_full(...)`: blocking call, segment 들이 ctx 내부에 저장됨. 수십 초 ~ 분 단위 소요. caller (Pipeline.run) 의 `Dispatchers.IO` 가 이 스레드를 점유.
- `ReleaseFloatArrayElements(..., JNI_ABORT)`:
  - `JNI_ABORT` mode — 만약 GetFloatArrayElements 가 *복사* 본을 줬다면 변경사항을 JVM 으로 반영 안 하고 release. *pinning* 일 경우 pin 해제만.
  - 우리는 PCM 을 read-only 로만 썼으므로 abort 모드가 정확 (`JNI_COMMIT` 또는 `0` 은 불필요한 copy-back).

```cpp path=android/app/src/main/cpp/ais_whisper_jni.cpp lines=106-130
    if (rc != 0) {
        LOGE("whisper_full rc=%d", rc);
        return nullptr;
    }

    int n_segments = whisper_full_n_segments(ctx);
    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(n_segments * 3, objCls, nullptr);

    jclass longCls = env->FindClass("java/lang/Long");
    jmethodID longCtor = env->GetMethodID(longCls, "<init>", "(J)V");

    for (int i = 0; i < n_segments; i++) {
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);

        jobject jStart = env->NewObject(longCls, longCtor, (jlong)t0);
        jobject jEnd   = env->NewObject(longCls, longCtor, (jlong)t1);
        jstring jText  = env->NewStringUTF(text ? text : "");

        env->SetObjectArrayElement(result, i * 3 + 0, jStart);
        env->SetObjectArrayElement(result, i * 3 + 1, jEnd);
        env->SetObjectArrayElement(result, i * 3 + 2, jText);

        env->DeleteLocalRef(jStart);
        env->DeleteLocalRef(jEnd);
        env->DeleteLocalRef(jText);
    }
    return result;
}
```

**분석 (Level 2)**
- `whisper_full_get_segment_t0/t1` 가 10 ms 단위 정수 반환 (`*10` → ms). whisper.cpp 의 unusual 시간 단위.
- **plate flatten 패턴**: 별도 Segment 클래스를 JNI 로 생성하지 않고 `Object[]` 길이 `n_segments * 3` 으로 `[Long start, Long end, String text, Long start, ..., String text]` 로 flatten. 이유:
  - 새 클래스 정의 + reflection 코스트 회피
  - Kotlin 측에서 `Array<Any>.size / 3` 으로 재조립 (WhisperNative.transcribe 의 helper)
- `FindClass("java/lang/Long")` + `GetMethodID(longCls, "<init>", "(J)V")`: Long(long) 생성자 lookup. JNI 의 보일러플레이트.
- **메모리 누수 방지**: `DeleteLocalRef` 가 매 iteration 후 호출 — JNI 의 local reference table 이 기본 512 slot. n_segments × 3 이 512 초과 시 (대용량 transcript, ~170 segments+) 누락된 ref 가 누수되어 `JNI ERROR (...): local reference table overflow` 가능. 본 코드는 매 iter 정리 → 안전.
- `objCls`, `longCls` 두 jclass 도 사실 local ref — 함수 끝나면 자동 정리. 함수 scope short 이면 OK.

**짚어볼 점 / Watch-outs**
- **unsafe pointer 캐스트** `reinterpret_cast<whisper_context *>(handle)`: Kotlin 이 전달한 jlong 이 진짜 우리의 ctx 인지 검증 안 함. 누군가 임의 long 을 `nativeFree` 에 전달하면 임의 free → SIGSEGV. Kotlin 측에서 handle 변수가 internal 인지 검증 권고.
- `whisper_full` 호출이 cancellation 무시 — Kotlin coroutine 이 cancel 되어도 JNI 호출은 끝까지 동작. 큰 음원에서 사용자 cancel 응답 지연 발생.
- `n_segments * 3` 이 INT_MAX/3 (~715M) 초과 시 정의되지 않은 동작 — 현실에는 불가, 이론적 노트.
- `params.language = lang_str.c_str()` 의 lifetime 가정이 미세하게 위험 — `lang_str` 이 SSO (Small String Optimization) buffer 일 때 어떤 STL 구현은 move/realloc 시 ptr 무효화. `params` 가 함수 scope 라 OK.
- `LOGI` 의 `%p` printf 포맷 — Android NDK 의 `__android_log_print` 가 표준 printf 와 동등. 안전.

**파일 수준 평가**
- 강점:
  - 132 LOC 로 4 JNI 함수 + 자원 짝맞춤 완비 — JNI best practice.
  - DeleteLocalRef 매 iteration 호출로 local ref overflow 방지.
  - JNI_ABORT 명시로 불필요 copy-back 회피.
- 위험:
  - handle 유효성 미검증 — 정상 사용에서는 무관하나 적대적 caller 면 crash.
  - whisper_full cancellation 미지원.
- 개선 권고:
  1. `params.suppress_non_speech_tokens = true` 추가 검토 — "Music playing" 같은 노이즈 토큰 제거.
  2. cancellation flag (`std::atomic<bool>` static) + `whisper_full_params.encoder_begin_callback` 으로 가벼운 cancel.
  3. handle magic number prefix 적용 (e.g. `((uintptr_t)ctx) ^ MAGIC`) — 적대적 caller 가드.
- 재읽기 권고 레벨: 3

**Cross-references**
- 호출처: `android/.../whisper/WhisperNative.kt:14-23` (Kotlin external fun 4종)
- API ref: whisper.cpp `include/whisper.h`
