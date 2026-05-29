# `core/Cargo.toml` — 코드 리뷰 / Code Review

> 리뷰 레벨 / Review Level: 2
> [← INDEX.md](../INDEX.md)

**파일 요약 / File Summary**
- 역할 / Role: Rust 워크스페이스 정의 (members: `ais-translit`, `ais-ffi`). 공통 의존성·프로파일 일원화.
- 진입점에서의 거리 / Distance from entrypoint: 1 (빌드 스크립트가 직접 참조)
- 라인 수 / LOC: 21
- 주요 export / Exports: 워크스페이스 메타 — Rust 빌드 도구만 소비
- 의존 모듈 / Depends on (internal): —
- 사용된 외부 패키지 / External packages used: `once_cell` (workspace.dependencies), `uniffi` 0.28 (no-default-features)
- 부수효과 / Side effects: 빌드 산출물 `core/target/` 생성
- 동시성 모델 / Concurrency model: N/A
- 테스트 커버리지 흔적 / Test coverage signal: `cargo test` 가 두 crate 의 `#[cfg(test)]` 호출

---

```toml path=core/Cargo.toml lines=1-9
[workspace]
resolver = "2"
members = [
    "crates/ais-translit",
    "crates/ais-ffi",
]

[workspace.package]
version = "0.1.0"
```

**분석 / Analysis (Level 2)**
- `resolver = "2"` 명시 — Cargo 의 feature unification 충돌을 줄여주는 modern resolver. 워크스페이스 + crate-type 혼합(`staticlib`/`cdylib`/`rlib`) 환경에서 feature flag 충돌이 발생하면 안 됨. Rust 1.51+ 지원이지만 명시 권장.
- members 2개. 한 crate(`ais-translit`)는 pure rlib, 다른(`ais-ffi`)은 cdylib+staticlib — **dynamic + static** 동시 빌드는 cargo 의 feature unification 에서 의외의 dep 활성화로 이어질 수 있는 패턴이라 resolver=2 가 사실상 필수.
- `[workspace.package]` 의 `version = "0.1.0"`: 두 crate 가 `version.workspace = true` 로 상속 → 단일 source of truth. 출시 시 한 곳만 bump.

```toml path=core/Cargo.toml lines=10-15
edition = "2021"
authors = ["As It Sounds"]
license = "MIT"
repository = "https://github.com/WisemanLim/As-It-Sounds"

[workspace.dependencies]
```

**분석 / Analysis (Level 2)**
- `edition = "2021"`: 2024 미사용. 데스크탑 코어 (`translit.rs`)가 2021 이므로 맞춤. 향후 edition 2024 마이그레이션 시 `Result` 의 `?` 동작 변경, `unsafe extern "C"` 명세화 등에 영향.
- `license = "MIT"` 워크스페이스 단위 선언 — 두 crate 가 `license.workspace = true` 상속. 단, **워크스페이스 루트에 LICENSE 파일이 부재** (in-depth-review 보고 §2.1 확인) — Cargo metadata 와 실제 파일 불일치, 출시 전 LICENSE 파일 동봉 필수.

```toml path=core/Cargo.toml lines=16-21
once_cell = "1"
uniffi = { version = "0.28", default-features = false }

[profile.release]
lto = true
codegen-units = 1
strip = "debuginfo"
opt-level = "z"
```

**분석 / Analysis (Level 2)**
- `[workspace.dependencies]` 의 `uniffi = { default-features = false }`: 기본 feature `cli` 가 꺼짐 → `ais-ffi` crate 가 자체 `features = ["cli"]` 로 opt-in 해서 `bin/uniffi-bindgen.rs` 만 켤 수 있게 함. 운영 cdylib 빌드 시 cli feature 코드 자동 배제 → 바이너리 약 1 MB 절감.
- `[profile.release]` 4 옵션은 **모바일 임베드** 시나리오용 최적화 4종 콤보:
  - `lto = true` (full LTO) — cross-crate inlining + dead code 제거. 빌드 시간 ~3× 증가 대신 .so/.a 사이즈 20–30% 감소.
  - `codegen-units = 1` — 병렬 컴파일 단위 1 → 컴파일 느림(약 2×) 대신 단일 코드 그래프 → LTO 효과 극대화.
  - `strip = "debuginfo"` — `.debug_*` 섹션 제거 (심볼은 유지). xcframework/.so 사이즈 추가 절감.
  - `opt-level = "z"` — "min size" 우선 (LLVM `-Oz`). 일반 `"3"` 대비 속도 5–10% 떨어질 수 있으나 모바일 IPA/APK 크기 우선이 적절한 트레이드오프.

**짚어볼 점 / Watch-outs**
- **`[workspace.dependencies]` 의 `uniffi` 가 사실상 미사용** — `ais-ffi/Cargo.toml` 만 uniffi 직접 의존. 워크스페이스 공유 효과 없음. 두 번째 crate 가 uniffi 도입할 때만 의미가 생김 → 현재는 `[workspace.dependencies]` 블록을 비우고 `ais-ffi/Cargo.toml` 에 inline 해도 동등.
- `repository` URL 이 `WisemanLim/As-It-Sounds` 인데 실제 저장소 권한·존재 미검증 — 출시 시 crates.io publish 안 하더라도 정확한 URL 권장.

**파일 수준 평가 / File-Level Verdict**
- 강점 / Strengths:
  - resolver=2 + 모바일-최적화 release profile 콤보가 견고.
  - 두 crate 가 워크스페이스 메타데이터를 상속하여 버전·라이선스 단일화.
- 위험 / Risks:
  - `workspace.dependencies` 의 `uniffi` 가 dead — 미세하지만 maintenance noise.
  - 워크스페이스 루트에 LICENSE 파일 부재.
- 개선 권고 / Recommendations:
  1. 워크스페이스 루트에 `LICENSE` (MIT 전문) 추가.
  2. `[workspace.dependencies].once_cell` 도 `ais-translit/Cargo.toml` 안에 inline 후 블록 삭제 검토 (현재 단일 소비자).
  3. `[profile.release]` 에 `panic = "abort"` 추가 검토 — `unwind` 테이블 제거로 모바일 사이즈 추가 절감.
- 재읽기 권고 레벨 / Suggested re-read level: 2 (Level 3+ 은 옵션 트레이드오프만 추가 인사이트, Level 2 가 적정)
