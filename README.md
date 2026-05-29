# As It Sounds — Mobile (iOS + Android)

<div align="center">

**Native mobile companion to the desktop / web "As It Sounds" project — transcribe audio/video in any language, render it as Korean Hangul phonetics that mirror the actual sound, plus a Korean translation alongside. All on-device.**

Swift 5.10 · SwiftUI · iOS 17+ &nbsp;·&nbsp; Kotlin 2.0 · Jetpack Compose · Android API 28–35 &nbsp;·&nbsp; Rust · UniFFI · whisper.cpp v1.7.5

</div>

---

> Shares the desktop's Rust transliteration core verbatim via `#[path]` (1,713 LOC, 30 regression tests) — no copy, no fork. Everything else is platform-native.

| Path | Stack | Purpose |
| :-- | :-- | :-- |
| [`core/`](./core/) | Rust workspace, UniFFI 0.28, whisper.cpp v1.7.5 | Re-exports the desktop's `translit.rs`; exposes `transliterate()` / `version()` to Swift + Kotlin; produces `AisCoreFFI.xcframework` + `libais_ffi.so × 4 ABI` |
| [`ios/`](./ios/) | Swift 5.10, SwiftUI `@Observable`, AVFoundation, GRDB.swift 6.29.3, BGProcessingTask, CloudKit, ActivityKit, AppIntents | iPhone / iPad app |
| [`android/`](./android/) | Kotlin 2.0, Jetpack Compose, Material 3, Media3 ExoPlayer 1.5.1, JNA 5.17.0, SQLiteOpenHelper, Foreground Service `mediaProcessing` | Android phone / tablet app |
| [`.doc/`](./.doc/) | Markdown | PRDs (iPhone + Android), env setup, signing, store metadata, KMM migration plan, Live Activity guide |
| [`.github/workflows/`](./.github/workflows/) | YAML | iOS / Android / Release CI — currently `workflow_dispatch:` only (auto triggers disabled) |
| [`assets/`](./assets/) | SVG + shell | App icon SVGs + `generate-icons.sh` (rsvg-convert) + `capture-screenshots.sh` |
| [`.review/`](#analyst-output) | Markdown | Auto-generated in-depth reviews, junior guides, line-by-line code reviews (analyst output, regenerable) |

> Inline source paths below are shorthand. Real roots: iOS = `ios/AsItSounds/` (e.g. `Core/Pipeline.swift` → [`ios/AsItSounds/Core/Pipeline.swift`](./ios/AsItSounds/Core/Pipeline.swift)); Android = `android/app/src/main/java/com/asitsounds/android/` (e.g. `whisper/Pipeline.kt`).

---

## Feature status

### Phase 0 — Scaffolding ✅

- Rust workspace builds (`cargo build --release`); all 30 upstream `translit.rs` tests pass.
- UniFFI Swift binding: `core/bindings/swift/AisCoreFFI.swift`
- UniFFI Kotlin binding: `core/bindings/kotlin/com/asitsounds/core/ais_ffi.kt`
- iOS device static lib cross-compiled (`aarch64-apple-ios`).
- Android `.so × 4 ABI` (arm64-v8a / armeabi-v7a / x86_64 / x86) via cargo-ndk.
- iOS SwiftUI scaffold + Android Compose scaffold; both demo `transliterate()` round-trip via FFI.

### Phase 1 — On-device transcription ✅

- Whisper.cpp v1.7.5 statically linked
  - iOS: `whisper.xcframework` (device + sim slices) via `core/scripts/assemble-whisper-xcframework.sh`
  - Android: `libais_whisper.so` per ABI via CMake + JNI (`android/app/src/main/cpp/ais_whisper_jni.cpp`)
- Audio decode → 16 kHz mono Float32 PCM
  - iOS: `AVAssetReader` + `AVAudioConverter` (`Core/AudioDecoder.swift`)
  - Android: `MediaExtractor` + `MediaCodec` + linear resample (`whisper/AudioDecoder.kt`)
- Model download: `ggml-{tiny,base,small}.bin` from Hugging Face, `.part` temp + atomic rename
- Pipeline orchestration:
  - iOS: `PipelineRunner` `@MainActor @Observable` + `Task.detached`
  - Android: `Pipeline.run(...)` returning `Flow<PipelineEvent>` on `Dispatchers.IO`

### Phase 2 — Real-world product ✅

- **Translator** — 4 backends, pluggable: MyMemory (free, default) / LibreTranslate (self-host) / DeepL (API key) / Off
- **Persistence** — 3 tables × FK cascade, shared schema across platforms
  - iOS: GRDB.swift 6.29.3 (local SwiftPM clone at `ios/packages/GRDB/`)
  - Android: hand-rolled `SQLiteOpenHelper` (Room blocked by KSP 2.0.21 incident — see [`.doc/signing.md`](.doc/signing.md))
- **Background processing**
  - iOS: `BackgroundTaskCoordinator` (BGProcessingTask, ID `com.asitsounds.ios.transcribe`)
  - Android: `TranscribeForegroundService` (`foregroundServiceType="mediaProcessing"`, API 34+)
- **Media player with auto-highlight**
  - iOS: AVPlayer + `ScrollViewReader` (`Features/PlayerView.swift`)
  - Android: Media3 ExoPlayer 1.5.1 + LazyColumn (`ui/PlayerScreen.kt`)
- **Subtitle export** — SRT / VTT / TXT
  - iOS: `UIDocumentPicker` (`Core/SubtitleExport.swift`)
  - Android: `ActivityResultContracts.CreateDocument` (`whisper/SubtitleExport.kt`)

### Phase 3 — Platform integrations ✅ (scaffolded)

- **iCloud sync** — `Core/CloudSync.swift` (CKDatabase private DB push/pull; requires iCloud capability + container `iCloud.com.asitsounds.ios`)
- **Siri / App Intents** — `Core/AppShortcuts.swift` (`OpenLatestProjectIntent` voice-triggered; `TransliterateIntent` Shortcuts.app entry)
- **Live Activity** — `Core/LiveActivity.swift` (`ActivityKit` attributes + Coordinator); Widget Extension target = manual Xcode step (see [`.doc/live-activity.md`](.doc/live-activity.md))
- **Quick Settings Tile (Android)** — `service/TranscribeQuickTile.kt` (registered in `AndroidManifest.xml`, opens app)
- **Live microphone STT** — rolling 5 s buffer + Whisper inference
  - iOS: `Core/LiveMicSTT.swift` (`AVAudioEngine` tap + `AVAudioConverter`)
  - Android: `whisper/LiveMicStt.kt` (`AudioRecord` Float32 native 16 kHz)
- **KMM migration plan** — [`.doc/kmm-migration.md`](.doc/kmm-migration.md) (deferred to Phase 3.5 / ~12 working days)

### Polish ✅

- i18n: English + 한국어 (`Localizable.strings` × 2 / `strings.xml` × 2, 50+ keys each)
- Dark mode: system / always on / always off via `Settings` (`AppSettings.colorScheme`)
- Material 3 Dynamic Color (Android 12+) + iOS semantic colors (`Color.accentColor` / `Color(.systemGreen)`)
- Accessibility labels: VoiceOver (iOS) + TalkBack (Android) per segment row
- Settings screen: real picker for Whisper model, translator, dark mode, license card, cache clear, disk usage

### Release prep ✅

- App icons: SVG sources + `assets/generate-icons.sh` (rsvg-convert) → iOS 1024×1024 + Android adaptive icons × 5 densities
- Store metadata: [`.doc/store-metadata.md`](.doc/store-metadata.md) (App Store Connect + Play Console, en + ko)
- Signing: [`.doc/signing.md`](.doc/signing.md) (Apple Developer ID + Play upload key + CI secrets matrix)
- CI: `.github/workflows/{ios,android,release}.yml` — **auto triggers commented out** (manual `Run workflow` only); re-enable when ready

---

## Quick start

> Prefer the **paren-free symlink** `~/As-It-Sounds-app` for Xcode GUI — macOS paths containing `(...)` break some Xcode path-normalisation steps:
> ```bash
> ln -sfn "/Users/$USER/Developer/Apps(Android,iOS,...)/As-It-Sounds-app" "$HOME/As-It-Sounds-app"
> ```

### Prerequisites

| Tool | Version | macOS install |
| :-- | :-- | :-- |
| Xcode | **26.0+** with iOS 17 SDK | Mac App Store |
| `xcode-select` | Must point at Xcode.app, **not** CommandLineTools | `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` |
| xcodegen | 2.45+ | `brew install xcodegen` |
| librsvg (rsvg-convert) | latest | `brew install librsvg` |
| Rust | 1.82+ via rustup | `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \| sh` |
| Android Studio | Panda 4 (2025.3.4) or newer | Direct download |
| Android NDK | **30.0.14904198** | Android Studio → SDK Manager → SDK Tools → NDK (Side by side) |
| CMake | 3.22.1+ | Android Studio → SDK Manager → SDK Tools |
| cargo-ndk | latest | `cargo install --locked cargo-ndk` |

```bash
# Once: NDK + SDK env
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.14904198"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Once: Rust mobile targets (iOS + Android, 7 targets)
rustup target add \
  aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios \
  aarch64-linux-android armv7-linux-androideabi \
  x86_64-linux-android i686-linux-android
```

### Fetch external sources (one-time)

```bash
# whisper.cpp v1.7.5 (used by both platforms)
git clone --depth 1 --branch v1.7.5 \
  https://github.com/ggerganov/whisper.cpp \
  core/third_party/whisper.cpp

# GRDB.swift v6.29.3 (iOS only — paren-path workaround)
git clone --depth 1 --branch v6.29.3 \
  https://github.com/groue/GRDB.swift \
  ios/packages/GRDB
```

### Build & run — iOS

```bash
# 1. Build Rust core → iOS xcframework + Swift bindings
cd core && ./scripts/build-ios.sh

# 2. Build whisper.cpp → iOS xcframework
(cd core/third_party/whisper.cpp && ./build-xcframework.sh) || true
(cd core && ./scripts/assemble-whisper-xcframework.sh)

# 3. Generate Xcode project
cd ../ios && xcodegen generate

# 4. Open in Xcode (use paren-free symlink for GUI)
open AsItSounds.xcodeproj
# → ⌘R targeting iPhone 17 Pro Simulator (iOS 26.5) or a registered device
```

### Build & run — Android

```bash
# 1. Build Rust core → 4 ABI .so + Kotlin bindings
cd core && ./scripts/build-android.sh

# 2. Open the android/ directory in Android Studio Panda
# → Gradle sync auto-fires
# → Run ▶︎ on a Medium_Phone emulator (API 28+) or device

# Or CLI
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb shell am start -n com.asitsounds.android/.MainActivity
```

---

## Usage demo

Both apps boot to 4 tabs: **Transcribe** / **Library** / **Translit** / **Settings**.

1. **Transcribe** — pick a media file (Files / PhotoPicker / Share Sheet on iOS, SAF on Android) → choose translator engine → tap *Transcribe* → segments stream in as EN / 한 / KO.
2. **Library** — projects + files + segments persisted across launches; tap a project to inspect.
3. **Translit** — quick text → Hangul demo via the Rust FFI (no Whisper required).
4. **Settings** — Whisper model, default translator, dark mode, version, licenses, cache clear.

### Sample inputs / outputs (Translit tab)

| Input | Expected Hangul output |
| :-- | :-- |
| `As it sounds, it works.` | 애즈 잇 사운즈, 잇 웍스. |
| `C. Right around the corner.` | 씨. 라이트 어라운드 더 코너. |
| `bonjour` | 봉주흐 |
| `こんにちは` | 콩니치하 |
| `Москва` | 모스크바 |
| `안녕하세요` | 안녕하세요 (pass-through) |

---

## Documentation

- **PRDs** — [iPhone](.doc/PRD-for-iPhone.md) · [Android](.doc/PRD-for-Android.md)
- **Environment setup** — [`.doc/ENV-setup.md`](.doc/ENV-setup.md)
- **Store metadata** (App Store Connect + Play Console, en + ko) — [`.doc/store-metadata.md`](.doc/store-metadata.md)
- **Code signing** (Apple Developer ID + Play upload key + CI secrets) — [`.doc/signing.md`](.doc/signing.md)
- **Live Activity setup** (Widget Extension manual Xcode steps) — [`.doc/live-activity.md`](.doc/live-activity.md)
- **KMM migration plan** (Phase 3.5, ~12 working days) — [`.doc/kmm-migration.md`](.doc/kmm-migration.md)
- **Per-platform README** — [iOS](./ios/README.md) · [Android](./android/README.md)

### Analyst output

Auto-generated reports under [`.review/`](./.review/) (regenerable; safe to delete):

| Report | Scope | Location |
| :-- | :-- | :-- |
| In-depth commercialization review | Android | [`android/.review/REVIEW-InDepth.md`](./android/.review/REVIEW-InDepth.md) |
| In-depth commercialization review | iOS | [`ios/.review/REVIEW-InDepth.md`](./ios/.review/REVIEW-InDepth.md) |
| Junior developer guide (bilingual) | Android | [`android/.review/GUIDE4Junior.md`](./android/.review/GUIDE4Junior.md) |
| Junior developer guide (bilingual) | iOS | [`ios/.review/GUIDE4Junior.md`](./ios/.review/GUIDE4Junior.md) |
| Implementation method guide (bilingual) | Android | [`android/.review/GUIDE4ImpMethod.md`](./android/.review/GUIDE4ImpMethod.md) |
| Implementation method guide (bilingual) | iOS | [`ios/.review/GUIDE4ImpMethod.md`](./ios/.review/GUIDE4ImpMethod.md) |
| Line-by-line code review (Level 2) | Workspace | [`.review/CODE-REVIEW-Lv2/INDEX.md`](./.review/CODE-REVIEW-Lv2/INDEX.md) |

---

## CI / Release

Three GitHub Actions workflows are committed but **auto triggers are disabled** (only `workflow_dispatch:` active):

| Workflow | Purpose | Re-enable |
| :-- | :-- | :-- |
| [`.github/workflows/ios.yml`](.github/workflows/ios.yml) | macos-14 simulator build per PR / main push | Uncomment `push` + `pull_request` blocks |
| [`.github/workflows/android.yml`](.github/workflows/android.yml) | ubuntu-22.04 debug APK per PR / main push | Same |
| [`.github/workflows/release.yml`](.github/workflows/release.yml) | Signed IPA + AAB on tag `v*` push | Uncomment `push.tags` block |

Manual run: GitHub → Actions → select workflow → *Run workflow*.

---

## Resolved issues — fix log

Cumulative incidents resolved during Phase 0–3 build-up:

| Symptom | Root cause | Fix |
| :-- | :-- | :-- |
| `bash: aarch64: unbound variable` in `build-android.sh` | macOS bash 3.2 + `set -u` × `declare -A` | Replaced associative array with parallel index arrays |
| iOS `XCFramework not found` in Xcode GUI | Paren `(...)` in path breaks Xcode 26 path normalisation | Use `~/As-It-Sounds-app` paren-free symlink |
| `cannot find 'AisCoreFFI' in scope` | UniFFI Swift is top-level `func`, not a module type | Call `transliterate(text:)` directly |
| `[CXX5106] NDK was located by using ndk.dir property` | Deprecated `ndk.dir` in `local.properties` | Delete the line; rely on `ndkVersion` in `app/build.gradle.kts` |
| `program alignment (8192) < page size (16384)` (Android 16 emulator) | Old JNA 5.15 8 KB alignment vs 16 KB page | Bump JNA → 5.17.0 (AAR variant) |
| KSP `Wrong plugin option format: null` | Kotlin 2.0.21 + KSP 2.0.21-1.0.27 + AGP 8.7 conflict | Drop Room, hand-rolled `SQLiteOpenHelper` |
| GRDB SwiftPM `cannot use bare repository (safe.bareRepository is 'explicit')` | Path with parens triggers git safe-repo policy | Local clone at `ios/packages/GRDB/` + `path:` ref |
| `unknown platform: xros` (vtool) building whisper.xcframework | whisper.cpp's `build-xcframework.sh` tries visionOS slice | Use our iOS-only `assemble-whisper-xcframework.sh` |
| `Undefined: _cblas_sgemm$NEWLAPACK$ILP64` | Accelerate framework not linked | `project.yml` adds 4 SDK frameworks (Accelerate / Metal / MetalKit / CoreML) |
| `Undefined: _whisper_coreml_*` | xcframework assembly excluded `libwhisper.coreml.a` | Include all `.a` per slice |
| AppIntents `Invalid parameter type. AppEntity and AppEnum are the only allowed types` | Free-form String in AppShortcut phrase | Phrase-bind only parameter-less intents |

---

## Roadmap (next)

| Priority | Item | Where it lives |
| :-- | :-- | :-- |
| Critical | `Pipeline.kt:66,70` double-free fix (`nativeFree(handle)` in `catch` **and** `finally` both fire on exception) | [Pipeline.kt.md](.review/CODE-REVIEW-Lv2/android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt.md) §watch-outs |
| Critical | `AudioDecoder.kt:61` OOM risk on 30 min+ audio (`ArrayList<Short>` boxing) | replace with `ShortBuffer` / `IntArray` |
| High | Whisper model SHA256 verification (both platforms) | `ModelManager.{kt,swift}` |
| High | XCTest + Android `app/src/test/` scaffold with ≥30 transliteration regression fixtures | new |
| High | Re-enable CI auto triggers after first stable tag | `.github/workflows/*.yml` |
| Medium | NOTICE file + auto OSS licenses screen | `gradle-license-plugin` (Android) + `LicensePlist` (iOS) |
| Medium | Live Activity Widget Extension target (manual Xcode step) | [`.doc/live-activity.md`](.doc/live-activity.md) |
| Medium | Crashlytics / MetricKit | both |
| Low | KMM migration (Phase 3.5) | [`.doc/kmm-migration.md`](.doc/kmm-migration.md) |

---

## License

MIT — same as the upstream desktop project. Full license file pending in `LICENSE` at repo root.

### Third-party components

- **whisper.cpp** v1.7.5 (`ggerganov/whisper.cpp`) — MIT
- **ggml** (bundled in whisper.cpp) — MIT
- **GRDB.swift** v6.29.3 (`groue/GRDB.swift`) — MIT
- **UniFFI** 0.28 (`mozilla/uniffi-rs`) — MPL-2.0 (generated binding files only)
- **JNA** 5.17.0 (`java-native-access/jna`) — Apache-2.0 OR LGPL-2.1+ (we use Apache-2.0)
- **AndroidX / Compose / Media3** — Apache-2.0
- **Whisper model weights** (Hugging Face `ggerganov/whisper.cpp`) — MIT

---

## Acknowledgements

- Upstream desktop / web app: [`../NGL(Go,Rust,...)/As-It-Sounds`](../NGL(Go,Rust,...)/As-It-Sounds)
- `translit.rs` — 1,713 lines of context-aware multilingual phonetic rules (English, Japanese, French, Spanish, German, Russian) — the heart of "As It Sounds"
