# `core/crates/ais-translit/Cargo.toml` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../INDEX.md)

**파일 요약 / File Summary**
- 역할: 음차 rlib crate 메타. 데스크탑 `translit.rs` 를 `#[path]` 로 재사용함을 캡슐화.
- 거리: 1 (ais-ffi 가 의존)
- LOC: 13
- exports: `ais_translit::transliterate(&str) -> String` (re-export from `mod translit_upstream`)
- 의존: `once_cell` (transitive via `translit.rs` 의 `Lazy`)
- 부수효과: 없음 (순수 함수)
- 동시성: 호출자 측 책임 — 함수 자체는 thread-safe (read-only 정적 테이블)

---

```toml path=core/crates/ais-translit/Cargo.toml lines=1-13
[package]
name = "ais-translit"
version.workspace = true
edition.workspace = true
license.workspace = true
description = "Multilingual phonetic transliteration to Korean Hangul. Re-exports the desktop crate's translit module via #[path]."

[lib]
name = "ais_translit"
crate-type = ["rlib"]

[dependencies]
once_cell = { workspace = true }
```

**분석 / Analysis (Level 2)**
- `version.workspace = true` 3개 라인: 워크스페이스 루트의 `[workspace.package]` 에서 값을 가져옴 — 단일 source of truth. 출시 시 워크스페이스 루트 한 곳만 bump.
- `crate-type = ["rlib"]`: Rust-only static lib. 다른 crate 가 `path = "../ais-translit"` 으로 의존할 때 inline LTO 가능. cdylib/staticlib 안 쓰는 이유 — 외부 FFI 표면은 `ais-ffi` 가 담당, 본 crate 는 pure Rust 도메인.
- `[lib].name = "ais_translit"`: package name 의 하이픈을 underscore 로 매핑 — Rust 의 module 이름 규약 (하이픈 불가) 명시.
- `description` 이 메타데이터로 한 줄 — `cargo metadata` 또는 `crates.io` publish 시 표시.
- `once_cell = { workspace = true }`: 워크스페이스 루트의 `[workspace.dependencies].once_cell = "1"` 을 inherit. 직접 사용은 외부 `translit.rs` 안 (`use once_cell::sync::Lazy;`).

**짚어볼 점 / Watch-outs**
- `#[path]` 로 외부 파일을 import 하는 사실이 메타데이터에 안 보임 — `cargo metadata` 로 패키지 의존성 추적 도구는 이 crate 가 외부 파일에 의존한다는 사실을 모름. `description` 에 명시한 것은 다행. CI 가 외부 `translit.rs` 변경에도 본 crate 빌드를 트리거하도록 추가 watch path 설정 권고.
- `Cargo.toml` 의 `description` 끝에 마침표 없음 — crates.io publish 시 minor lint.

**파일 수준 평가**
- 강점: 13 줄로 핵심 의도(데스크탑 코어 재사용) + 워크스페이스 정합성 동시 충족.
- 위험: `#[path]` 외부 의존이 메타데이터에 안 잡혀 도구 추적 어려움.
- 개선 권고:
  1. README 또는 본 Cargo.toml metadata 에 `[package.metadata]` 사용자 정의 키로 외부 경로 명시.
  2. 향후 음차 fixture 를 `ais-translit/tests/` 에 별도 회귀 테스트로 노출 검토.
- 재읽기 권고 레벨: 2
