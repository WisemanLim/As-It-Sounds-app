# As It Sounds — 모바일 테스트 시나리오

> 🌐 **한국어** · [English](SCENARIOS.en.md)

Rust 번역 코어를 공유하는 모바일 컴패니언(iOS + Android)의 테스트 계획입니다.
모든 테스트는 시뮬레이터/에뮬레이터 형태로 실행됩니다. 결과는 `test/results/` 아래에 저장됩니다.

| 환경 | 도구 |
| :-- | :-- |
| Rust core | `cargo test --workspace --release` |
| iOS | iOS Simulator (`xcrun simctl`) + `xcodebuild` |
| Android | Android Emulator (`emulator` + `adb`) + Gradle |

---

## S1 — Rust 코어 (공유 `translit.rs`)

| ID | 시나리오 | 예상 결과 |
| :-- | :-- | :-- |
| S1.1 | `cargo test --workspace --release` | 회귀 테스트 30개 통과 |
| S1.2 | FFI 인터페이스 컴파일 (`ais-ffi`) | UniFFI 바인딩 빌드 성공, `version()` 값 비어 있지 않음 |

## S2 — iOS 시뮬레이터

| ID | 시나리오 | 예상 결과 |
| :-- | :-- | :-- |
| S2.1 | `aarch64-apple-ios-sim` 용 앱 빌드 (xcframework + whisper slice) | 빌드 성공, `.app` 생성됨 |
| S2.2 | 시뮬레이터 부팅 + 설치 + 실행 | 앱 실행됨, 콜드 스타트 시 크래시 없음 |
| S2.3 | 디바이스에서 FFI 왕복 테스트 | `transliterate()` 데모가 한글 출력을 렌더링함 |
| S2.4 | UI 스모크 — 메인 화면 렌더링 | 홈 뷰가 보이고, 빨간 오류 화면 없음 |

## S3 — Android 에뮬레이터

| ID | 시나리오 | 예상 결과 |
| :-- | :-- | :-- |
| S3.1 | cargo-ndk로 `.so × ABI` 빌드 + `:app` 어셈블 | `assembleDebug` 성공 |
| S3.2 | 에뮬레이터 부팅 + APK 설치 + 실행 | 앱 실행됨, 크래시 없음 |
| S3.3 | JNA를 통한 FFI 왕복 테스트 | `transliterate()` 데모가 한글 출력을 렌더링함 |
| S3.4 | UI 스모크 — 메인 화면 렌더링 | Compose 홈 화면이 보임 |

---

## 통과 기준

S1–S3의 모든 시나리오가 통과해야 합니다. 실패 시: 로그 수집, 원인 파악, 수정 또는 문서화 후 재실행합니다.
전체 스위트가 통과되었을 때만 결과를 `test/results/RESULTS.md`에 기록합니다.
