# As It Sounds — Mobile (iOS + Android)

> 🌐 [한국어](README.md) · **English**

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

### Phase 4 — Transcription quality & UX improvements ✅

- **F1 Progress tracking** — `PipelineRunner` gains `progressFraction` / `completedSegments` / `totalSegments`. `PipelineView` shows a deterministic `ProgressView` during the translation loop. Android: `PipelineEvent.TranslationProgress` event + `LibraryScreen` progress indicator with a "Resume" button.
- **F2 Retry / resume** — Library shows a retry button for error/processing files. iOS: `AppDatabase.clearSegments(fileId:)` + retry button in `LibraryView.ProjectDetailView` (`retryFile()`). Android: `LibraryViewModel.retryProject()` + WorkManager checkpoint resume. `AS-It-Sounds-iOS`: `Services.retryTranscription(fileId:)` + `TranscriptionRetryBannerView`.
- **F3 Higher-quality models** — iOS: `ggml-medium.bin` (1,534 MB) + `ggml-large-v3-turbo.bin` (809 MB) from HuggingFace `ggerganov/whisper.cpp` GGUF. Android: `MEDIUM` (1.5 GB, 8 GB+ RAM) + `LARGE_V3_TURBO` (809 MB, 6 GB+ RAM). RAM guard applied; selectable in Settings.

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

## Test distribution (install + run without Developer Mode)

Goal: get the app onto a tester's physical device that **does not** have Developer Mode enabled.

### Why a local Ad Hoc build still demands Developer Mode

On iOS 16+ the **Developer Mode requirement is tied to how the app reaches the device and the trust channel**, not only the certificate:

- **App Store / TestFlight** — Apple re-signs the binary with an App Store distribution profile and delivers it through the App Store / TestFlight client. **Never** needs Developer Mode, never needs UDID registration, never shows an "Untrusted Developer" prompt. This is the only route that is guaranteed Dev-Mode-free.
- **Ad Hoc** (`Apple Distribution` cert + ad-hoc profile, device UDID baked in) — installs over the `installation_proxy` channel (Apple Configurator / `ideviceinstaller`). In practice on recent iOS (incl. 26.x) launching a locally-sideloaded build can still trip **"개발자 모드가 필요함"**, because the install came through a developer tunnel. Unreliable for non-developer testers.
- **Development** (`Apple Development` cert, `get-task-allow=true`) — always requires Developer Mode. Not for testers.

**Conclusion: use TestFlight for iOS test distribution.** Identifiers we ship with: bundle `com.asitsounds.ios`, team `ALQEFZ23LN`, version `0.1.0 (1)`.

### iOS — TestFlight (recommended)

Prereq: paid Apple Developer Program; an App Store Connect app record for `com.asitsounds.ios`.

**1. Create the App Store Connect record** (once)
- appstoreconnect.apple.com → Apps → **+** → New App → iOS → pick bundle ID `com.asitsounds.ios` → SKU `asitsounds-ios` → create.

**2. Archive with App Store signing**
```bash
cd ios
xcodegen generate
xcodebuild -project AsItSounds.xcodeproj -scheme AsItSounds \
  -configuration Release -sdk iphoneos \
  -archivePath build/AsItSounds.xcarchive \
  -destination 'generic/platform=iOS' \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=ALQEFZ23LN CODE_SIGN_STYLE=Automatic \
  archive
```

**3. Export an App Store IPA** — `ios/build/exportOptions-appstore.plist`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>teamID</key><string>ALQEFZ23LN</string>
  <key>signingStyle</key><string>automatic</string>
  <key>destination</key><string>export</string>
</dict></plist>
```
```bash
xcodebuild -exportArchive \
  -archivePath build/AsItSounds.xcarchive \
  -exportOptionsPlist build/exportOptions-appstore.plist \
  -exportPath build/appstore-ipa \
  -allowProvisioningUpdates
```

**4. Upload to App Store Connect** — use an App Store Connect API key (Users and Access → Integrations → **+**, role *App Manager*; download `AuthKey_<KEYID>.p8` → `~/.appstoreconnect/private_keys/`):
```bash
xcrun altool --upload-app -f build/appstore-ipa/AsItSounds.ipa -t ios \
  --apiKey <KEYID> --apiIssuer <ISSUER-UUID>
# or: xcrun notarytool / Transporter.app (drag the .ipa)
```

**5. Hand out the build**
- Processing finishes in ~5–15 min (App Store Connect → TestFlight tab).
- **Internal testing** — add testers under Users and Access (up to 100, must be on the team). Builds go live immediately, no review.
- **External testing** — create a group, add tester emails or share the **public TestFlight link**. First external build needs a short Beta App Review.
- Tester installs the **TestFlight** app from the App Store → opens the invite → Install. **No Developer Mode, no UDID.**

> Build expiry: TestFlight builds last 90 days. Bump `CURRENT_PROJECT_VERSION` (build number) for each new upload.

### iOS — Ad Hoc (fallback, may still need Developer Mode)

What this repo did for a single registered device (`com.asitsounds.ios`):
```bash
# archive (as above), then export ad-hoc:
#   method = ad-hoc  (Xcode 26 deprecation-aliases this to "release-testing")
xcodebuild -exportArchive -archivePath build/AsItSounds.xcarchive \
  -exportOptionsPlist build/exportOptions-adhoc.plist \
  -exportPath build/adhoc-ipa -allowProvisioningUpdates
