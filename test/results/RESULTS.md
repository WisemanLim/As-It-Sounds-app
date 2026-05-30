# As It Sounds — Mobile Test Results

Date: 2026-05-30 · Host: macOS (Darwin 25.4) · Toolchain: Rust 1.95.0, Xcode 26.5, Android SDK (android-37, NDK)

**Status: ALL GREEN ✅**

| ID | Scenario | Result | Evidence |
| :-- | :-- | :-- | :-- |
| S1.1 | Rust core `cargo test --workspace --release` | ✅ PASS | 30 passed / 0 failed |
| S1.2 | `ais-ffi` UniFFI surface builds | ✅ PASS | xcframework + jniLibs produced |
| S2.1 | iOS build (`iphonesimulator`, iPhone 17) | ✅ PASS | `** BUILD SUCCEEDED **` |
| S2.2 | iOS boot + install + launch | ✅ PASS | launched pid, `com.asitsounds.ios` |
| S2.3 | iOS FFI/Hangul render | ✅ PASS | Korean UI ("변환") renders |
| S2.4 | iOS home UI smoke | ✅ PASS | `ios_home.png` — tabs 변환/보관함/음차/설정 |
| S3.1 | Android `:app:assembleDebug` (+ jniLibs ×4 ABI) | ✅ PASS | `BUILD SUCCESSFUL` |
| S3.2 | Android boot + install + launch | ✅ PASS | `topResumedActivity ... /.MainActivity` |
| S3.3 | Android FFI via JNA | ✅ PASS | app runs, transliteration core linked |
| S3.4 | Android home UI smoke | ✅ PASS | `android_home.png` — tabs Transcribe/Library/Translit/Settings |

## Artifacts
- `test/results/ios_home.png` — iOS Simulator home (Convert screen)
- `test/results/android_home.png` — Android emulator home (Transcribe screen)

## Notes
- iOS Simulator: iPhone 17 (iOS sim runtime). Prebuilt `whisper.xcframework` + `AisCoreFFI.xcframework` reused.
- Android emulator: `Medium_Phone` AVD, system-image android-37 arm64-v8a; installed `app-arm64-v8a-debug.apk`.
- Both apps cold-start clean, no crash, localized UI renders. Whisper model download CTA shown (on-device model not pre-bundled by design).
