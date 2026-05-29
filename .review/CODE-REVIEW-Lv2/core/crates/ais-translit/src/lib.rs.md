# `core/crates/ais-translit/src/lib.rs` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../INDEX.md)

**파일 요약 / File Summary**
- 역할: 데스크탑 워크스페이스의 `translit.rs` (1,713 LOC, 핵심 IP) 를 `#[path]` 로 inline 하고 재-export.
- 거리: 1
- LOC: 33 (외부 참조 `translit.rs` 의 1,713 LOC 는 별개)
- exports: `pub use translit_upstream::transliterate;`
- 의존 (internal): 외부 `NGL(Go,Rust,...)/As-It-Sounds/src-tauri/src/core/translit.rs`
- 외부 패키지: `once_cell` (transitive via translit.rs)
- 부수효과: 없음
- 테스트: 3 단위 테스트 (`english_basic`, `empty_input`, `passthrough_korean`)

---

```rust path=core/crates/ais-translit/src/lib.rs lines=1-12
//! `ais-translit` — re-exports the desktop project's transliteration engine without copying.
//!
//! The single authoritative source is the desktop repo's [`translit.rs`]. We pull it in via
//! `#[path]` so the iOS + Android apps consume the exact same code path the desktop and web
//! versions use. Update the constant below if the desktop repo moves on disk.
//!
//! [`translit.rs`]: ../../../../../../NGL(Go,Rust,...)/As-It-Sounds/src-tauri/src/core/translit.rs

#[path = "../../../../../../NGL(Go,Rust,...)/As-It-Sounds/src-tauri/src/core/translit.rs"]
mod translit_upstream;

pub use translit_upstream::transliterate;
```

**분석 / Analysis (Level 2)**
- `//!` 모듈 doc 주석 — `cargo doc` 산출에 표시. 외부 `translit.rs` 가 진짜 진실이라는 사실을 코드 안에서 명문화.
- `#[path = "..."]`: Rust 의 mod resolution override 어트리뷰트. `mod translit_upstream;` 이 normally `./translit_upstream.rs` 또는 `./translit_upstream/mod.rs` 를 찾는데, `#[path]` 가 그 경로를 강제 치환.
- **상대 경로 깊이 6개 `../` 의 의미**:
  - 본 파일 `core/crates/ais-translit/src/lib.rs` 위치 기준
  - `..` 6 회 = `As-It-Sounds-app/` 의 부모인 `Apps(Android,iOS,...)/` 의 부모인 `Developer/` 까지 올라간 뒤
  - `NGL(Go,Rust,...)/As-It-Sounds/src-tauri/src/core/translit.rs` 로 진입.
- `pub use translit_upstream::transliterate;` — 외부 `pub fn transliterate(sentence: &str) -> String` 만 노출. translit.rs 의 private helper (`compose`, `Vowel` enum 등) 는 외부에서 안 보임.
- 본 crate 자체 코드 라인은 사실상 **8 줄** (주석·테스트 제외). 그러나 빌드 시 `translit.rs` 1,713 LOC + `Lazy<HashMap>` 정적 테이블이 compile unit 에 함께 포함됨.

```rust path=core/crates/ais-translit/src/lib.rs lines=14-33
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn english_basic() {
        let out = transliterate("As it sounds, it works.");
        assert!(!out.is_empty(), "empty output");
    }

    #[test]
    fn empty_input() {
        assert_eq!(transliterate(""), "");
    }

    #[test]
    fn passthrough_korean() {
        let s = "안녕하세요";
        assert_eq!(transliterate(s), s);
    }
}
```

**분석 / Analysis (Level 2)**
- `#[cfg(test)] mod tests` — Rust 의 표준 단위 테스트 위치. `cargo test` 시에만 컴파일됨.
- 3 케이스:
  1. **`english_basic`**: 출력이 empty 가 아닌지만 검증. 정확한 한글 문자열을 hard-code 안한 이유 — 음차 규칙 미세 조정이 빈번해서 fixture lock 하면 CI 빨간불이 매번. 회귀 정확도 검증은 외부 `translit.rs` 의 자체 `#[cfg(test)]` (origin 측 30 fixtures) 에 위임.
  2. **`empty_input`**: 빈 문자열 → 빈 문자열. boundary 조건 OK.
  3. **`passthrough_korean`**: 한글 입력은 그대로 통과 (script detect 결과). 데스크탑 README §3.1 의 동작 약속 (Hangul/CJK/Arabic pass-through) 을 본 crate 측에서도 가드.

**짚어볼 점 / Watch-outs**
- `english_basic` 의 assertion 이 약함 — 진짜 회귀 fixture 는 외부 30개에 의존. **본 crate 가 외부 파일 read 권한을 잃으면 (예: 다른 머신, CI 디스크) `cargo build` 자체가 실패** — 외부 의존이 미러됨에 적절히 노출.
- `#[path]` 경로 안의 괄호 `(...)` 와 점 `...` 가 일부 도구 (rust-analyzer, IDE 인덱서) 에서 인덱싱 실패할 수 있음. 다행히 `rustc` 자체는 문자열 path 그대로 처리.
- 외부 `translit.rs` 가 신규 의존 (예: `regex`) 을 추가하면 본 crate 의 `Cargo.toml` 에도 dep 추가 필요 — 컴파일 에러로 즉시 드러나지만 명문화된 가드 (예: `ci/check-deps.sh`) 가 있으면 좋음.

**파일 수준 평가**
- 강점:
  - 8 줄로 1,713 LOC 의 IP 를 이식 — `#[path]` 패턴 모범 사례.
  - 3 테스트가 boundary + happy path 동시 커버.
- 위험:
  - 외부 파일 disk 의존성이 메타데이터에 안 잡힘.
  - 회귀 정확도 검증을 outside crate 에 위임 — fork 시 깨질 위험.
- 개선 권고:
  1. CI 에 `[ -f "../../../../../../NGL.../translit.rs" ] || exit 1` 가드 step 추가.
  2. 외부 30 fixture 의 일부를 본 crate 의 `tests/regression.rs` 에 복제 (cross-machine 안전성).
- 재읽기 권고 레벨: 3 (외부 translit.rs 자체 알고리즘 이해는 Level 3 깊이 필요)

**Cross-references**
- 호출처: `core/crates/ais-ffi/src/lib.rs:4` (`ais_translit::transliterate(&text)`)
- 외부 진실: `~/Developer/NGL(Go,Rust,...)/As-It-Sounds/src-tauri/src/core/translit.rs:1510` (`pub fn transliterate(sentence: &str) -> String`)
