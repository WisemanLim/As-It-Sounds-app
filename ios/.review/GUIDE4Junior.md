# 프로젝트 주니어 개발자 가이드 / Junior Developer Guide

> 이 문서는 한국어와 영어로 작성되었습니다.
> This document is written in both Korean and English.
>
> 대상 / Target: `As-It-Sounds-app/ios`
> 작성일 / Date: 2026-05-29

---

## 1. 프로젝트 개요 / Project Overview

**[한국어]**

- **목적**: 영어·일본어·프랑스어·스페인어·독일어·러시아어 등 다국어 음성/영상 파일을 iPhone/iPad 에서 **온디바이스로** Whisper.cpp + Core ML 가속으로 전사한 뒤, **들리는 발음 그대로** 한글 음차 + 한국어 번역을 함께 보여주는 학습용 앱입니다.
- **핵심 기능**:
  - 로컬 Whisper STT (Core ML encoder 활용, base 모델 첫 실행 시 ~142 MB 다운로드)
  - 다국어 → 한글 음차 (Rust 1,713 줄 음운 규칙, 데스크탑/Android 와 동일 코어 공유)
  - 4종 번역 엔진 (MyMemory / LibreTranslate / DeepL / Off)
  - GRDB 영구화 (3 테이블)
  - AVPlayer + ScrollViewReader 자동 하이라이트
  - SRT/VTT/TXT 자막 내보내기 (UIDocumentPicker)
  - BGProcessingTask 백그라운드 처리 골조
  - App Intents (Siri) + Live Activity 스캐폴드
- **기술 스택**: Swift 5.10, SwiftUI + `@Observable` (iOS 17 Observation), AVFoundation, AVKit, BackgroundTasks, CloudKit, ActivityKit, AppIntents · GRDB.swift 6.29.3 (local SwiftPM) · whisper.cpp v1.7.5 (정적 xcframework) · UniFFI Swift bindings · iOS 17.0+, Xcode 26.0.

**[English]**

- **Purpose**: iPhone/iPad app that transcribes audio/video in 6+ languages **on-device** with Whisper.cpp + Core ML, then renders the result as Korean Hangul phonetics **mirroring the actual sound** plus a Korean translation side-by-side. Designed for language learners and content creators.
- **Core features**:
  - Local Whisper STT (Core ML encoder, base model auto-downloaded on first run ~142 MB)
  - Multilingual → Hangul transliteration (1,713 lines of Rust phonetic rules, shared with desktop and Android cores)
  - Pluggable translator (MyMemory / LibreTranslate / DeepL / Off)
  - GRDB persistence (3 tables)
  - AVPlayer with `ScrollViewReader` auto-highlight
  - SRT / VTT / TXT subtitle export via `UIDocumentPicker`
  - BGProcessingTask background scaffold
  - App Intents (Siri) + Live Activity attributes scaffold
- **Tech stack**: Swift 5.10, SwiftUI + `@Observable` (iOS 17 Observation framework), AVFoundation, AVKit, BackgroundTasks, CloudKit, ActivityKit, AppIntents · GRDB.swift 6.29.3 (local SwiftPM) · whisper.cpp v1.7.5 (static xcframework) · UniFFI Swift bindings · iOS 17.0+, Xcode 26.0.

---

## 2. 개발 환경 요구사항 / Prerequisites

**[한국어]**

| 도구 | 버전 | 비고 |
| :-- | :-- | :-- |
| **macOS** | 14 Sonoma 이상 | Xcode 26 호환 |
| **Xcode** | **26.0** 이상 | iOS 17 SDK, Strict Concurrency |
| **xcode-select** | `/Applications/Xcode.app/Contents/Developer` | CommandLineTools 만으로는 부족 |
| **xcodegen** | 2.45+ | `brew install xcodegen` |
| **librsvg** (rsvg-convert) | 최신 | 아이콘 SVG → PNG 변환 |
| **Rust** | 1.82+ (rustup) | `core/` 빌드용. **brew rust 금지** (cross target 불가) |
| **Apple Silicon Mac** | 권장 | Whisper Core ML 가속 검증 위해 |
| **Apple Developer Program** | TestFlight / App Store 출시 시 필수 | $99/yr |