# install over the non-developer channel:
brew install ideviceinstaller
ideviceinstaller install build/adhoc-ipa/AsItSounds.ipa
```
The device UDID must be registered in the portal first (a destination-targeted `xcodebuild ... -allowProvisioningUpdates` registers it as a side effect, even if the build then errors on Developer Mode). Use only for your own dev devices — testers should get TestFlight.

---

## Android — test distribution

Identifiers: applicationId `com.asitsounds.android`, `versionCode 1` / `versionName 0.1.0`, `minSdk 28`, `target/compileSdk 35`. Android has **no Developer Mode equivalent** — a signed APK installs on any device that allows the source. Three routes, easiest first.

### 0. Create a release keystore (once)
```bash
keytool -genkeypair -v -keystore ~/keystores/asitsounds-release.jks \
  -alias asitsounds -keyalg RSA -keysize 2048 -validity 10000
```
Wire it into `android/app/build.gradle.kts` (keep secrets out of git — read from `~/.gradle/gradle.properties`):
```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getProperty("user.home") + "/keystores/asitsounds-release.jks")
        storePassword = (project.findProperty("AIS_STORE_PW") as String?) ?: ""
        keyAlias = "asitsounds"
        keyPassword = (project.findProperty("AIS_KEY_PW") as String?) ?: ""
    }
}
buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
```

### Route A — direct APK (fastest, no accounts)
```bash
cd android
./gradlew :app:assembleRelease            # → app/build/outputs/apk/release/app-*-release.apk
# send the universal APK (AirDrop / link). Tester enables "Install unknown apps"
# for their browser/Files, taps the APK, installs. Or via adb:
adb install -r app/build/outputs/apk/release/app-universal-release.apk
```

### Route B — Firebase App Distribution (managed testers, cross-platform)
```bash
npm i -g firebase-tools && firebase login
./gradlew :app:assembleRelease
firebase appdistribution:distribute \
  app/build/outputs/apk/release/app-universal-release.apk \
  --app <FIREBASE_ANDROID_APP_ID> \
  --groups "qa" --release-notes "0.1.0 build 1"
```
Testers get an email + the App Tester app. (Firebase also distributes the **iOS Ad Hoc IPA** to registered-UDID devices — but for Dev-Mode-free iOS, prefer TestFlight.)

### Route C — Google Play Internal testing (closest to production)
```bash
./gradlew :app:bundleRelease              # → app/build/outputs/bundle/release/app-release.aab
```
Play Console → create app → **Testing → Internal testing** → upload the `.aab` → add testers by email or opt-in link → testers install via Play Store. Up to 100 internal testers, no review delay. Play re-signs with the App Signing key.

### Distribution route summary

| Platform | Route | Dev Mode? | Tester ceiling | Review |
| :-- | :-- | :-- | :-- | :-- |
| iOS | **TestFlight** (recommended) | No | 100 internal / 10k external | external only, ~1 day |
| iOS | Ad Hoc + `ideviceinstaller` | sometimes | 100 UDIDs | none |
| Android | Direct APK | n/a | unlimited | none |
| Android | Firebase App Distribution | n/a | per-group | none |
| Android | Play Internal testing | n/a | 100 | none |

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

## Testing (`test/`)

Scenario-driven verification on the iOS Simulator + Android Emulator, plus the shared Rust core. See [`test/SCENARIOS.md`](test/SCENARIOS.md); latest run in [`test/results/RESULTS.md`](test/results/RESULTS.md).

| ID | Scope | How | Status |
| :-- | :-- | :-- | :-- |
| S1.1 | Rust core regression | `cargo test --workspace --release` (in `core/`) | ✅ 30/30 |
| S1.2 | UniFFI surface | xcframework + jniLibs ×4 ABI build | ✅ pass |
| S2.1 | iOS build | `xcodegen` + `xcodebuild -sdk iphonesimulator` (iPhone 17) | ✅ BUILD SUCCEEDED |
| S2.2–2.4 | iOS sim run | boot + `simctl install/launch` + screenshot | ✅ home renders, no crash |
| S3.1 | Android build | `./gradlew :app:assembleDebug` (NDK jniLibs) | ✅ BUILD SUCCESSFUL |
| S3.2–3.4 | Android emu run | `Medium_Phone` AVD + `adb install` + launch + screencap | ✅ home renders, MainActivity resumed |

Evidence screenshots: [`test/results/ios_home.png`](test/results/ios_home.png), [`test/results/android_home.png`](test/results/android_home.png).

> This is simulator/emulator launch + UI smoke verification. The unit-level `XCTest` + Android `app/src/test/` fixture scaffold remains on the roadmap below.

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
