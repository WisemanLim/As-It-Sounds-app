# As It Sounds — iPhone App PRD

> 🌐 [한국어](PRD-for-iPhone.md) · **English**

> Re-implementing the core value of the desktop/web (Tauri + Rust) as iOS-native. The key strategy is to **reuse the Rust core 1,713-line translit + phonetic rules asset as-is**.

- **Product name (code)**: `as-it-sounds-ios` (Bundle ID `com.asitsounds.ios`)
- **Platform**: iOS 17.0+ (iPhone), iPadOS 17.0+ (secondary)
- **App category**: Education / Productivity
- **Language**: Swift 5.10 / SwiftUI / Swift Concurrency
- **Core reuse**: Rust library → xcframework (UniFFI bindings)

---

## 1. Overview

iPhone users can **transliterate** multilingual audio/video (English, Japanese, French, etc.) **into Hangul exactly as it sounds** and view a **Korean translation** alongside it, all **on-device** without any external cloud upload. Media can be imported from the Files app, Photos, Voice Memos, or the Share Sheet.

### 1.1 Core Value (iPhone-specific vs. Desktop)

1. **On-device ASR** — Whisper.cpp + Core ML acceleration. Works in Airplane Mode with no network.
2. **Share Sheet integration** — Transcribe in one tap from Safari/Podcasts/Voice Memos via "Share → As It Sounds".
3. **Background processing** — `BGProcessingTask` keeps converting long files even on the lock screen.
4. **Widget / Live Activity** — Shows conversion progress on the lock screen.
5. **Apple Translation (iOS 17.4+) option** — On-device translation with no API key required.

### 1.2 Non-goals (MVP)

- Simultaneous Android release (separate track).
- Real-time microphone streaming STT (Phase 2).
- Multi-user / cloud sync (Phase 3, CloudKit candidate).
- Apple Vision Pro build.

---

## 2. Target Users / Use Scenarios

| Persona | Scenario |
| :-- | :-- |
| English learner (TOEIC, OPIc) | Screen-record a YouTube video → Share → Check pronunciation / translation |
| Commuting podcast listener | Podcasts → Share → Listen on the way to work, then review in Hangul |
| Interviewer / content creator | Record an interview in Voice Memos → Draft transcript + Korean translation |
| Japanese / French beginner | Drop a conversation mp3 → Follow along in Hangul transliteration instead of katakana / accents |

---

## 3. Technology Stack (iOS Native)

### 3.1 Rationale — Why Not Tauri Mobile

| Candidate | Chosen? | Reason |
| :-- | :-- | :-- |
| **Swift + SwiftUI native** | ✅ Adopted | Full access to App Store policies, background tasks, Share Sheet, Live Activity, Core ML. Optimal memory/battery. |
| Tauri 2 Mobile (iOS) | ❌ | Still alpha; background / Share Sheet / widget APIs are insufficient. WKWebView memory ceiling (~1 GB) is tight for Whisper inference. |
| React Native | ❌ | Bridge overhead. Awkward whisper.cpp integration. |
| Flutter | ❌ | Rust FFI is possible but Swift is far more natural. |
| Capacitor / Cordova | ❌ | Not a real fit. |

### 3.2 Layer-by-Layer Stack