- **권장 IDE**: Xcode 26. 보조: VS Code (코드 탐색용, 빌드는 xcodebuild)
- **OS별 주의사항**:
  - macOS 경로의 **괄호 `(...)` 가 Xcode GUI 일부 path-normalisation 단계를 깨뜨림**. `~/As-It-Sounds-app` 같은 paren-free 심볼릭 링크에서 프로젝트 열기 권장.
  - Linux/Windows 에서는 빌드 불가 — Xcode 가 macOS only.

**[English]**

| Tool | Version | Notes |
| :-- | :-- | :-- |
| **macOS** | 14 Sonoma or newer | Xcode 26 compatibility |
| **Xcode** | **26.0+** | iOS 17 SDK, Strict Concurrency |
| **xcode-select** | `/Applications/Xcode.app/Contents/Developer` | CommandLineTools alone is insufficient |
| **xcodegen** | 2.45+ | `brew install xcodegen` |
| **librsvg** (rsvg-convert) | latest | Icon SVG → PNG conversion |
| **Rust** | 1.82+ via rustup | Required for `core/`. **Don't use `brew install rust`** — cross targets won't work |
| **Apple Silicon Mac** | Strongly recommended | Core ML acceleration validation |
| **Apple Developer Program** | Required for TestFlight / App Store | $99/yr |

- **Recommended IDE**: Xcode 26. Optional: VS Code for code browsing; use `xcodebuild` to compile.
- **OS notes**:
  - macOS paths containing **parentheses `(...)` break some Xcode GUI path-normalisation steps**. Open the project via a paren-free symlink like `~/As-It-Sounds-app` instead.
  - Linux/Windows builds aren't possible — Xcode is macOS-only.

---

## 3. 개발 환경 구성 (단계별) / Environment Setup (Step-by-Step)

**[한국어]**

1. **xcode-select Xcode.app 으로 전환**
   ```bash
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   xcode-select -p   # → /Applications/Xcode.app/Contents/Developer
   ```
2. **도구 설치**
   ```bash
   brew install xcodegen librsvg
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   source "$HOME/.cargo/env"
   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
   ```
3. **저장소 + paren-free 심볼릭 링크**
   ```bash
   git clone <repo-url> "/Users/$USER/Developer/Apps(Android,iOS,...)/As-It-Sounds-app"
   ln -sfn "/Users/$USER/Developer/Apps(Android,iOS,...)/As-It-Sounds-app" "$HOME/As-It-Sounds-app"
   cd ~/As-It-Sounds-app
   ```
4. **whisper.cpp + GRDB 로컬 clone (한 번만)**
   ```bash
   mkdir -p core/third_party
   git clone --depth 1 --branch v1.7.5 \
     https://github.com/ggerganov/whisper.cpp \
     core/third_party/whisper.cpp

   mkdir -p ios/packages
   git clone --depth 1 --branch v6.29.3 \
     https://github.com/groue/GRDB.swift \
     ios/packages/GRDB
   ```
5. **Rust 코어 → xcframework + UniFFI Swift 바인딩**
   ```bash
   cd core && ./scripts/build-ios.sh
   # → core/target/AisCoreFFI.xcframework
   # → core/bindings/swift/AisCoreFFI.swift
   ```
6. **whisper.xcframework 조립** (build-xcframework.sh + assemble 스크립트)
   ```bash
   (cd third_party/whisper.cpp && ./build-xcframework.sh) || true
   ./scripts/assemble-whisper-xcframework.sh
   # → core/target/whisper.xcframework
   ```
7. **Xcode 프로젝트 생성 + 빌드**
   ```bash
   cd ../ios && xcodegen generate
   open AsItSounds.xcodeproj
   # 또는 CLI
   xcodebuild -project AsItSounds.xcodeproj \
     -scheme AsItSounds -configuration Debug \
     -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
   ```
8. **검증** — Xcode 에서 ⌘R → 시뮬레이터 부팅 → Translit 탭 → 한글 출력 표시.

**[English]**

1. **Point `xcode-select` at Xcode.app**
   ```bash
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   xcode-select -p   # → /Applications/Xcode.app/Contents/Developer
   ```
2. **Install tools**
   ```bash
   brew install xcodegen librsvg
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   source "$HOME/.cargo/env"
   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
   ```
3. **Clone + create paren-free symlink**
   ```bash
   git clone <repo-url> "/Users/$USER/Developer/Apps(Android,iOS,...)/As-It-Sounds-app"
   ln -sfn "/Users/$USER/Developer/Apps(Android,iOS,...)/As-It-Sounds-app" "$HOME/As-It-Sounds-app"
   cd ~/As-It-Sounds-app
   ```
