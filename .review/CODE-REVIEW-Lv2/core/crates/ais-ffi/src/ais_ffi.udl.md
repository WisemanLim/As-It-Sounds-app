# `core/crates/ais-ffi/src/ais_ffi.udl` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../INDEX.md)

**파일 요약**
- 역할: UniFFI 인터페이스 정의 언어 (UDL). Swift + Kotlin 바인딩 생성기가 본 파일을 1차 source-of-truth 로 사용.
- LOC: 8
- exports: `namespace ais_ffi { string transliterate(string text); string version(); }`

---

```idl path=core/crates/ais-ffi/src/ais_ffi.udl lines=1-8
namespace ais_ffi {
    /// Transliterate a sentence in any supported language into Korean Hangul phonetics.
    /// Returns the input unchanged when the dominant script is Hangul / CJK / Arabic / unknown.
    string transliterate(string text);

    /// Library version string (Cargo package version).
    string version();
};
```

**분석 (Level 2)**
- `namespace ais_ffi { ... }`: UniFFI 의 top-level — Swift 측에서는 module `AisCoreFFI` (uniffi.toml 의 `module_name` 매핑) 안의 자유 함수, Kotlin 측에서는 package `com.asitsounds.core` 의 top-level 함수로 변환됨.
- `string transliterate(string text);`: UDL 의 `string` 은 Rust 의 owned `String` ↔ Swift `String` ↔ Kotlin `String` 으로 자동 매핑. heap alloc 한 번 (FFI 경계).
- `///` 형태의 doc 주석 — UDL 파서가 이를 무시하지 않고 생성 Swift/Kotlin 파일의 KDoc/DocC 주석으로 전파.
- 시그니처 2개 — 본 프로젝트의 모든 cross-language 호출은 이 2 라인으로 끝남. 미래 Whisper 자체를 Rust 로 옮긴다면 본 UDL 가 segmented stream signature 로 확장될 곳.

**짚어볼 점 / Watch-outs**
- `[ByRef] string text` 같은 borrow 어노테이션 미사용 — 모든 String 인자가 owned 로 cross-FFI. 음차는 segment 당 1 호출이라 무관, 라이브 STT (50–100 호출/초) 가 본격 가동되면 borrow 검토 가치.
- `version()` 이 매번 새 String alloc 반환 — 호출 빈도가 낮아 무관. 메모리 sensitive 한 곳이면 Rust 측에서 `&'static str` lifetime 가져오는 별도 wrapper 가능.
- 에러 처리 부재 — `transliterate` 가 panic 시 FFI 경계 정의되지 않음. 다행히 데스크탑 `translit.rs` 가 모든 입력에 대해 무한 loop 또는 panic 불가 (script detect fallthrough pass-through). 향후 강한 입력 검증 함수 추가 시 `[Throws=AisError]` 어노테이션 권고.

**파일 수준 평가**
- 강점: 8 줄로 양 플랫폼 인터페이스 합의. 변경 비용 최소.
- 위험: 에러 채널 없음 — 미래 함수에서 깜빡 잊고 panic 노출 위험.
- 개선 권고:
  1. 향후 함수 추가 시 panic 가능성이 있으면 `interface AisError { string message; };` + `[Throws=AisError]` 패턴 사용.
  2. version() 을 `[ByRef]` 또는 캐시드 static 으로 검토 (마이크로 최적화).
- 재읽기 권고 레벨: 2