| Layer | Choice | Notes |
| :-- | :-- | :-- |
| UI | **SwiftUI + Observation** (iOS 17 `@Observable`) | UIKit compatibility via `UIViewControllerRepresentable` |
| State / DI | **Swift Concurrency** (`actor`, `Task`, `AsyncStream`) + simple manual DI | TCA/Composable also an option (depending on team familiarity) |
| Design system | **SF Symbols + Dynamic Type + Color Assets** + custom tokens | Tailwind replacement — follows Apple HIG |
| Routing | `NavigationStack` + `NavigationPath` | Desktop React Router equivalent |
| Persistence | **SQLite via GRDB.swift 6.x** | Same schema as Rust-side `rusqlite`. SwiftData considered but lower migration/FTS flexibility |
| ASR | **whisper.cpp + Core ML encoder** (bundled xcframework or integrated at build time) | M1+ NPU acceleration |
| Transliteration (English→Hangul, multilingual) | **Rust `translit.rs` as-is** → UniFFI → Swift | **No reimplementation** (1,713 lines, core IP) |
| Translation | **Abstract Translator protocol**: Apple Translation (iOS 17.4+) · MyMemory · LibreTranslate · DeepL | Apple Translation: zero API key · on-device |
| Media decode | **AVFoundation** (`AVAudioFile`, `AVAssetReader`) → 16 kHz mono Float PCM | No ffmpeg needed. HEVC/HLS handled by the OS |
| Media playback | `AVPlayer` + `AVPlayerLayer` (video), `AVAudioPlayer` (audio) | Native seek / loop segment |
| Background processing | **BGProcessingTaskRequest** (long-running) + `URLSession.background` (downloads) | Whisper model download / large file conversion |
| File import | **UIDocumentPickerViewController**, **PHPickerViewController**, **Share Sheet (Share Extension)**, **Drag & Drop** | iPad split-view supported |
| Keys / secrets | **Keychain** (`SecItem*`) | API key storage (DeepL / LibreTranslate) |
| Analytics | **MetricKit + OSLog (signpost)** + optional PostHog | Crashes: Apple Crash Reporter |
| Payments | **StoreKit 2** | Unlock Pro model downloads / large file conversion (optional) |

### 3.3 Rust Core ↔ Swift Bridge

```
┌──────────────── iOS App (Swift) ────────────────┐
│  SwiftUI Views ─ ViewModels (actor)              │
│        │                                         │
│        ▼  (async/await)                          │
│  IPC Façade: PipelineService                     │
│        │                                         │
│        ├─▶  WhisperEngine.swift (Obj-C++ bridge) │
│        │     └─▶ whisper.cpp + ggml + Core ML    │
│        │                                         │
│        ├─▶  AudioDecoder.swift (AVFoundation)    │
│        │                                         │
│        ├─▶  Translator.swift (protocol)          │
│        │     ├─ AppleTranslator                  │
│        │     ├─ MyMemoryTranslator              │
│        │     └─ LibreTranslateTranslator        │
│        │                                         │
│        └─▶  AisCore.framework (UniFFI bindings)  │
│                  │                               │
│                  ▼                               │
│         ┌─────────────────────────────┐         │
│         │  Rust static lib (.a/.xcfwk)│         │
│         │   • translit.rs  (1713 LOC) │         │
│         │   • script detect           │         │
│         │   • phonetic rules engine   │         │
│         └─────────────────────────────┘         │
└──────────────────────────────────────────────────┘
```