4. **Clone whisper.cpp + GRDB locally (one-time)**
   ```bash
   mkdir -p core/third_party
   git clone --depth 1 --branch v1.7.5 \
     https://github.com/ggerganov/whisper.cpp \
     core/third_party/whisper.cpp

   mkdir -p ios/packages
   git clone --depth 1 --branch v6.29.3 \
     https://github.com/groue/GRDB.swift \
     ios/packages/GRDB
   ```
5. **Build the Rust core → xcframework + UniFFI Swift bindings**
   ```bash
   cd core && ./scripts/build-ios.sh
   # → core/target/AisCoreFFI.xcframework
   # → core/bindings/swift/AisCoreFFI.swift
   ```
6. **Assemble whisper.xcframework**
   ```bash
   (cd third_party/whisper.cpp && ./build-xcframework.sh) || true
   ./scripts/assemble-whisper-xcframework.sh
   # → core/target/whisper.xcframework
   ```
7. **Generate the Xcode project + build**
   ```bash
   cd ../ios && xcodegen generate
   open AsItSounds.xcodeproj
   # or CLI
   xcodebuild -project AsItSounds.xcodeproj \
     -scheme AsItSounds -configuration Debug \
     -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
   ```
8. **Verify** — In Xcode, ⌘R → simulator boots → Translit tab → Hangul output appears.

---

## 4. 소스 구조 설명 / Source Structure

**[한국어]**

```tree
ios/
├── README.md                              # 단축 빌드 가이드
├── project.yml                            # xcodegen spec (87 LOC)
├── AsItSounds.xcodeproj/                  # generated, gitignored
├── packages/
│   └── GRDB/                              # 로컬 SwiftPM clone (paren-path 회피)
└── AsItSounds/
    ├── AsItSoundsApp.swift                # @main, scene root
    ├── AsItSounds-Bridging-Header.h       # Obj-C++ 헤더 노출
    ├── Core/  (12 파일, ~1.4k LOC)
    │   ├── WhisperEngine.{h,mm}           # Obj-C++ → whisper.cpp
    │   ├── AudioDecoder.swift             # AVAssetReader + AVAudioConverter
    │   ├── Database.swift                 # GRDB DAO + migrator
    │   ├── ModelManager.swift             # URLSession 백그라운드 다운로드
    │   ├── Pipeline.swift                 # @MainActor @Observable runner
    │   ├── Translator.swift               # 4 impls + TranslatorChoice
    │   ├── SubtitleExport.swift           # SRT/VTT/TXT 렌더
    │   ├── BackgroundTask.swift           # BGProcessingTask 코디네이터
    │   ├── CloudSync.swift                # CKDatabase push/pull
    │   ├── AppShortcuts.swift             # AppIntents Siri
    │   ├── LiveActivity.swift             # ActivityKit attrs
    │   └── LiveMicSTT.swift               # AVAudioEngine 롤링 버퍼
    ├── Features/  (8 파일, ~640 LOC)
    │   ├── RootView.swift                 # TabView 4탭
    │   ├── PipelineView.swift             # 267 LOC, 메인 흐름
    │   ├── LibraryView.swift              # 프로젝트/파일/세그먼트 트리
    │   ├── PlayerView.swift               # AVPlayer + 하이라이트
    │   ├── SettingsView.swift             # 모델/번역/다크모드/라이선스
    │   ├── TranslitDemoView.swift         # FFI 검증 데모
    │   └── (legacy Placeholder views 2)
    └── Resources/
        ├── Info.plist                     # iOS 17 + BG modes + BGTaskIDs
        ├── Assets.xcassets/
        │   ├── AppIcon.appiconset/        # 1024×1024 PNG
        │   └── AccentColor.colorset/
        ├── en.lproj/Localizable.strings   # 영어 50+
        └── ko.lproj/Localizable.strings   # 한국어 50+
```

**핵심 모듈 의존 관계**:

- `Features/*` (SwiftUI 뷰) → `Core/PipelineRunner.run(...)` 호출
- `PipelineRunner` (`@MainActor @Observable`) → `Task.detached` 내부에서 `AudioDecoder` → `WhisperEngine` (Obj-C++) → `transliterate(text:)` (UniFFI) → `Translator.translate(...)` → `AppDatabase.shared.saveSegments(...)`
- `AppSettings.shared` (UserDefaults) — 모든 뷰가 `@State private var settings = AppSettings.shared` 로 구독
- `BackgroundTaskCoordinator` — `AsItSoundsApp.init()` 에서 1회 register
- `AppShortcutsProvider` — Siri 자동 인식

