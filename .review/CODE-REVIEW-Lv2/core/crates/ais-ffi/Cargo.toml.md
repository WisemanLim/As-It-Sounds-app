# `core/crates/ais-ffi/Cargo.toml` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../INDEX.md)

**파일 요약**
- 역할: UniFFI 노출 crate. cdylib(.so/.dylib) + staticlib(.a) + rlib + bin (uniffi-bindgen) 4 산출.
- LOC: 28
- 외부 패키지: `ais-translit` (path), `uniffi` (runtime + build), feature 게이트 `cli`

---

```toml path=core/crates/ais-ffi/Cargo.toml lines=1-12
[package]
name = "ais-ffi"
version.workspace = true
edition.workspace = true
license.workspace = true
description = "UniFFI bindings — exposes ais-translit to Swift (iOS) and Kotlin (Android)."

[lib]
name = "ais_ffi"
crate-type = ["staticlib", "cdylib", "rlib"]

[dependencies]
```

**분석 (Level 2)**
- `crate-type = ["staticlib", "cdylib", "rlib"]` 3종 산출:
  - **`staticlib`** → `libais_ffi.a` — iOS 에서 xcframework 로 묶을 때 정적 링크 (사이즈 + 코드 사이닝 안정).
  - **`cdylib`** → `libais_ffi.so` (Android) / `libais_ffi.dylib` (macOS dev) — JNA 가 동적 로드 (Kotlin 측은 `System.loadLibrary("ais_ffi")` 패턴).
  - **`rlib`** — Rust 코드끼리만 link 할 때 사용 (테스트 + uniffi-bindgen bin 이 본 crate 를 dep 로 가질 때).
- 빌드 시간 cost: 3종 동시 emit 으로 단일 compile unit 1회만 — 동일 .rs → 3 산출로 펼치는 거라 ~10% 추가만 발생.

```toml path=core/crates/ais-ffi/Cargo.toml lines=13-22
ais-translit = { path = "../ais-translit" }
uniffi = { workspace = true }

[build-dependencies]
uniffi = { workspace = true, features = ["build"] }

[features]
default = []
cli = ["uniffi/cli"]
```

**분석 (Level 2)**
- `ais-translit = { path = "../ais-translit" }`: 워크스페이스 내부 path 의존 — 버전 다운로드 X. `cargo metadata` 가 path 의존을 인식하므로 워크스페이스 빌드 그래프 정상.
- `[build-dependencies] uniffi = { features = ["build"] }`: build.rs 가 사용. 런타임 runtime dep (`[dependencies] uniffi`) 와 분리 — build-only 코드가 런타임 바이너리에 안 들어감.
- `[features].default = []`: 기본 feature 0개 — cdylib/staticlib 빌드 시 cli 자동 미포함. 의도된 minimal.
- `cli = ["uniffi/cli"]`: feature flag 가 다른 crate (`uniffi`) 의 feature 를 켜는 패턴 = "feature dependency". `cargo build --features cli` 시에만 uniffi 의 cli 의존성 (clap, askama 등) 끌어옴.

```toml path=core/crates/ais-ffi/Cargo.toml lines=23-28
[[bin]]
name = "uniffi-bindgen"
path = "src/bin/uniffi-bindgen.rs"
required-features = ["cli"]
```

**분석 (Level 2)**
- `[[bin]]` 으로 lib crate 안에 bin target 추가 — `cargo run --bin uniffi-bindgen` 으로 실행 가능.
- `required-features = ["cli"]`: bin 이 cli feature 없이는 컴파일 시도조차 안 함. `cargo build` 단독 호출 시 무시되어 cdylib/staticlib 만 emit — 모바일 산출 워크플로우와 깨끗하게 분리.
- bin 의 본문 (`src/bin/uniffi-bindgen.rs`) 은 단 한 줄 (`uniffi::uniffi_bindgen_main()`) — uniffi crate 가 제공하는 entrypoint 호출.

**짚어볼 점 / Watch-outs**
- `[dependencies].uniffi` 가 `workspace = true` → 워크스페이스 루트의 `default-features = false` 를 상속 → runtime 측에 cli feature 가 누락됨. 정상.
- `[build-dependencies].uniffi` 도 동일하게 `workspace = true` + `features = ["build"]` 추가만 — runtime 과 build 가 다른 feature 셋으로 두 번 컴파일됨. 빌드 시간 약간 증가하나 격리 측면 OK.
- bin 의 `required-features` 로 cli 만 명시 → uniffi 의 cli feature 가 미래에 rename 되면 silent break. uniffi 0.28 LTS 가 안정적이라 단기 위험 낮음.

**파일 수준 평가**
- 강점:
  - feature gate + build-deps 분리 + 3 crate-type 동시 산출 — modern Rust FFI 모범.
  - bin target 의 `required-features` 로 모바일 빌드 산출물에서 깨끗하게 제외.
- 위험:
  - 워크스페이스 dep 상속 사슬이 길어 변경 시 영향 추적 어려움.
- 개선 권고:
  1. `crate-type = ["rlib"]` 만으로 cargo test 가 정상 작동하는지 점검 — 안 그러면 staticlib/cdylib 가 항상 빌드되어 테스트 느림.
  2. `Cargo.lock` 의 `uniffi` 두 entry (runtime + build) feature 차이 시각화 검토.
- 재읽기 권고 레벨: 2