**Selected tool**: [UniFFI (Mozilla)](https://github.com/mozilla/uniffi-rs)
- `.udl` interface definition → Swift bindings auto-generated
- Alternatives: `cbindgen` + manual Swift FFI (less safe), `swift-bridge` (active but small ecosystem)

**Build pipeline**:
```
cargo build --release --target aarch64-apple-ios       # device
cargo build --release --target aarch64-apple-ios-sim   # simulator (ARM)
cargo build --release --target x86_64-apple-ios        # Intel sim (legacy build machines)
xcodebuild -create-xcframework -library ... -output AisCore.xcframework
```

CI: Auto-built on GitHub Actions `macos-14` runner, then distributed as a SwiftPM package (in-repo binary target).

### 3.4 Whisper.cpp iOS Integration

- Wraps the official [whisper.cpp/examples/whisper.objc](https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.objc) pattern in an Obj-C++ wrapper.
- Core ML encoder generation: pre-generate `ggml-base-encoder.mlmodelc` with `python3 models/generate-coreml-model.sh base` and place it in the app bundle or `Application Support`.
- Metal/GPU acceleration is ON by default (safe because iOS only targets Apple Silicon).
- Memory: `base` ≈ 200 MB peak, `small` ≈ 600 MB, `medium` ≈ 1.5 GB → MVP defaults to **`tiny`/`base`**; `small` is allowed only on devices with RAM ≥ 6 GB (iPhone 15 Pro+).

### 3.5 Recommended Model Matrix

| Device | RAM | Recommended Model | Notes |
| :-- | :-- | :-- | :-- |
| iPhone 12/13 (4 GB) | 4 GB | `tiny.en` (39 MB) / `tiny` (75 MB) | base is possible but causes thermal throttle |
| iPhone 14 / SE 3 (6 GB) | 6 GB | `base` (142 MB) | Default |
| iPhone 15 Pro / 16 / 16 Pro | 8 GB | `small` (466 MB) | Recommended — quality↑, speed OK with Core ML acceleration |
| iPad Pro M-series | 8–16 GB | `medium` (1.5 GB) | Optional |

---

## 4. Functional Requirements

### 4.1 MVP (Phase 1, Initial Release)

| ID | Feature | Description |
| :-- | :-- | :-- |
| F-1 | Project CRUD | Left tab / `NavigationSplitView` (iPad). iPhone uses a single stack. |
| F-2 | Multi-path file import | (a) Files app picker, (b) Photos (PHPicker, video), (c) Voice Memos import, (d) Share Sheet, (e) Drag & Drop (iPad), (f) AirDrop |
| F-3 | Supported formats | `m4a, mp3, wav, aac, caf, mp4, mov, m4v` (everything AVFoundation supports). HEVC/H.265 ok. |
| F-4 | Automatic language detection | Whisper auto-detect; user can also force a specific language. |
| F-5 | Pipeline execution | `Start Transcription` button → decode → ASR → transliterate → translate. Progress % + ETA. |
| F-6 | 3-panel segment view | EN / Hangul transliteration / KO translation. Currently playing segment highlighted. |
| F-7 | Seek / tap jump | Tapping a segment calls `AVPlayer.seek(to:)`. |
| F-8 | Media controls | Play/pause, 0.5/0.75/1/1.25/1.5/2× speed, ±5 s skip, A-B loop |
| F-9 | Local storage | GRDB. Included in iCloud backup (Documents directory). |
| F-10 | Settings | Model select/download, translation engine, dark/light mode, font size (Dynamic Type), data deletion |
| F-11 | Model manager | Download from Hugging Face with progress indicator. Background URLSession passes through the lock screen. |
| F-12 | Share Sheet (Share Extension) | "Share → As It Sounds" from an external app → import as new project and start conversion |
| F-13 | Share / copy results | Per-segment copy, full-text share (UIActivityViewController) |
| F-14 | Offline mode indicator | Explicitly shown when the selected translation engine requires network |

### 4.2 Phase 2 (Extensions)

- **SRT/VTT/TXT/Markdown export** — `UIDocumentPickerViewController(forExporting:)`
- **Segment editing** → automatic transliteration/translation regeneration
- **Spotlight Index (Core Spotlight)** — expose segment content in OS-wide search
- **Live Activity / Dynamic Island** — conversion progress, completion notification
- **Widget (WidgetKit)** — recent projects, learning statistics
- **Shortcuts (App Intents)** — "Hey Siri, transcribe today's recording"
- **AirPlay / Bluetooth headphone** shadow-speaking (loop segment)
- **Live mic STT** — `AVAudioEngine` + Whisper VAD chunks

### 4.3 Phase 3

- iPad multi-window / Stage Manager
- macOS Catalyst build (or SwiftUI multiplatform unification)
- CloudKit sync (optional)
- Study mode (enter correct answer → score)
- Pronunciation score (DTW + MFCC) — compare user pronunciation vs. original

### 4.4 Non-functional Requirements

| Item | Target |
| :-- | :-- |
| **Cold start** | < 1.5 s (iPhone 14) |
| **First segment displayed** | ≤ 8 s for a 30-second video (base, iPhone 15) |
| **RTF** | base ≤ 0.4, small ≤ 0.8 (iPhone 15 Pro, Core ML) |
| **Memory peak** | ≤ 350 MB while running base model |
| **Battery** | ≤ 5% drain for a 10-minute conversion (iPhone 15) |
| **App binary** | < 80 MB (excluding models, with App Thinning) |
| **App size policy** | Models downloaded on first launch — avoids App Store 80 MB cellular download limit |
| **Accessibility** | Full support for VoiceOver, Dynamic Type, Reduce Motion |
| **Privacy** | Microphone/Photos permissions triggered only on explicit use. Zero external transmissions except translation API calls |

---

## 5. UI/UX (HIG-based)

### 5.1 Information Architecture

```
TabView
├── Home          (recent projects, quick import)
├── Library       (full project list, search, filter)
├── Record        (Phase 2 — live recording / STT)
└── Settings      (model, translation, language, about)
```

iPhone: 4-tab `TabView` at the bottom. iPad: `NavigationSplitView` 3-pane (sidebar / list / detail).

### 5.2 Key Screens (Text Wireframes)

**Home**
```
[Logo]                                    [⚙]
─────────────────────────────────────────────
Today's Study
  [▶ Resume: Interview 02 — 13:24]
─────────────────────────────────────────────
Quick Start
  [📁 From Files]  [🎞 From Photos]  [🎙 Record]
─────────────────────────────────────────────
Recent Projects
  • TOEIC LC 03    English · 8m · Conversion complete
  • Meeting 0524   Korean · 22m · Processing 41%
```

**Project Detail**
```
< Back   Interview 02           [⋯ Share/Delete]
────────────────────────────────────────────
[AVPlayer / or audio waveform]
────────────────────────────────────────────
[00:03 ─ 00:05]               [⋯]
EN  As it sounds, it works.
한  애즈 잇 사운즈, 잇 웍스.
KO  들리는 대로, 동작합니다.
────────────────────────────────────────────
[00:05 ─ 00:08]
...
────────────────────────────────────────────
Bottom floating control bar
  [↺ 5] [▶︎] [↻ 5]   1× ▾   A↔B
```

**Settings**
```
Whisper Model
  Current: base (142 MB)       [Change >]
  Available space: 21.4 GB

Translation Engine
  Apple Translation            [Default]
  ▸ MyMemory · LibreTranslate · DeepL · Off

Display
  Dynamic Type                 [System]
  Dark Mode                    [System]

Data
  Storage used: 1.2 GB
  [Clear Cache]  [Delete All Data]

About
  Version 1.0 (1)
  Open Source Licenses >
```

### 5.3 Design Tokens

- **Color**: System colors first (`Color.accentColor`, `.primary`, `.secondary`). Auto dark/light. Accent: `#4F8DFF` (preserving Gemini tone).
- **Typography**: `SF Pro` (automatic) + `SF Mono` (timecodes). Full Dynamic Type support.
- **Spacing**: 4 / 8 / 16 / 24 / 32 grid.
- **Corner radius**: 12 (cards), 8 (buttons), 28 (large sheets).
- **Motion**: Fade-only when `Reduce Motion` is ON. Default: `spring(response:0.4, damping:0.8)`.

### 5.4 Accessibility Checklist

- VoiceOver labels: segment = combined label "Timecode 00:03 to 00:05, English: As it sounds it works, Hangul transliteration: …, Korean: …".
- Switch Control / AssistiveTouch — all action hit targets ≥ 44 pt.
- Color blindness: progress state indicated by color + icon + text (triple redundancy).
- Captions: the app is itself a captioning tool, so dogfood it.

---

## 6. Architecture

### 6.1 Modules (Swift Package + Xcode Targets)

```
AsItSounds.xcodeproj
├── App                  (target: main app)
│   ├── Features/
│   │   ├── Home/
│   │   ├── Library/
│   │   ├── Project/
│   │   ├── Settings/
│   │   └── ModelManager/
│   ├── Shared/
│   │   ├── DesignSystem/
│   │   ├── Models/
│   │   └── Extensions/
│   └── Resources/
├── ShareExtension       (target: NSExtension — Share Sheet)
├── PipelineKit          (SwiftPM local) — actor-based pipeline orchestration
├── WhisperKitBridge     (SwiftPM local) — Obj-C++ wrapper
├── AisCoreFFI           (SwiftPM local, binaryTarget) — UniFFI output
├── DBKit                (SwiftPM local) — GRDB wrapper, schema, migrations
└── TranslateKit         (SwiftPM local) — Translator protocol + 4 implementations
```

### 6.2 Pipeline

```swift
actor PipelineService {
    func run(fileIds: [UUID], options: PipelineOptions) -> AsyncStream<PipelineEvent>
}

enum PipelineEvent {
    case modelDownloading(progress: Double)
    case decoding(fileId: UUID, progress: Double)
    case asrProgress(fileId: UUID, progress: Double)
    case segment(Segment)           // partial
    case translatingSegment(idx: Int)
    case fileDone(fileId: UUID)
    case error(fileId: UUID?, error: AisError)
}
```

Processing stages:

```
MediaFile (Files / PHPicker / Share Sheet)
  └─▶ AVAssetReader  : 16 kHz mono Float32 PCM
        └─▶ WhisperEngine (Core ML)  : segments[{start,end,text,lang}]
              ├─▶ AisCoreFFI.transliterate(text, scriptHint) → Hangul
              └─▶ TranslateKit.translate(text, src→ko) → KO (async, parallel)
                    └─▶ DBKit.insertSegments + AsyncStream.yield(.segment(...))
```

Threading: decode runs on `Task.detached(priority: .userInitiated)`, ASR on a dedicated `OperationQueue (qos: .userInitiated, maxConcurrent: 1)` (single model instance), translation runs N segments in parallel via `TaskGroup`.

### 6.3 Data Model (GRDB)

```swift
// Identical 1:1 to the existing desktop SQLite schema
struct Project: Codable, Identifiable, FetchableRecord, PersistableRecord {
    var id: String              // UUID string
    var name: String
    var createdAt: Date
    var updatedAt: Date
}

struct MediaFile: Codable, Identifiable, FetchableRecord, PersistableRecord {
    var id: String
    var projectId: String
    var path: String            // Documents-relative
    var displayName: String
    var mime: String?
    var durationMs: Int64?
    var status: Status          // pending | processing | done | error
    var error: String?
    var createdAt: Date
}

struct Segment: Codable, Identifiable, FetchableRecord, PersistableRecord {
    var id: String
    var fileId: String
    var idx: Int
    var startMs: Int64
    var endMs: Int64
    var textEn: String
    var textHangul: String?
    var textKo: String?
}
```

Migrations are version-managed via `DatabaseMigrator`. **FTS5** virtual table added (Phase 2 search).

### 6.4 Translation Engine Abstraction

```swift
protocol Translator: Sendable {
    var name: String { get }
    var requiresNetwork: Bool { get }
    func translate(_ text: String, from: String, to: String) async throws -> String
}

struct AppleTranslator: Translator       { ... }    // iOS 17.4+, TranslationSession
struct MyMemoryTranslator: Translator    { ... }    // free, no key
struct LibreTranslateTranslator: Translator { ... } // self-host URL + optional key
struct DeepLTranslator: Translator       { ... }    // API key required
```

### 6.5 Model Storage

- Location: `Application Support/com.asitsounds.ios/models/ggml-<name>.bin` + `.mlmodelc/`
- iCloud Backup exclusion flag: `URLResourceValues.isExcludedFromBackup = true` (prevents large files from flooding backups)
- When disk is full: clear cache first → prompt user to change model

### 6.6 Background Processing

| Scenario | Strategy |
| :-- | :-- |
| Model download | `URLSession(configuration: .background)` — OS completes even after lock / termination |
| Long file conversion (>30 s) | `BGProcessingTaskRequest(identifier: "com.asitsounds.ios.transcribe")` + checkpoint save. Resumes on next launch |
| Short file | Foreground only; ~30 s grace period via `UIApplication.beginBackgroundTask` |

### 6.7 Error Handling

```swift
enum AisError: LocalizedError, Sendable {
    case audioDecode(reason: String)
    case modelMissing(name: String)
    case modelDownloadFailed(URLError?)
    case whisperRuntime(code: Int32, message: String)
    case translateFailed(engine: String, underlying: Error)
    case diskFull(neededMB: Int, freeMB: Int)
    case cancelled
}
```

Per-segment failures go to a retry queue; per-file failures show a UI badge. Same policy as the desktop.

---

## 7. Permissions / Info.plist Keys

| Key | Reason |
| :-- | :-- |
| `NSMicrophoneUsageDescription` | Phase 2 live STT (declared early in MVP for future use) |
| `NSPhotoLibraryUsageDescription` | Importing videos from Photos |
| `NSDocumentsFolderUsageDescription` | Files app import |
| `NSAppleEventsUsageDescription` | Shortcuts (App Intents) |
| `NSUserActivityTypes` | Handoff / Spotlight |
| Background Modes | `audio`, `processing`, `fetch` |
| `UIBackgroundModes` → processing | BGTask |
| `UIFileSharingEnabled` = YES | Export via iTunes/Finder |
| `LSSupportsOpeningDocumentsInPlace` = YES | In-place external files |
| `UTExportedTypeDeclarations` | `.ais` project bundle (Phase 2 export) |

---

## 8. Security / Privacy

- **App Transport Security**: ON by default; per-domain exceptions only for MyMemory/LibreTranslate/DeepL domains.
- **Sandbox**: All user files are copied inside Documents (PHPicker forces a copy). External paths are never persisted.
- **Keychain**: API keys (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
- **Logging**: No PII — text content must not be logged. Only length/language/model are counted.
- **App Privacy Manifest (PrivacyInfo.xcprivacy)**: Required by iOS 17. `NSPrivacyAccessedAPITypes` recorded precisely.
- **Data Collection declaration**: App Store Connect — "Diagnostics: Crash Data (linked, but not used for tracking)" only.

---

## 9. Test Strategy

| Layer | Tool |
| :-- | :-- |
| Unit (Swift) | **Swift Testing** (Xcode 16) — actor and async friendly. XCTest compatible |
| Unit (Rust core) | Existing `cargo test` (translit accuracy regression — same fixtures as desktop) |
| Snapshot UI | `swift-snapshot-testing` (light/dark, sizeCategory matrix) |
| Integration (pipeline) | XCUITest + short .wav fixtures (reuse `.samples/*.mp3` → converted `.wav`) |
| Performance | XCTest Metrics: `clock`, `memory`, `disk`. 3-device matrix: iPhone 12/14/15 Pro |
| Regression — transliteration accuracy | Existing fixtures from Rust workspace (`test.sh`) — run on every PR in CI |

Success criteria (same as desktop PRD):
- Segment accuracy ≥ 85% on a 10-minute English video
- Transliteration readability ≥ 4 / 5 (beta users)
- Cold start → first segment ≤ 10 s (base, iPhone 15)
- 100% functional in Airplane Mode (when translation engine = Apple/Off)

---

## 10. Deployment / Build

### 10.1 Build System

- Xcode 16+, Swift 5.10+
- SwiftPM (external dependencies)
- No CocoaPods/Carthage (unnecessary)
- **Local packages**: all Section 6.1 modules are in-repo via SwiftPM `path:`

### 10.2 External Dependencies (SwiftPM)

| Package | Purpose | License |
| :-- | :-- | :-- |
| [groue/GRDB.swift](https://github.com/groue/GRDB.swift) | SQLite | MIT |
| [pointfreeco/swift-snapshot-testing](https://github.com/pointfreeco/swift-snapshot-testing) | UI snapshots (testing) | MIT |
| [apple/swift-collections](https://github.com/apple/swift-collections) | OrderedDict, etc. | Apache-2 |
| [apple/swift-log](https://github.com/apple/swift-log) | Logging abstraction | Apache-2 |
| [ggerganov/whisper.cpp](https://github.com/ggerganov/whisper.cpp) | ASR (submodule or prebuilt xcframework) | MIT |
| UniFFI (Mozilla) | Rust ↔ Swift | MPL-2 |

Optional:
| Package | Purpose |
| :-- | :-- |
| [argmaxinc/WhisperKit](https://github.com/argmaxinc/WhisperKit) | Pure-Swift Whisper (Core ML native). Alternative to whisper.cpp — A/B evaluation |
| [pointfreeco/swift-composable-architecture](https://github.com/pointfreeco/swift-composable-architecture) | State management (optional) |

**whisper.cpp vs WhisperKit decision criteria**:
- whisper.cpp: same engine as desktop/web, fewer regressions, uses Core ML encoder only. Recommended (MVP).
- WhisperKit: pure Swift, loads MLPackage directly, more iOS-friendly. Consider switching after verifying output equivalence with transliteration/translation results.

### 10.3 CI/CD

- GitHub Actions `macos-14` (Apple Silicon) runner
- Steps: Rust xcframework build → SwiftPM cache restore → Xcodebuild test → archive → TestFlight upload (`fastlane match` + `pilot`)
- Secrets: `APP_STORE_CONNECT_API_KEY` (App Store Connect API), `APPLE_TEAM_ID`, `MATCH_PASSWORD`
- Code signing: **fastlane match** recommended (PR builds = development, main merge = TestFlight)

### 10.4 App Store Submission Checklist

- 4-size screenshots (6.7", 6.1", 5.5", iPad 12.9")
- 30-second promo video (optional)
- App Privacy answers
- Export Compliance (whisper.cpp = no standard encryption → "No" possible, ATS only)
- Category: Education (sub: Productivity)
- Pricing: Free + IAP (unlock Pro model) or Free

---

## 11. Roadmap (Proposed)

| Phase | Duration (business days) | Deliverables |
| :-- | :-- | :-- |
| **0. Initial setup** | 5d | Xcode project, SwiftPM modules, CI skeleton, Rust xcframework build verified |
| **1. Whisper integration** | 7d | Single short .wav → segments displayed (engine demo, UI-agnostic) |
| **2. Rust transliteration bridge** | 4d | UniFFI bindings, fixture parity with desktop confirmed |
| **3. UI shell (Home/Library/Project)** | 8d | End-to-end navigation with mock data |
| **4. File import 4 paths** | 5d | Files / PHPicker / Share Sheet / Drag-Drop |
| **5. Pipeline execution + AsyncStream** | 6d | Real audio → results displayed |
| **6. Translation engines ×4** | 4d | Apple/MyMemory/Libre/DeepL toggle |
| **7. Model manager + background** | 5d | Download progress, BGTask conversion |
| **8. Settings / polish / accessibility** | 4d | Settings, VoiceOver, Dynamic Type |
| **9. Beta TestFlight** | 3d | 50 external beta testers |
| **10. App Store submission** | 3d | Screenshots, metadata, review response |

Total: **approximately 54 business days** (≈ 11 weeks, solo dev). With 2 people: ~7 weeks.

---

## 12. Risks / Mitigations

| Risk | Impact | Mitigation |
| :-- | :-- | :-- |
| Whisper model memory (older iPhones) | OOM crash | Auto-guard per device: 4 GB RAM → force tiny, 6 GB → force base |
| Rust xcframework build complexity | High barrier for new devs | Distribute CI output as SwiftPM `binaryTarget(url:)`; local build is optional |
| App Store review: "third-party content auto-download" | Model auto-download may be rejected | Explicit consent dialog on first launch with Wi-Fi recommendation |
| Apple Translation regional availability | Fallback needed in some countries | Automatic fallback priority chain (Apple → MyMemory → Off) |
| Background processing time limit | Long media may not finish | Per-segment checkpoints; resume from where left off on app restart |
| HEVC/HEIC codec not supported on some devices | Rare but possible | AVAssetReader failure → clear error message, .wav re-encoding option |
| Transliteration quality (multilingual) | Reduced learning effectiveness | Desktop regression fixtures reused as-is → equivalence guaranteed |
| Build size (whisper.cpp + Core ML) | 60 MB+ even after App Thinning | Models downloaded dynamically (not bundled in app) |
| GRDB ↔ Rust SQLite concurrent access | Possible data corruption (if Rust also uses SQLite) | On iOS, **Rust core does not use SQLite** — translit only. DB is 100% Swift |

---

## 13. Code / IP Reuse Matrix (Summary)

| Asset | Location | iOS Reuse Method |
| :-- | :-- | :-- |
| `translit.rs` (1,713 LOC, multilingual phonetic rules) | `src-tauri/src/core/translit.rs` | **Compiled as-is**, exposed via UniFFI |
| `models.rs`, `error.rs` | Same | Partial type mirroring (Swift side) |
| `asr.rs` (whisper-rs wrapper) | Rust | Not reused — Swift calls whisper.cpp directly (performance / Core ML) |
| `media.rs` (ffmpeg sidecar) | Rust | Not reused — AVFoundation used instead |
| `translate.rs` (MyMemory/Libre/DeepL) | Rust | Reference only — rewritten as Swift `TranslateKit` (adds Apple Translation) |
| `db.rs` (rusqlite) | Rust | Reference only — rewritten with GRDB. **Schema kept 1:1** |
| `pipeline.rs` | Rust | Reimplemented as `actor PipelineService` |
| Frontend React | `src/` | Not reused — new SwiftUI |
| `.samples/*.mp3` fixtures | repo root | **Used as-is for regression test input** |

**Conclusion**: Only `translit.rs`, the highest-IP-value asset in the Rust core, is brought over unchanged; everything else is rewritten as iOS-native. Maintainable scope, leveraging platform strengths.

---

## 14. Success Metrics (KPIs)

- **Day-30 retention** ≥ 25%
- **First conversion completion rate** ≥ 70% (users who installed the app and saw their first result)
- **App Store rating** ≥ 4.5
- **Crash-free sessions** ≥ 99.7%
- **Segment accuracy** ≥ 85% (English), ≥ 78% (Japanese/French)
- **Beta NPS** ≥ 40

---

## 15. Decision Summary (Architectural Decisions)

| ADR | Decision |
| :-- | :-- |
| ADR-001 | Tauri Mobile not adopted. Swift native. |
| ADR-002 | Only Rust `translit.rs` reused, exposed via UniFFI. |
| ADR-003 | ASR uses whisper.cpp + Core ML encoder (MVP). WhisperKit evaluated in Phase 2. |
| ADR-004 | Media decode via AVFoundation. No ffmpeg. |
| ADR-005 | DB is GRDB.swift. Rust core does not access the DB. |
| ADR-006 | Default translation = Apple Translation (iOS 17.4+), fallback MyMemory. |
| ADR-007 | Models downloaded on first launch. Not bundled in the app. |
| ADR-008 | Minimum OS = iOS 17.0. (`@Observable`, App Intents, background improvements) |
| ADR-009 | State management via Swift Concurrency + `@Observable`. No TCA (MVP). |
| ADR-010 | Design prioritizes standard HIG components. Minimal customization. |

---

## 16. Appendix — References

- whisper.cpp iOS guide: <https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.objc>
- Core ML Whisper conversion: <https://github.com/ggerganov/whisper.cpp/blob/master/models/README.md#core-ml-support>
- WhisperKit (Argmax): <https://github.com/argmaxinc/WhisperKit>
- UniFFI guide: <https://mozilla.github.io/uniffi-rs/>
- Apple Translation framework: <https://developer.apple.com/documentation/translation>
- GRDB.swift: <https://github.com/groue/GRDB.swift>
- Apple HIG: <https://developer.apple.com/design/human-interface-guidelines/>
- Background Tasks: <https://developer.apple.com/documentation/backgroundtasks>
- Share Extension: <https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/ShareSheet.html>
- App Privacy Manifests: <https://developer.apple.com/documentation/bundleresources/privacy_manifest_files>
- Desktop PRD (parent): `../../NGL(Go,Rust,...)/As-It-Sounds/.doc/PRD.md`
- Desktop README: `../../NGL(Go,Rust,...)/As-It-Sounds/README.md`
- Web deployment README: `../../NGL(Go,Rust,...)/As-It-Sounds/README-Web.md`