**[English]**

```tree
ios/
├── README.md
├── project.yml                            # xcodegen spec (87 LOC)
├── AsItSounds.xcodeproj/                  # generated, gitignored
├── packages/
│   └── GRDB/                              # local SwiftPM clone (paren-path workaround)
└── AsItSounds/
    ├── AsItSoundsApp.swift                # @main scene root
    ├── AsItSounds-Bridging-Header.h
    ├── Core/  (12 files, ~1.4k LOC)
    │   ├── WhisperEngine.{h,mm}           # Obj-C++ → whisper.cpp
    │   ├── AudioDecoder.swift             # AVAssetReader + AVAudioConverter
    │   ├── Database.swift                 # GRDB DAO + migrator
    │   ├── ModelManager.swift             # URLSession background download
    │   ├── Pipeline.swift                 # @MainActor @Observable runner
    │   ├── Translator.swift               # 4 impls + TranslatorChoice
    │   ├── SubtitleExport.swift           # SRT/VTT/TXT renderers
    │   ├── BackgroundTask.swift           # BGProcessingTask coordinator
    │   ├── CloudSync.swift                # CKDatabase push/pull
    │   ├── AppShortcuts.swift             # AppIntents / Siri
    │   ├── LiveActivity.swift             # ActivityKit attributes
    │   └── LiveMicSTT.swift               # AVAudioEngine rolling buffer
    ├── Features/  (8 files, ~640 LOC)
    │   ├── RootView.swift                 # TabView shell (4 tabs)
    │   ├── PipelineView.swift             # 267 LOC, main flow
    │   ├── LibraryView.swift              # project/file/segment tree
    │   ├── PlayerView.swift               # AVPlayer + highlight
    │   ├── SettingsView.swift             # model/translator/dark mode/licenses
    │   ├── TranslitDemoView.swift         # FFI smoke test
    │   └── (2 legacy Placeholder views)
    └── Resources/
        ├── Info.plist
        ├── Assets.xcassets/
        │   ├── AppIcon.appiconset/
        │   └── AccentColor.colorset/
        ├── en.lproj/Localizable.strings
        └── ko.lproj/Localizable.strings
```

**Core module dependencies**:

- `Features/*` (SwiftUI views) → call `Core/PipelineRunner.run(...)`
- `PipelineRunner` (`@MainActor @Observable`) → inside a `Task.detached`: `AudioDecoder` → `WhisperEngine` (Obj-C++) → `transliterate(text:)` (UniFFI) → `Translator.translate(...)` → `AppDatabase.shared.saveSegments(...)`
- `AppSettings.shared` (UserDefaults) — every view binds via `@State private var settings = AppSettings.shared`
- `BackgroundTaskCoordinator` is registered once in `AsItSoundsApp.init()`
- `AppShortcutsProvider` is auto-picked-up by Siri / Shortcuts.app

---

## 5. 로컬 빌드 방법 / Local Build

**[한국어]**

```bash
# 시뮬레이터 디버그 (Xcode GUI ⌘R 동등)
xcodebuild -project AsItSounds.xcodeproj \
  -scheme AsItSounds -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

# 실기 디버그 (Apple Developer 등록된 디바이스)
xcodebuild -project AsItSounds.xcodeproj \
  -scheme AsItSounds -configuration Debug \
  -destination 'platform=iOS,name=<device-name>' build

# 릴리스 archive (서명 + IPA 추출 .doc/signing.md 참조)
xcodebuild -project AsItSounds.xcodeproj \
  -scheme AsItSounds -configuration Release \
  -archivePath build/AsItSounds.xcarchive archive
```

**빌드 결과물 위치**:
- Debug app: `build/Build/Products/Debug-iphonesimulator/AsItSounds.app`
- Archive: `build/AsItSounds.xcarchive`
- Export IPA: `build/export/AsItSounds.ipa` (export 단계 후)

**자주 발생하는 빌드 오류**:

| 오류 | 원인 | 해결 |
| :-- | :-- | :-- |
| `There is no XCFramework found at '.../AisCoreFFI.xcframework'` | `core/scripts/build-ios.sh` 미실행 | Step 3.5 의 build-ios.sh 실행 |
| `cannot use bare repository '...' (safe.bareRepository is 'explicit')` | SwiftPM 의 git 보안 정책 | GRDB 를 **로컬 clone (`packages/GRDB`)** 으로 우회 (현재 적용됨) |
| `Undefined symbols: _cblas_sgemm$NEWLAPACK$ILP64` | Accelerate framework 미링크 | `project.yml` 의 `dependencies` 에 `sdk: Accelerate.framework` 등 4개 SDK 추가 (현재 적용됨) |
| `Undefined symbols: _whisper_coreml_*` | xcframework 조립 시 libwhisper.coreml.a 제외 | `assemble-whisper-xcframework.sh` 의 find 패턴이 모든 `.a` 포함하도록 (현재 적용됨) |
| `AppIntents: Invalid parameter type. AppEntity and AppEnum are the only allowed types` | `TransliterateIntent` 의 String 파라미터를 Phrase 에 사용 | `OpenLatestProjectIntent` 처럼 파라미터 없는 intent 만 phrase 노출 (현재 적용됨) |
| `unknown platform: xros` (vtool) | whisper.cpp `build-xcframework.sh` 가 visionOS slice 빌드 시도 | iOS-only `assemble-whisper-xcframework.sh` 사용 |
| `value of tuple type 'Void' has no member 'recordsToSave'` | iOS 17 SDK 에서 `CKDatabase.add(op)` async API 변경 | `database.modifyRecords(saving:deleting:savePolicy:)` 사용 (현재 적용됨) |

**[English]**

```bash
# Simulator debug build (equivalent to Xcode ⌘R)
xcodebuild -project AsItSounds.xcodeproj \
  -scheme AsItSounds -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build

# Device debug build (registered Apple Developer device)
xcodebuild -project AsItSounds.xcodeproj \
  -scheme AsItSounds -configuration Debug \
  -destination 'platform=iOS,name=<device-name>' build

# Release archive (signing + IPA export — see .doc/signing.md)
xcodebuild -project AsItSounds.xcodeproj \
  -scheme AsItSounds -configuration Release \
  -archivePath build/AsItSounds.xcarchive archive
```

**Build outputs**:
- Debug app: `build/Build/Products/Debug-iphonesimulator/AsItSounds.app`
- Archive: `build/AsItSounds.xcarchive`
- Exported IPA: `build/export/AsItSounds.ipa` (after the export step)

**Common build errors**:

| Error | Cause | Fix |
| :-- | :-- | :-- |
| `There is no XCFramework found at '.../AisCoreFFI.xcframework'` | Forgot to run `core/scripts/build-ios.sh` | Run it (Step 3.5) |
| `cannot use bare repository '...' (safe.bareRepository is 'explicit')` | SwiftPM hits git's safe-repo policy on parens path | We bundle GRDB via local clone at `packages/GRDB` (already applied) |
| `Undefined symbols: _cblas_sgemm$NEWLAPACK$ILP64` | Accelerate framework not linked | `project.yml` already adds `sdk: Accelerate.framework` + 3 more SDKs |
| `Undefined symbols: _whisper_coreml_*` | xcframework assembly excluded `libwhisper.coreml.a` | `assemble-whisper-xcframework.sh` now includes all `.a` |
| `AppIntents: Invalid parameter type. AppEntity and AppEnum are the only allowed types` | A String parameter was used in an AppShortcut phrase | Expose only parameter-less intents via phrases (e.g. `OpenLatestProjectIntent`) |
| `unknown platform: xros` (vtool) | whisper.cpp's `build-xcframework.sh` tries to build a visionOS slice | Use our iOS-only `assemble-whisper-xcframework.sh` instead |
| `value of tuple type 'Void' has no member 'recordsToSave'` | iOS 17 changed `CKDatabase.add(op)` API | Use `database.modifyRecords(saving:deleting:savePolicy:)` (already applied) |

---

## 6. 로컬 실행 방법 / Running Locally

**[한국어]**

**Xcode GUI 방식 (권장)**:
1. `~/As-It-Sounds-app/ios/AsItSounds.xcodeproj` 열기
2. 좌상단 디바이스 선택 → iPhone 17 Pro Simulator (또는 실기)
3. ⌘R → 빌드 + 자동 실행

