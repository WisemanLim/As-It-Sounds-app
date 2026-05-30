# As It Sounds — Mobile Test Scenarios

> 🌐 [한국어](SCENARIOS.md) · **English**

Test plan for the mobile companion (iOS + Android) sharing the Rust transliteration core.
All tests run as simulator/emulator form. Results saved under `test/results/`.

| Env | Tool |
| :-- | :-- |
| Rust core | `cargo test --workspace --release` |
| iOS | iOS Simulator (`xcrun simctl`) + `xcodebuild` |
| Android | Android Emulator (`emulator` + `adb`) + Gradle |

---

## S1 — Rust core (shared `translit.rs`)

| ID | Scenario | Expected |
| :-- | :-- | :-- |
| S1.1 | `cargo test --workspace --release` | 30 regression tests pass |
| S1.2 | FFI surface compiles (`ais-ffi`) | UniFFI bindings build, `version()` non-empty |

## S2 — iOS Simulator

| ID | Scenario | Expected |
| :-- | :-- | :-- |
| S2.1 | Build app for `aarch64-apple-ios-sim` (xcframework + whisper slice) | Build succeeds, `.app` produced |
| S2.2 | Boot simulator + install + launch | App launches, no crash on cold start |
| S2.3 | FFI round-trip on device | `transliterate()` demo renders Hangul output |
| S2.4 | UI smoke — main screen renders | Home view visible, no red error screen |

## S3 — Android Emulator

| ID | Scenario | Expected |
| :-- | :-- | :-- |
| S3.1 | Build `.so × ABI` via cargo-ndk + assemble `:app` | `assembleDebug` succeeds |
| S3.2 | Boot emulator + install APK + launch | App launches, no crash |
| S3.3 | FFI round-trip via JNA | `transliterate()` demo renders Hangul output |
| S3.4 | UI smoke — main screen renders | Compose home visible |

---

## Pass criteria

All S1–S3 scenarios pass. On failure: capture log, root-cause, fix or document, re-run.
Results written to `test/results/RESULTS.md` only when the full suite is green.
