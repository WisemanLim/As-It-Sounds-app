# `WhisperEngine.mm` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../INDEX.md)

**파일 요약**
- 역할: Obj-C++ wrapper 가 Swift 에서 whisper.cpp 를 안전하게 사용하도록 캡슐화. Swift `Sendable` 강제 회피하면서 thread-confined 안에 가둠.
- LOC: 105
- exports (Obj-C class): `AisSegment`, `WhisperEngine` (`+systemInfo`, `-initWithModelPath:useGpu:`, `-transcribePCM:language:nThreads:onError:`)
- 의존: `<whisper.h>` (whisper.xcframework), `<os/log.h>`
- 부수효과: `whisper_context*` heap alloc/free
- 동시성: Swift 측에서 `Task.detached` 안에서만 사용 — 본 클래스 자체는 non-thread-safe (instance method 동시 호출 시 미정의)

---

```objc path=ios/AsItSounds/Core/WhisperEngine.mm lines=1-20
#import "WhisperEngine.h"
#import <os/log.h>

#import "whisper.h"

#pragma mark - AisSegment

@implementation AisSegment
- (instancetype)initWithStartMs:(int64_t)startMs
                          endMs:(int64_t)endMs
                           text:(NSString *)text {
    self = [super init];
    if (!self) return nil;
    _startMs = startMs;
    _endMs = endMs;
    _text = [text copy];
    return self;
}
@end
```

**분석 (Level 2)**
- `#import "whisper.h"` — xcframework 의 헤더. `module.modulemap` 통해 Xcode auto-import.
- `AisSegment` 가 Obj-C 표준 클래스 — `@property (nonatomic, readonly)` 3개 (header 에 선언). designated initializer 패턴.
- `[text copy]`: caller 가 mutable NSString 던질 경우 immutable 사본 보관 — defensive. NSString → NSString copy 는 retain 동등 (이미 immutable) — overhead 없음.
- `self = [super init]; if (!self) return nil;` Obj-C 표준 idiom. `[super init]` 가 nil 반환할 일은 거의 없으나 의무.

```objc path=ios/AsItSounds/Core/WhisperEngine.mm lines=21-45
#pragma mark - WhisperEngine

@implementation WhisperEngine {
    struct whisper_context *_ctx;
}

+ (NSString *)systemInfo {
    const char *info = whisper_print_system_info();
    return info ? [NSString stringWithUTF8String:info] : @"";
}

- (nullable instancetype)initWithModelPath:(NSString *)path useGpu:(BOOL)useGpu {
    self = [super init];
    if (!self) return nil;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu ? true : false;

    _ctx = whisper_init_from_file_with_params(path.UTF8String, cparams);
    if (!_ctx) {
        os_log_error(OS_LOG_DEFAULT, "whisper_init_from_file_with_params failed for %{public}@", path);
        return nil;
    }
    return self;
}
```

**분석 (Level 2)**
- `@implementation WhisperEngine { struct whisper_context *_ctx; }`: instance variable 직접 선언 (modern Obj-C 의 ivar block). C struct 포인터를 ARC 가 관리 안 함 → dealloc 에서 수동 free 의무.
- `+systemInfo` class method — 정적 진단 정보. `NSString stringWithUTF8String:` 가 nil-safe 가드 후 빈 string 반환.
- `initWithModelPath:useGpu:`:
  - `whisper_context_default_params()` 가 stack POD struct 반환.
  - `cparams.use_gpu = useGpu ? true : false`: `BOOL` (signed char) → C `bool` 명시 변환. 단순 cast 도 OK 지만 명시가 컴파일러 경고 회피.
  - `whisper_init_from_file_with_params` 실패 시 `_ctx == NULL` → return nil. ARC 가 self 메모리 자동 정리.
  - `path.UTF8String` — NSString 의 NUL-terminated C string. 임시 buffer, 함수 호출 동안만 유효 → whisper_init 가 sync 함수라 안전.
  - `os_log_error` + `%{public}@` 포맷 — Apple 의 unified logging. `%{public}` 가 path 가 redacted 안되도록 명시 (debug builds).

```objc path=ios/AsItSounds/Core/WhisperEngine.mm lines=47-77
- (NSArray<AisSegment *> *)transcribePCM:(NSData *)pcm
                                language:(nullable NSString *)language
                                nThreads:(int32_t)nThreads
                                  onError:(NSError * _Nullable * _Nullable)error {
    if (!_ctx) {
        if (error) *error = [NSError errorWithDomain:@"AisWhisper" code:1
                                            userInfo:@{NSLocalizedDescriptionKey: @"context not initialized"}];
        return @[];
    }

    const float *samples = (const float *)pcm.bytes;
    int n_samples = (int)(pcm.length / sizeof(float));
    if (n_samples <= 0) {
        if (error) *error = [NSError errorWithDomain:@"AisWhisper" code:2
                                            userInfo:@{NSLocalizedDescriptionKey: @"empty PCM"}];
        return @[];
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_special    = false;
    params.print_timestamps = true;
    params.translate        = false;
    params.detect_language  = (language == nil);
    params.language         = language ? language.UTF8String : "auto";
    params.n_threads        = nThreads > 0 ? nThreads : 4;
    params.no_context       = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
```