**CLI 방식**:
```bash
# 시뮬레이터 부팅
xcrun simctl boot "iPhone 17 Pro"
open -a Simulator

# 설치
xcrun simctl install "iPhone 17 Pro" \
  build/Build/Products/Debug-iphonesimulator/AsItSounds.app

# 실행
xcrun simctl launch "iPhone 17 Pro" com.asitsounds.ios

# 로그
xcrun simctl spawn "iPhone 17 Pro" log show \
  --last 5m --predicate 'process == "AsItSounds"'
```

**모델 사전 푸시 (옵션 — 첫 실행 142 MB 다운로드 회피)**:
```bash
APP_DATA=$(xcrun simctl get_app_container "iPhone 17 Pro" com.asitsounds.ios data)
mkdir -p "$APP_DATA/Library/Application Support/models"
cp ~/path/to/ggml-base.bin "$APP_DATA/Library/Application Support/models/"
```

**접속/포트**: 모바일 앱이므로 없음.

**실행 확인**:
- 시뮬에 4탭 (Transcribe / Library / Translit / Settings) 표시
- Translit 탭 입력 → 한글 + Whisper 시스템 정보 카드 표시 시 정상
- `xcrun simctl spawn ... log show` 로그에 FATAL/Crash 없음

**[English]**

**Xcode GUI (recommended)**:
1. Open `~/As-It-Sounds-app/ios/AsItSounds.xcodeproj`
2. Pick iPhone 17 Pro Simulator (or a real device) in the top-left
3. ⌘R → build + launch

**CLI**:
```bash
# Boot the simulator
xcrun simctl boot "iPhone 17 Pro"
open -a Simulator

# Install
xcrun simctl install "iPhone 17 Pro" \
  build/Build/Products/Debug-iphonesimulator/AsItSounds.app

# Launch
xcrun simctl launch "iPhone 17 Pro" com.asitsounds.ios

# Tail logs
xcrun simctl spawn "iPhone 17 Pro" log show \
  --last 5m --predicate 'process == "AsItSounds"'
```

**Pre-seed the Whisper model (optional — skip first-run 142 MB download)**:
```bash
APP_DATA=$(xcrun simctl get_app_container "iPhone 17 Pro" com.asitsounds.ios data)
mkdir -p "$APP_DATA/Library/Application Support/models"
cp ~/path/to/ggml-base.bin "$APP_DATA/Library/Application Support/models/"
```

**Ports**: None (mobile app).

**Verify**:
- 4 tabs appear in the simulator (Transcribe / Library / Translit / Settings)
- Typing in Translit shows Hangul + Whisper system info card → healthy
- `xcrun simctl spawn ... log show` shows no FATAL / Crash entries

---

## 7. 테스트 실행 / Running Tests

**[한국어]**

⚠️ **현재 본 프로젝트는 XCTest 또는 UI 테스트 타깃이 작성되어 있지 않습니다.** `project.yml` 에 test target 선언이 없고, `AsItSoundsTests/` 디렉터리 부재.

향후 추가 시 권장:

```bash
# 단위 테스트
xcodebuild test \
  -project AsItSounds.xcodeproj \
  -scheme AsItSounds \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:AsItSoundsTests

# UI 테스트
xcodebuild test \
  -project AsItSounds.xcodeproj \
  -scheme AsItSounds \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:AsItSoundsUITests

# 커버리지
xcodebuild test ... -enableCodeCoverage YES
xcrun xccov view --report .xcresult > coverage.txt
```

**픽스처 권고**:
- 음차 회귀 fixture: 데스크탑 `As-It-Sounds/.samples/01_19_04.mp3` 의 transcribe 결과 → `transliterate(text:)` 호출 → 30 set assertEqual.
- `Pipeline` happy path: mock `WhisperEngine` 와 mock `Translator` 주입하여 `PipelineRunner.run(...)` 동작 검증.
- Snapshot test: `swift-snapshot-testing` 라이브러리 + light/dark + 한국어/영어 매트릭스.

**[English]**

⚠️ **No XCTest or UI test targets exist today.** `project.yml` declares no test target, and there's no `AsItSoundsTests/` directory.

When tests are added, run them via:

```bash
# Unit tests
xcodebuild test \
  -project AsItSounds.xcodeproj \
  -scheme AsItSounds \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:AsItSoundsTests

# UI tests
xcodebuild test \
  -project AsItSounds.xcodeproj \
  -scheme AsItSounds \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -only-testing:AsItSoundsUITests

# Coverage
xcodebuild test ... -enableCodeCoverage YES
xcrun xccov view --report .xcresult > coverage.txt
```

