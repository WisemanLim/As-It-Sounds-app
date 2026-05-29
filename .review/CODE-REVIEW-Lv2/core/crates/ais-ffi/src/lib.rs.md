# `core/crates/ais-ffi/src/lib.rs` — 코드 리뷰 / Code Review

> 리뷰 레벨 / Review Level: 2
> [← INDEX.md](../../../../INDEX.md)

**파일 요약 / File Summary**
- 역할 / Role: UniFFI scaffolding 포함 + 두 wrapper 함수(`transliterate`, `version`) Rust 측 구현.
- 진입점에서의 거리: 0 (FFI cdylib/staticlib 의 본체)
- LOC: 11
- 주요 export: `pub fn transliterate(String) -> String`, `pub fn version() -> String`
- 의존: `ais-translit`, `uniffi` (build-time scaffolding macro)
- 부수효과: scaffolding macro 가 `extern "C"` C ABI 함수들을 자동 생성 (`ffi_ais_ffi_transliterate` 등) — 산출 .so/.a 에 export
- 동시성 모델: stateless (호출마다 새 String)

---

```rust path=core/crates/ais-ffi/src/lib.rs lines=1-12
//! `ais-ffi` — UniFFI surface for Swift + Kotlin.

pub fn transliterate(text: String) -> String {
    ais_translit::transliterate(&text)
}

pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

uniffi::include_scaffolding!("ais_ffi");
```

**분석 / Analysis (Level 2)**
- 본 파일이 하는 일은 **3 가지** 만:
  1. `transliterate(text: String) -> String` — UDL 가 선언한 시그니처에 맞춰 Rust 구현 제공. 내부에서 `ais_translit::transliterate(&text)` 위임. `&str` 빌림은 새 alloc 없이 그대로 전달.
  2. `version() -> String` — `env!("CARGO_PKG_VERSION")` 매크로는 컴파일 시점에 `Cargo.toml` 의 version 문자열 (`"0.1.0"`) 로 치환. `.to_string()` 으로 heap String 반환.
  3. `uniffi::include_scaffolding!("ais_ffi");` — build.rs 가 생성한 `target/.../ais_ffi.uniffi.rs` 를 본 lib.rs 안에 inline include. 그 안에 `extern "C"` 진입점, error 변환, type lifting/lowering 코드가 자동 생성됨.
- Rust 측 시그니처가 UDL 과 정확히 일치해야 함 — 불일치 시 컴파일 에러 (uniffi 의 sanity check 가 `include_scaffolding!` 매크로 안에서 동작).

**짚어볼 점 / Watch-outs**
- `transliterate(text: String)` 가 `&str` 가 아닌 `String` 을 받음 — UDL 가 `string` 타입을 명시했고, UniFFI 의 기본 매핑이 *owned* `String`. FFI 경계에서 한 번 alloc 발생 (Swift/Kotlin → Rust 방향). hot path 면 UDL 측에서 `[ByRef] string text` 로 변경 검토할 수 있으나, 음차는 segment 단위라 무관.
- `env!("CARGO_PKG_VERSION")` 은 빌드 시점에 평가됨 — git short hash 등 동적 정보는 못 넣음. 그게 필요하면 `build.rs` 에서 `cargo:rustc-env=GIT_HASH=...` 출력 후 `env!("GIT_HASH")` 로 사용.
- `include_scaffolding!` 매크로는 build.rs 가 한 번도 안 돌면 fail. CI 가 `cargo build` 보다 먼저 `cargo build --features cli` 같은 도구 빌드를 띄울 때 race 가능성은 있으나 cargo 가 build.rs 우선 실행하므로 일반 빌드에서는 안전.

**파일 수준 평가 / File-Level Verdict**
- 강점:
  - 11 줄로 두 플랫폼 FFI 완성 — UniFFI 의 minimalism 을 살림.
  - wrapper 가 thin pass-through 라 회귀 검증이 거의 필요 없음 (외부 `ais_translit` 만 확인).
- 위험:
  - `version()` 이 빌드 시점 상수 — 런타임 hot-patch 같은 시나리오 미지원 (현재 불필요).
- 개선 권고:
  1. `version()` 반환에 git short hash 추가 검토 (`format!("{} ({})", env!("CARGO_PKG_VERSION"), env!("GIT_HASH"))`) — 사용자 빌드 식별 용이.
  2. 향후 함수 추가 시 위 패턴 유지 — wrapper 한 줄, UDL 한 줄.
- 재읽기 권고 레벨: 2

**Cross-references**
- 호출처: `core/bindings/{swift,kotlin}/...` (UniFFI 생성). Swift/Kotlin 측에서 `transliterate(text:)` 호출 시 → 본 함수 → `ais_translit::transliterate` → 데스크탑 `translit.rs`
- UDL: `core/crates/ais-ffi/src/ais_ffi.udl:1-8`