**분석 (Level 2)**
- Obj-C 의 NSError convention: `(NSError * _Nullable * _Nullable)error` 이중 포인터 — caller 가 `&error` 전달, callee 가 *error = new error 패턴. Swift 측은 `try` 와 자동 매핑.
- **`const float *samples = (const float *)pcm.bytes`**: NSData 의 `bytes` 가 `const void *` 반환. float* reinterpret cast. **alignment 가정**: PCM 이 Float32 boundary 정렬돼야 함. NSData 가 default 16-byte alignment 보장 → Float32 안전.
- `pcm.length / sizeof(float)`: float 개수 계산. NSUInteger → int cast — 2 GB 이상 audio 면 truncation, 현실 비안전.
- `language ? language.UTF8String : "auto"`: NSString → C string. **C string lifetime**: `language.UTF8String` 가 임시 autoreleased — 본 method 의 `@autoreleasepool` (호출자가 wrap 했다고 가정) 안에서 유효. `whisper_full` 가 동기 호출이라 안전. 만약 비동기 callback 으로 전달했다면 dangling pointer 위험.
- 8개 `whisper_full_params` 설정: Android JNI 의 동일 패턴과 1:1 매핑 — Pipeline 패리티 보장.

```objc path=ios/AsItSounds/Core/WhisperEngine.mm lines=78-104
    int rc = whisper_full(_ctx, params, samples, n_samples);
    if (rc != 0) {
        if (error) *error = [NSError errorWithDomain:@"AisWhisper" code:rc
                                            userInfo:@{NSLocalizedDescriptionKey:
                                                          [NSString stringWithFormat:@"whisper_full rc=%d", rc]}];
        return @[];
    }

    int n = whisper_full_n_segments(_ctx);
    NSMutableArray<AisSegment *> *out = [NSMutableArray arrayWithCapacity:n];
    for (int i = 0; i < n; i++) {
        int64_t t0 = whisper_full_get_segment_t0(_ctx, i) * 10; // 10ms units
        int64_t t1 = whisper_full_get_segment_t1(_ctx, i) * 10;
        const char *txt = whisper_full_get_segment_text(_ctx, i);
        NSString *s = txt ? [NSString stringWithUTF8String:txt] : @"";
        [out addObject:[[AisSegment alloc] initWithStartMs:t0 endMs:t1 text:s]];
    }
    return out;
}

- (void)dealloc {
    if (_ctx) {
        whisper_free(_ctx);
        _ctx = NULL;
    }
}

@end
```

**분석 (Level 2)**
- `whisper_full(...)` blocking — 음원 길이 비례 시간 소요. caller (Swift Task.detached) 가 격리.
- `whisper_full_get_segment_t0/t1` 10 ms 단위 → `*10` 으로 ms 변환. Android JNI 와 동일 패턴 — 시간 단위 협업 일관.
- `[NSMutableArray arrayWithCapacity:n]`: 초기 capacity hint. 내부 자료구조 over-alloc 회피.
- `dealloc`:
  - `_ctx` null 가드 → `whisper_free(NULL)` 호출 방지.
  - `_ctx = NULL` 후속 dealloc 호출 (없을 거지만) 가드.
- ARC 가 `dealloc` 호출 — 명시적 release 안 함. Swift 가 holding 풀면 자동 trigger.

**짚어볼 점 / Watch-outs**
- **alignment**: `(const float *)pcm.bytes` 가 정렬 가정. NSData 가 16-byte alignment 보장하지만 NSData 가 다른 NSData subclass (immutable subclass) 일 때는 미보장. 현실에서는 Swift 의 `Data(bytes:)` 가 항상 정렬됨 — 안전. INDEX §4.3 hotspot #4 에 보수적으로 기록.
- `n_samples = (int)(pcm.length / sizeof(float))`: 2 GB+ 음원 (사실상 1.5억 sample) 시 int overflow. 비현실적이라 무관.
- `whisper_full` 호출 도중 cancellation 없음 — Swift Task cancel 무시. Android JNI 동일 문제.
- `language.UTF8String` 의 lifetime 가정이 `whisper_full` sync 호출에 의존 — 안전.
- `os_log_error` 가 path 를 `%{public}@` 로 명시 — privacy classification. App Store 심사 영향 없음.
- `NSError code:rc` 의 rc 가 whisper.cpp 의 error code (1, 2, 3...). 의미가 외부 문서 없음 — Swift 측 catch 가 code 분기 어려움. message 만 보고 처리.

**파일 수준 평가**
- 강점:
  - 105 LOC 로 RAII (init/dealloc) + 에러 채널 + parameter 8종 + segment 변환 완비.
  - Swift Strict Concurrency 와 우아하게 공존 — Sendable 미선언 클래스를 Task.detached 안에 가둠.
  - 메모리 leak 가드 (dealloc 의 `_ctx = NULL`).
- 위험:
  - alignment 가정 (이론적, 현실 무관).
  - cancellation 미지원.
  - error code semantics 미문서화.
- 개선 권고:
  1. `pcm.bytes` 사용 전 `NSAssert((uintptr_t)pcm.bytes % alignof(float) == 0, ...)` 가드 (DEBUG 빌드만).
  2. `whisper_full_params.encoder_begin_callback` 활용한 가벼운 cancel.
  3. `WhisperEngineErrorDomain` + enum 으로 error code 시맨틱 노출.
- 재읽기 권고 레벨: 3

**Cross-references**
- 호출처: `ios/.../Core/Pipeline.swift:52-58` (`PipelineRunner.run` 의 Task.detached 안)
- 헤더 선언: `ios/AsItSounds/Core/WhisperEngine.h:1-29`
- bridging: `ios/AsItSounds/AsItSounds-Bridging-Header.h:1`
- Android counterpart: `android/.../cpp/ais_whisper_jni.cpp` — JNI 의 4 외부 함수가 본 Obj-C class 의 4 method 와 1:1 매핑. JNI 는 stateless C 함수, Obj-C 는 instance method 로 model handle 보유 (RAII).