**Recommended fixtures**:
- Transliteration regression: pull desktop `.samples/01_19_04.mp3` transcripts through `transliterate(text:)` and assert against 30 known outputs.
- `Pipeline` happy path: inject a mock `WhisperEngine` + mock `Translator` into `PipelineRunner.run(...)`.
- Snapshot tests: `swift-snapshot-testing` matrix over light/dark + en/ko.

---

## 8. 코드 기여 가이드라인 / Contribution Guidelines

**[한국어]**

⚠️ 별도 `CONTRIBUTING.md` 부재. 아래는 워크스페이스 PRD / 코드 패턴 추론.

- **브랜치 전략**:
  - `main` = 항상 빌드 가능한 단일 트렁크
  - `feature/<slug>` 짧게 살린 뒤 squash merge
  - 출시는 태그 (`v0.1.0`, `v0.1.1`, …)
- **커밋 메시지**:
  - 제목 1줄 ≤ 70자. 예: `feat(pipeline): inject Translator into runner`
  - 본문에 "why" 만 (diff 가 "what" 말해줌)
- **PR / 리뷰**:
  - `.github/workflows/ios.yml` 자동 빌드 통과 (현재 수동 트리거)
  - ≥ 1 reviewer 승인
  - whisper/UniFFI 변경 시 시뮬 + 실기 둘 다 검증 권고
- **코딩 스타일**:
  - SwiftUI: state hoisting, `@Observable` + `@State` 조합
  - Swift Strict Concurrency 활성 — `Sendable` 위반 경고는 ignore 금지
  - 명명: `*View` = SwiftUI 화면, `*Runner` / `*Coordinator` = 비동기 오케스트레이터
  - Obj-C++: `*Engine` 접미사, dealloc 에서 native 자원 정리 필수

**[English]**

⚠️ No dedicated `CONTRIBUTING.md`. The following is inferred from the workspace PRD and code patterns.

- **Branching**:
  - `main` = always-buildable trunk
  - `feature/<slug>` short-lived branches → squash-merged
  - Releases tagged `v0.1.0`, `v0.1.1`, …
- **Commits**:
  - Subject ≤ 70 chars. Example: `feat(pipeline): inject Translator into runner`
  - Body explains "why" — the diff already shows "what"
- **PR / review**:
  - `.github/workflows/ios.yml` must pass (currently manual trigger)
  - ≥ 1 reviewer
  - Verify whisper/UniFFI changes on both simulator and a real device
- **Style**:
  - SwiftUI: hoist state, mix `@Observable` and `@State`
  - Swift Strict Concurrency is on — never silence `Sendable` warnings
  - Naming: `*View` for SwiftUI screens, `*Runner` / `*Coordinator` for async orchestrators
  - Obj-C++: suffix with `Engine`; always free native resources in `dealloc`

---

## 9. 자주 묻는 질문 / FAQ

**[한국어]**

1. **Q: Xcode 가 "There is no XCFramework found at '.../AisCoreFFI.xcframework'" 오류로 빌드 실패합니다.**
   A: `core/scripts/build-ios.sh` 미실행. Step 3.5 실행하면 `core/target/AisCoreFFI.xcframework` 생성됨. paren-free 심볼릭 링크 (`~/As-It-Sounds-app`) 경로에서 Xcode 열기도 권장.

2. **Q: SwiftPM 이 "cannot use bare repository (safe.bareRepository is 'explicit')" 로 GRDB resolve 실패합니다.**
   A: 경로에 괄호 `(...)` 가 있을 때 xcodebuild SPM 의 git 호출이 막힙니다. **해결**: 본 프로젝트는 GRDB 를 `ios/packages/GRDB` 에 로컬 clone 하고 `project.yml` 의 `packages.GRDB.path: packages/GRDB` 로 우회 (현재 적용됨). Step 3.4 의 GRDB clone 단계 확인.

3. **Q: 시뮬레이터에서 앱이 부팅 후 즉시 종료됩니다 — Whisper 관련 crash?**
   A: 시뮬은 Core ML Neural Engine 가속 미지원이므로 모델 로드 단계가 느릴 수 있음. 그러나 crash 라면 `xcrun simctl spawn ... log show --last 1m --predicate 'process == "AsItSounds"'` 로 EXC_BAD_ACCESS / nil unwrap 확인. `WhisperEngine.mm` 의 `_ctx == NULL` 가드 체크.

4. **Q: AppShortcuts 빌드 시 "Invalid parameter type. AppEntity and AppEnum are the only allowed types" 오류.**
   A: AppShortcut phrase 의 파라미터는 AppEntity/AppEnum 만 허용. 문자열 파라미터는 phrase 에 노출 불가. `OpenLatestProjectIntent` 처럼 파라미터 없는 intent 만 phrase 에. `TransliterateIntent` 는 Shortcuts.app 에는 등록되되 phrase 없음.

5. **Q: `xcodegen generate` 했는데 새로 추가한 `.swift` 파일이 Xcode 에 안 보입니다.**
   A: `project.yml` 의 `sources:` 가 `AsItSounds` 디렉터리 통째 잡고 있으므로 디렉터리에 파일만 두면 자동 픽업. **`xcodegen generate` 를 매번 다시 실행하면 됨**. Xcode 안에서 "Add Files" 수동 추가하면 nuke 됨.

6. **Q: visionOS slice 빌드에서 vtool 이 `unknown platform: xros` 로 죽습니다.**
   A: whisper.cpp 의 `build-xcframework.sh` 가 모든 Apple 플랫폼 빌드 시도하지만 vtool 이 visionOS 인자 미지원. iOS device + sim slice 만 build 된 시점에 abort 됨. 그 시점에 `core/scripts/assemble-whisper-xcframework.sh` 가 iOS slices 만 모아 xcframework 조립.

7. **Q: TestFlight 업로드 시 "App Store Connect API key 가 만료됨" 오류.**
   A: App Store Connect → Users and Access → Keys 에서 새 키 발급 후 `secrets.APP_STORE_CONNECT_API_KEY*` 갱신. `.doc/signing.md` 의 시크릿 매트릭스 참조.

**[English]**

1. **Q: Xcode fails with "There is no XCFramework found at '.../AisCoreFFI.xcframework'".**
   A: You skipped `core/scripts/build-ios.sh`. Run it (Step 3.5) and it produces `core/target/AisCoreFFI.xcframework`. Also open Xcode via the paren-free symlink (`~/As-It-Sounds-app`).

2. **Q: SwiftPM can't resolve GRDB — "cannot use bare repository (safe.bareRepository is 'explicit')".**
   A: xcodebuild's SwiftPM hits git's safe-repo policy on paths containing parentheses. **Fix**: this project clones GRDB locally to `ios/packages/GRDB` and references it via `packages.GRDB.path: packages/GRDB` in `project.yml`. Re-check Step 3.4.

3. **Q: The app crashes on launch in the simulator — Whisper-related?**
   A: The simulator doesn't expose Core ML Neural Engine acceleration, so model load can be slow but shouldn't crash. If it does, run `xcrun simctl spawn ... log show --last 1m --predicate 'process == "AsItSounds"'` and look for EXC_BAD_ACCESS or nil-unwrap. Check the `_ctx == NULL` guard in `WhisperEngine.mm`.

4. **Q: AppShortcuts won't build — "Invalid parameter type. AppEntity and AppEnum are the only allowed types".**
   A: AppShortcut phrases only accept AppEntity / AppEnum parameters. Strings can't be inlined into phrases. Only parameter-less intents (e.g. `OpenLatestProjectIntent`) can be phrase-bound. `TransliterateIntent` is still callable from Shortcuts.app but without a spoken phrase.

5. **Q: I ran `xcodegen generate` but a new `.swift` file doesn't show up in Xcode.**
   A: `project.yml` recurses over the whole `AsItSounds` dir, so dropping a file in the right folder is enough. **Re-run `xcodegen generate`** every time you add a file. Don't use Xcode's "Add Files…" — `xcodegen generate` will overwrite it.

6. **Q: The visionOS slice build dies with `unknown platform: xros` from vtool.**
   A: whisper.cpp's `build-xcframework.sh` builds every Apple platform, but vtool can't tag visionOS yet. By the time it aborts, the iOS device and simulator slices are already built; our `core/scripts/assemble-whisper-xcframework.sh` collects them into a usable iOS-only xcframework.

7. **Q: TestFlight upload fails with "App Store Connect API key expired".**
   A: Generate a new key at App Store Connect → Users and Access → Keys, then update `secrets.APP_STORE_CONNECT_API_KEY*` in GitHub Secrets. See `.doc/signing.md` for the full matrix.
