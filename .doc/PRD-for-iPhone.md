# As It Sounds — iPhone App PRD

> 데스크탑/웹 (Tauri + Rust) 의 핵심 가치를 iOS-네이티브로 재구현. **Rust 코어 1713줄 translit + 음운 규칙 자산을 그대로 재사용**하는 게 핵심 전략.

- **제품명(코드)**: `as-it-sounds-ios` (Bundle ID `com.asitsounds.ios`)
- **플랫폼**: iOS 17.0+ (iPhone), iPadOS 17.0+ (보조)
- **앱 분류**: Education / Productivity
- **언어**: Swift 5.10 / SwiftUI / Swift Concurrency
- **코어 재사용**: Rust 라이브러리 → xcframework (UniFFI 바인딩)

---

## 1. 개요

iPhone 사용자가 외부 클라우드 업로드 없이 **로컬 디바이스에서** 영어·일본어·프랑스어 등 다국어 음성/영상을 **들리는 발음 그대로 한글로 음차** 하고 **한국어 번역** 까지 함께 본다. Files 앱·사진앨범·녹음·공유 시트에서 모든 미디어 입수 가능.

### 1.1 핵심 가치 (데스크탑 대비 iPhone 특화)

1. **온디바이스 ASR** — Whisper.cpp + Core ML 가속. 네트워크 없이 비행기 모드에서도 동작.
2. **공유 시트 통합** — Safari/Podcast/녹음앱에서 "공유 → As It Sounds" 한 번에 전사.
3. **백그라운드 처리** — `BGProcessingTask` 로 긴 파일 잠금 화면에서도 계속 변환.
4. **위젯/Live Activity** — 변환 진행률 잠금화면 표시.
5. **Apple Translation (iOS 17.4+) 옵션** — 키 없이 온디바이스 번역.

### 1.2 비목표(Non-goals, MVP)

- Android 동시 출시 (별도 트랙).
- 실시간 마이크 스트리밍 STT (Phase 2).
- 다중 사용자/클라우드 동기화 (Phase 3, CloudKit 후보).
- Apple Vision Pro 빌드.

---

## 2. 타겟 사용자 / 사용 시나리오

| 페르소나 | 시나리오 |
| :-- | :-- |
| 영어 학습자 (TOEIC, OPIc) | YouTube 영상을 화면녹화 → 공유 → 발음/번역 확인 |
| 통근중 팟캐스트 청취자 | Podcasts → 공유 → 출근길에 듣고 다시 한글로 검수 |
| 인터뷰어/콘텐츠 제작자 | 음성 메모로 인터뷰 녹음 → 초안 transcript + 한국어 번역 |
| 일본어/프랑스어 입문자 | 회화 mp3 드롭 → 카타카나·악센트 대신 한글 음차로 따라 읽기 |

---

## 3. 기술 스택 (iOS 네이티브)

### 3.1 선정 사유 — Tauri Mobile 안 쓰는 이유

| 후보 | 선택? | 이유 |
| :-- | :-- | :-- |
| **Swift + SwiftUI 네이티브** | ✅ 채택 | App Store 정책, 백그라운드 태스크, 공유 시트, Live Activity, Core ML 풀 액세스. 메모리/배터리 최적. |
| Tauri 2 Mobile (iOS) | ❌ | 아직 alpha 단계, 백그라운드/공유 시트/위젯 API 미흡. WKWebView 메모리 한계(~1GB) 가 Whisper 추론에 타이트. |
| React Native | ❌ | Bridge 오버헤드. whisper.cpp 통합 어색. |
| Flutter | ❌ | Rust FFI 가능하지만 Swift 가 훨씬 자연스러움. |
| Capacitor / Cordova | ❌ | 동상이몽. |

### 3.2 레이어별 스택

| 계층 | 선택 | 비고 |
| :-- | :-- | :-- |
| UI | **SwiftUI + Observation** (iOS 17 `@Observable`) | UIKit 호환은 `UIViewControllerRepresentable` |
| 상태/DI | **Swift Concurrency** (`actor`, `Task`, `AsyncStream`) + 간단 manual DI | TCA/Composable 도 옵션 (팀 익숙도에 따라) |
| 디자인 시스템 | **SF Symbols + Dynamic Type + Color Assets** + 자체 토큰 | Tailwind 대체 — Apple HIG 준수 |
| 라우팅 | `NavigationStack` + `NavigationPath` | 데스크탑 React Router 대응 |
| 영구 저장 | **SQLite via GRDB.swift 6.x** | Rust 측 `rusqlite` 와 같은 schema. SwiftData 도 검토했으나 마이그레이션/FTS 자유도 ↓ |
| ASR | **whisper.cpp + Core ML encoder** (xcframework 동봉 또는 빌드 시 통합) | M1+ NPU 가속 |
| 음차 (영어→한글, 다국어) | **Rust `translit.rs` 그대로** → UniFFI → Swift | **재구현 금지** (1713 줄, 핵심 IP) |
| 번역 | **번역 엔진 추상 프로토콜**: Apple Translation (iOS 17.4+) · MyMemory · LibreTranslate · DeepL | Apple Translation 은 키 0 · 온디바이스 |
| 미디어 디코드 | **AVFoundation** (`AVAudioFile`, `AVAssetReader`) → 16 kHz mono Float PCM | ffmpeg 불필요. HEVC/HLS 도 OS 처리 |
| 미디어 재생 | `AVPlayer` + `AVPlayerLayer` (영상), `AVAudioPlayer` (음성) | seek/구간 반복 native |
| 백그라운드 처리 | **BGProcessingTaskRequest** (장시간) + `URLSession.background` (다운로드) | Whisper 모델 다운로드/큰 파일 변환 |
| 파일 입수 | **UIDocumentPickerViewController**, **PHPickerViewController**, **공유 시트 (Share Extension)**, **Drag & Drop** | iPad split view 도 지원 |
| 키/시크릿 | **Keychain** (`SecItem*`) | API 키 (DeepL/LibreTranslate) 저장 |
| 분석 | **MetricKit + OSLog (signpost)** + 옵션 PostHog | 크래시: Apple Crash Reporter |
| 결제 | **StoreKit 2** | Pro 모델 다운로드/큰 파일 변환 잠금 해제 (선택) |

### 3.3 Rust 코어 ↔ Swift 브릿지

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

**선정 도구**: [UniFFI (Mozilla)](https://github.com/mozilla/uniffi-rs)
- `.udl` 인터페이스 정의 → Swift 바인딩 자동 생성
- 대안: `cbindgen` + 수동 Swift FFI (덜 안전), `swift-bridge` (활발하지만 small ecosystem)

**빌드 파이프라인**:
```
cargo build --release --target aarch64-apple-ios       # 실기
cargo build --release --target aarch64-apple-ios-sim   # 시뮬레이터 (ARM)
cargo build --release --target x86_64-apple-ios        # Intel sim (구형 빌드머신용)
xcodebuild -create-xcframework -library ... -output AisCore.xcframework
```

CI: GitHub Actions `macos-14` 러너에서 자동 빌드 후 SwiftPM 패키지로 배포 (in-repo binary target).

### 3.4 Whisper.cpp iOS 통합

- 공식 [whisper.cpp/examples/whisper.objc](https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.objc) 패턴 따라 Obj-C++ 래퍼.
- Core ML encoder 생성: 사전에 `python3 models/generate-coreml-model.sh base` 로 `ggml-base-encoder.mlmodelc` 만들어 앱 번들 또는 `Application Support` 에 배치.
- Metal/GPU 가속은 기본 ON (iOS 에서 Apple Silicon 만 대상이므로 안전).
- 메모리: `base` ≈ 200 MB peak, `small` ≈ 600 MB, `medium` ≈ 1.5 GB → MVP 는 **`tiny`/`base` 기본**, `small` 은 디바이스 RAM ≥ 6 GB (iPhone 15 Pro+) 만 허용.

### 3.5 모델 권장 매트릭스

| 기기 | RAM | 권장 모델 | 비고 |
| :-- | :-- | :-- | :-- |
| iPhone 12/13 (4 GB) | 4 GB | `tiny.en` (39 MB) / `tiny` (75 MB) | base 도 가능하지만 thermal throttle |
| iPhone 14 / SE 3 (6 GB) | 6 GB | `base` (142 MB) | 기본값 |
| iPhone 15 Pro / 16 / 16 Pro | 8 GB | `small` (466 MB) | 권장 — 품질↑, Core ML 가속으로 속도 OK |
| iPad Pro M-series | 8–16 GB | `medium` (1.5 GB) | 옵션 |

---

## 4. 기능 요구사항

### 4.1 MVP (Phase 1, 첫 출시)

| ID | 기능 | 설명 |
| :-- | :-- | :-- |
| F-1 | 프로젝트 CRUD | 좌측 탭 / `NavigationSplitView` (iPad). iPhone 은 단일 stack. |
| F-2 | 파일 입수 다중 경로 | (a) Files 앱 picker, (b) 사진앨범 (PHPicker, 영상), (c) 음성 메모 import, (d) 공유 시트, (e) Drag & Drop (iPad), (f) AirDrop |
| F-3 | 지원 포맷 | `m4a, mp3, wav, aac, caf, mp4, mov, m4v` (AVFoundation 가능한 것 전부). HEVC/H.265 ok. |
| F-4 | 자동 언어 감지 | Whisper auto-detect; 사용자 강제 지정 옵션도. |
| F-5 | 파이프라인 실행 | `Start Transcription` 버튼 → 디코드 → ASR → 음차 → 번역. 진행률 % + ETA. |
| F-6 | 3단 세그먼트 뷰 | EN / 한글 음차 / KO 번역. 현재 재생 세그먼트 하이라이트. |
| F-7 | 시크/탭 점프 | 세그먼트 탭 = `AVPlayer.seek(to:)`. |
| F-8 | 미디어 컨트롤 | 재생/일시정지, 0.5/0.75/1/1.25/1.5/2× 속도, 5초 ±, A-B 구간 반복 |
| F-9 | 로컬 저장 | GRDB. iCloud 백업 포함 (Documents 디렉터리). |
| F-10 | 설정 | 모델 선택/다운로드, 번역 엔진, 다크/라이트, 글자 크기 (Dynamic Type), 데이터 삭제 |
| F-11 | 모델 매니저 | Hugging Face 에서 progress 표시 후 다운로드. 백그라운드 URLSession 으로 잠금화면 통과. |
| F-12 | 공유 시트 (Share Extension) | 외부 앱에서 "공유 → As It Sounds" → 새 프로젝트로 임포트 후 변환 시작 |
| F-13 | 결과 공유/복사 | 세그먼트 단위 복사, 전체 텍스트 공유 (UIActivityViewController) |
| F-14 | 오프라인 모드 인디케이터 | 번역 엔진이 네트워크 필요 시 명시 |

### 4.2 Phase 2 (확장)

- **SRT/VTT/TXT/Markdown 내보내기** — `UIDocumentPickerViewController(forExporting:)`
- **세그먼트 편집** → 음차/번역 자동 재생성
- **Spotlight Index (Core Spotlight)** — 세그먼트 본문 검색을 OS 검색에 노출
- **Live Activity / Dynamic Island** — 변환 진행률, 완료 알림
- **위젯 (WidgetKit)** — 최근 프로젝트, 학습 통계
- **Shortcuts (App Intents)** — "Siri야, 오늘 녹음 변환해줘"
- **AirPlay/Bluetooth 헤드폰** 따라 말하기 (구간 반복)
- **마이크 라이브 STT** — `AVAudioEngine` + Whisper VAD 청크

### 4.3 Phase 3

- iPad 멀티윈도우 / Stage Manager
- macOS Catalyst 빌드 (또는 SwiftUI multiplatform 통합)
- CloudKit 동기화 (옵션)
- 학습 모드 (정답 입력 → 채점)
- 발음 점수 (DTW + MFCC) — 사용자 발음 vs. 원본 비교

### 4.4 비기능 요구사항

| 항목 | 목표 |
| :-- | :-- |
| **콜드 스타트** | < 1.5 초 (iPhone 14) |
| **첫 세그먼트 표시** | 30 초 영상에서 ≤ 8 초 (base, iPhone 15) |
| **RTF** | base ≤ 0.4, small ≤ 0.8 (iPhone 15 Pro, Core ML) |
| **메모리 피크** | base 모델 동작 중 ≤ 350 MB |
| **배터리** | 10 분 변환 ≤ 5 % 소모 (iPhone 15) |
| **앱 바이너리** | < 80 MB (모델 제외, App Thinning 적용) |
| **앱 크기 정책** | 모델은 첫 실행 시 다운로드 — App Store 80 MB cellular 다운로드 제한 회피 |
| **접근성** | VoiceOver, Dynamic Type, Reduce Motion 모두 대응 |
| **프라이버시** | 마이크/사진 권한은 명확한 사용 시 트리거. 외부 전송은 번역 호출 외 0 |

---

## 5. UI/UX (HIG 기반)

### 5.1 정보 구조

```
TabView
├── Home          (최근 프로젝트, 빠른 임포트)
├── Library       (전체 프로젝트 리스트, 검색, 필터)
├── Record        (Phase 2 — 라이브 녹음/STT)
└── Settings      (모델, 번역, 언어, 정보)
```

iPhone: 하단 `TabView` 4탭. iPad: `NavigationSplitView` 3-pane (사이드바 / 리스트 / 디테일).

### 5.2 주요 화면 (와이어프레임 텍스트)

**Home**
```
[Logo]                                    [⚙]
─────────────────────────────────────────────
오늘의 학습
  [▶ Resume: 인터뷰 02 — 13:24]
─────────────────────────────────────────────
빠른 시작
  [📁 파일에서]  [🎞 사진에서]  [🎙 녹음]
─────────────────────────────────────────────
최근 프로젝트
  • TOEIC LC 03    영어 · 8m · 변환 완료
  • 회의 0524      한국어 · 22m · 처리 중 41%
```

**Project Detail**
```
< Back   인터뷰 02              [⋯ 공유/삭제]
────────────────────────────────────────────
[AVPlayer / 또는 audio waveform]
────────────────────────────────────────────
[00:03 ─ 00:05]               [⋯]
EN  As it sounds, it works.
한  애즈 잇 사운즈, 잇 웍스.
KO  들리는 대로, 동작합니다.
────────────────────────────────────────────
[00:05 ─ 00:08]
...
────────────────────────────────────────────
하단 floating control bar
  [↺ 5] [▶︎] [↻ 5]   1× ▾   A↔B
```

**Settings**
```
Whisper 모델
  현재: base (142 MB)         [변경 >]
  사용 가능한 공간: 21.4 GB

번역 엔진
  Apple Translation           [기본]
  ▸ MyMemory · LibreTranslate · DeepL · 끄기

표시
  Dynamic Type                [시스템]
  다크 모드                   [시스템]

데이터
  저장 사용량 1.2 GB
  [캐시 비우기]  [모든 데이터 삭제]

정보
  버전 1.0 (1)
  오픈소스 라이선스 >
```

### 5.3 디자인 토큰

- **컬러**: 시스템 색상 우선 (`Color.accentColor`, `.primary`, `.secondary`). 다크/라이트 자동. 액센트: `#4F8DFF` (Gemini 톤 유지).
- **타이포**: `SF Pro` (자동) + `SF Mono` (타임코드). Dynamic Type 전부 대응.
- **간격**: 4 / 8 / 16 / 24 / 32 grid.
- **모서리**: 12 (카드), 8 (버튼), 28 (큰 시트).
- **모션**: `Reduce Motion` ON 시 fade only. 기본은 `spring(response:0.4, damping:0.8)`.

### 5.4 접근성 체크리스트

- VoiceOver 라벨: 세그먼트 = "타임코드 00:03 부터 00:05, 영어 As it sounds it works, 한글 음차 …, 한국어 …" 결합 라벨.
- Switch Control / AssistiveTouch — 모든 액션 Hit target ≥ 44pt.
- 색맹: 진행 상태는 색 + 아이콘 + 텍스트 3중.
- 자막: 본인이 자막 도구이므로 dogfooding.

---

## 6. 아키텍처

### 6.1 모듈 (Swift Package + Xcode 타깃)

```
AsItSounds.xcodeproj
├── App                  (target: 메인 앱)
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
├── PipelineKit          (SwiftPM local) — actor 기반 파이프라인 오케스트레이션
├── WhisperKitBridge     (SwiftPM local) — Obj-C++ 래퍼
├── AisCoreFFI           (SwiftPM local, binaryTarget) — UniFFI 산출물
├── DBKit                (SwiftPM local) — GRDB 래퍼, 스키마, 마이그레이션
└── TranslateKit         (SwiftPM local) — 번역 엔진 프로토콜 + 구현 4종
```

### 6.2 파이프라인

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

처리 단계:

```
MediaFile (Files / PHPicker / Share Sheet)
  └─▶ AVAssetReader  : 16 kHz mono Float32 PCM
        └─▶ WhisperEngine (Core ML)  : segments[{start,end,text,lang}]
              ├─▶ AisCoreFFI.transliterate(text, scriptHint) → 한글
              └─▶ TranslateKit.translate(text, src→ko) → KO (async, 병렬)
                    └─▶ DBKit.insertSegments + AsyncStream.yield(.segment(...))
```

스레딩: 디코드는 `Task.detached(priority: .userInitiated)`, ASR 은 dedicated `OperationQueue (qos: .userInitiated, maxConcurrent: 1)` (모델 1 인스턴스), 번역은 `TaskGroup` 으로 세그먼트 N개 병렬.

### 6.3 데이터 모델 (GRDB)

```swift
// 기존 데스크탑 SQLite 스키마와 1:1 동일
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

마이그레이션은 `DatabaseMigrator` 로 버전 관리. **FTS5** 가상 테이블 추가 (Phase 2 검색).

### 6.4 번역 엔진 추상

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

### 6.5 모델 저장

- 위치: `Application Support/com.asitsounds.ios/models/ggml-<name>.bin` + `.mlmodelc/`
- iCloud Backup 제외 플래그: `URLResourceValues.isExcludedFromBackup = true` (큰 파일 백업 폭주 방지)
- 디스크 부족 시: 우선 캐시 정리 → 모델 변경 안내

### 6.6 백그라운드 처리

| 시나리오 | 전략 |
| :-- | :-- |
| 모델 다운로드 | `URLSession(configuration: .background)` — 잠금/종료 후에도 OS 가 완료 |
| 긴 파일 변환 (>30초) | `BGProcessingTaskRequest(identifier: "com.asitsounds.ios.transcribe")` + checkpoint 저장. 다음 부팅 때 이어쓰기 |
| 짧은 파일 | 포그라운드만, `UIApplication.beginBackgroundTask` 로 ~30 초 유예 |

### 6.7 에러 처리

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

세그먼트 단위 실패는 재시도 큐로, 파일 단위 실패는 UI 배지. 데스크탑과 동일 정책.

---

## 7. 권한 / Info.plist 키

| 키 | 사유 |
| :-- | :-- |
| `NSMicrophoneUsageDescription` | Phase 2 라이브 STT (MVP 도 향후 위해 미리) |
| `NSPhotoLibraryUsageDescription` | 사진앨범의 영상 가져오기 |
| `NSDocumentsFolderUsageDescription` | Files 앱 임포트 |
| `NSAppleEventsUsageDescription` | Shortcuts (App Intents) |
| `NSUserActivityTypes` | Handoff / Spotlight |
| Background Modes | `audio`, `processing`, `fetch` |
| `UIBackgroundModes` → processing | BGTask |
| `UIFileSharingEnabled` = YES | iTunes/Finder 로 내보내기 |
| `LSSupportsOpeningDocumentsInPlace` = YES | 외부 파일 in-place |
| `UTExportedTypeDeclarations` | `.ais` 프로젝트 번들 (Phase 2 내보내기) |

---

## 8. 보안 / 프라이버시

- **App Transport Security**: 기본 ON, MyMemory/LibreTranslate/DeepL 도메인만 도메인 단위 허용.
- **샌드박스**: 모든 사용자 파일은 Documents 내부 사본 (PHPicker는 강제 사본). 외부 경로 절대 저장 X.
- **Keychain**: API 키 (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
- **로그**: PII 없음 — 텍스트는 로깅 금지. 길이/언어/모델만 카운트.
- **App Privacy Manifest (PrivacyInfo.xcprivacy)**: iOS 17 요구사항. `NSPrivacyAccessedAPITypes` 정확히 기록.
- **Data Collection 표시**: App Store Connect — "Diagnostics: Crash Data (linked, but not used for tracking)" 만.

---

## 9. 테스트 전략

| 레이어 | 도구 |
| :-- | :-- |
| 단위 (Swift) | **Swift Testing** (Xcode 16) — actor, async 친화. XCTest 호환 |
| 단위 (Rust 코어) | 기존 `cargo test` (translit 정확도 회귀 — 데스크탑과 동일 fixture) |
| 스냅샷 UI | `swift-snapshot-testing` (light/dark, sizeCategory 매트릭스) |
| 통합 (파이프라인) | XCUITest + 짧은 .wav 픽스처 (`.samples/*.mp3` 재사용 → `.wav` 변환본) |
| 성능 | XCTest Metrics: `clock`, `memory`, `disk`. iPhone 12/14/15 Pro 3-기종 매트릭스 |
| 회귀 — 음차 정확도 | Rust workspace 의 기존 fixture (`test.sh`) — CI 에서 매 PR 실행 |

성공 기준 (데스크탑 PRD 와 동일):
- 10 분 영어 영상 세그먼트 정확도 ≥ 85%
- 음차 가독성 ≥ 4 / 5 (베타 사용자)
- 콜드 스타트 → 첫 세그먼트 ≤ 10 초 (base, iPhone 15)
- 비행기 모드에서 100% 동작 (번역 엔진 = Apple/끄기 일 때)

---

## 10. 배포 / 빌드

### 10.1 빌드 시스템

- Xcode 16+, Swift 5.10+
- SwiftPM (외부 의존성)
- CocoaPods/Carthage 사용 안 함 (불필요)
- **로컬 패키지**: 위 6.1 모듈 모두 SwiftPM `path:` 로 인-리포

### 10.2 외부 의존성 (SwiftPM)

| 패키지 | 목적 | 라이선스 |
| :-- | :-- | :-- |
| [groue/GRDB.swift](https://github.com/groue/GRDB.swift) | SQLite | MIT |
| [pointfreeco/swift-snapshot-testing](https://github.com/pointfreeco/swift-snapshot-testing) | UI 스냅샷 (테스트) | MIT |
| [apple/swift-collections](https://github.com/apple/swift-collections) | OrderedDict 등 | Apache-2 |
| [apple/swift-log](https://github.com/apple/swift-log) | 로깅 추상 | Apache-2 |
| [ggerganov/whisper.cpp](https://github.com/ggerganov/whisper.cpp) | ASR (서브모듈 또는 prebuilt xcframework) | MIT |
| UniFFI (Mozilla) | Rust ↔ Swift | MPL-2 |

선택적:
| 패키지 | 목적 |
| :-- | :-- |
| [argmaxinc/WhisperKit](https://github.com/argmaxinc/WhisperKit) | Pure-Swift Whisper (Core ML 네이티브). whisper.cpp 대안 — A/B 평가 |
| [pointfreeco/swift-composable-architecture](https://github.com/pointfreeco/swift-composable-architecture) | 상태관리 (옵션) |

**whisper.cpp vs WhisperKit 결정 기준**:
- whisper.cpp: 데스크탑/웹과 동일 엔진, 회귀 적음, Core ML encoder 만 사용. 권장 (MVP).
- WhisperKit: 순수 Swift, MLPackage 직접 로드, 더 iOS 친화. 음차/번역 결과 동치성 검증 후 전환 고려.

### 10.3 CI/CD

- GitHub Actions `macos-14` (Apple Silicon) 러너
- 단계: Rust xcframework 빌드 → SwiftPM 캐시 복원 → Xcodebuild test → archive → TestFlight 업로드 (`fastlane match` + `pilot`)
- 시크릿: `APP_STORE_CONNECT_API_KEY` (App Store Connect API), `APPLE_TEAM_ID`, `MATCH_PASSWORD`
- 코드사이닝: **fastlane match** 추천 (PR 빌드 = development, main 머지 = TestFlight)

### 10.4 App Store 제출 준비물

- 4 size 스크린샷 (6.7", 6.1", 5.5", iPad 12.9")
- 30초 프로모 영상 (옵션)
- App Privacy 답변
- Export Compliance (whisper.cpp = 표준 암호화 사용 안 함 → "No" 가능, ATS 만)
- 카테고리: Education (서브 Productivity)
- 가격: Free + IAP (Pro 모델 잠금 해제) 또는 Free

---

## 11. 로드맵 (제안)

| Phase | 기간 (영업일) | 산출물 |
| :-- | :-- | :-- |
| **0. 초기 셋업** | 5d | Xcode 프로젝트, SwiftPM 모듈, CI 골격, Rust xcframework 빌드 검증 |
| **1. Whisper 통합** | 7d | 단일 짧은 .wav → segments 표시 (UI 무관 데모) |
| **2. Rust 음차 브릿지** | 4d | UniFFI 바인딩, 데스크탑과 fixture 일치 확인 |
| **3. UI 셸 (Home/Library/Project)** | 8d | Mock 데이터로 end-to-end navigation |
| **4. 파일 입수 4 경로** | 5d | Files / PHPicker / 공유 시트 / Drag-Drop |
| **5. 파이프라인 실행 + AsyncStream** | 6d | 실제 음원 → 결과 표시 |
| **6. 번역 엔진 4종** | 4d | Apple/MyMemory/Libre/DeepL 토글 |
| **7. 모델 매니저 + 백그라운드** | 5d | 다운로드 progress, BGTask 변환 |
| **8. 설정/Polish/접근성** | 4d | Settings, VoiceOver, Dynamic Type |
| **9. 베타 TestFlight** | 3d | 50명 외부 베타 |
| **10. App Store 제출** | 3d | 스크린샷, 메타데이터, 심사 대응 |

총 **약 54 영업일** (≈ 11 주, solo dev 기준). 2명이면 ~7주.

---

## 12. 리스크 / 대응

| 리스크 | 영향 | 대응 |
| :-- | :-- | :-- |
| Whisper 모델 메모리 (구형 iPhone) | OOM 크래시 | 디바이스별 자동 가드: 4GB RAM → tiny 강제, 6GB → base 강제 |
| Rust xcframework 빌드 복잡도 | 신규 dev 진입장벽 | CI 산출물을 SwiftPM `binaryTarget(url:)` 로 배포, 로컬 빌드는 옵션 |
| App Store 심사: "타사 콘텐츠 자동 다운로드" | 모델 자동 다운로드 거절 가능성 | 첫 실행 시 명시적 동의 다이얼로그, Wi-Fi 권장 안내 |
| Apple Translation 사용 가능 지역 한정 | 일부 국가에서 fallback 필요 | 자동 폴백 우선순위 체인 (Apple → MyMemory → 끄기) |
| 백그라운드 처리 시간 제한 | 장문 미디어 미완료 | 세그먼트 단위 checkpoint, 앱 재시작 시 이어쓰기 |
| HEVC/HEIC 코덱 일부 단말 미지원 | 드물지만 발생 | AVAssetReader 가 fail → 명확 메시지, .wav 재인코딩 옵션 |
| 음차 품질 (다국어) | 학습 효과 저하 | 데스크탑 회귀 fixture 그대로 → 동등 보장 |
| 빌드 크기 (whisper.cpp + Core ML) | App Thinning 후에도 60 MB+ | 모델은 동적 다운로드 (앱에 포함 X) |
| GRDB ↔ Rust SQLite 동시 액세스 | 데이터 손상 가능 (만약 Rust도 SQLite 쓰면) | iOS 에서는 **Rust 코어가 SQLite 사용 안 함** — translit 만. DB 는 100% Swift |

---

## 13. 코드/IP 재사용 매트릭스 (요약)

| 자산 | 위치 | iOS 재사용 방식 |
| :-- | :-- | :-- |
| `translit.rs` (1713 LOC, 다국어 음운 규칙) | `src-tauri/src/core/translit.rs` | **그대로 컴파일**, UniFFI 노출 |
| `models.rs`, `error.rs` | 동상 | 일부 타입 미러링 (Swift 측) |
| `asr.rs` (whisper-rs 래퍼) | Rust | 재사용 안 함 — Swift 가 직접 whisper.cpp 호출 (성능/Core ML) |
| `media.rs` (ffmpeg 사이드카) | Rust | 재사용 안 함 — AVFoundation 사용 |
| `translate.rs` (MyMemory/Libre/DeepL) | Rust | 참조용 — Swift `TranslateKit` 재작성 (Apple Translation 추가) |
| `db.rs` (rusqlite) | Rust | 참조용 — GRDB 재작성. **스키마는 1:1 유지** |
| `pipeline.rs` | Rust | 재구현 — `actor PipelineService` |
| 프론트엔드 React | `src/` | 재사용 0 — SwiftUI 신규 |
| `.samples/*.mp3` 픽스처 | repo 루트 | **그대로 회귀 테스트 입력** |

**결론**: Rust 코어 중 IP 가치가 가장 큰 `translit.rs` 만 그대로 가져오고 나머지는 iOS-native 로 재작성. 유지보수 가능한 분량, 플랫폼 강점 활용.

---

## 14. 성공 지표 (KPI)

- **Day-30 retention** ≥ 25 %
- **첫 변환 완료율** ≥ 70 % (앱 설치 → 첫 결과 본 사용자)
- **평점** App Store ≥ 4.5
- **크래시-free 세션** ≥ 99.7 %
- **세그먼트 정확도** ≥ 85 % (영어), ≥ 78 % (일본어/프랑스어)
- **베타 NPS** ≥ 40

---

## 15. 결정사항 요약 (Architectural Decisions)

| ADR | 결정 |
| :-- | :-- |
| ADR-001 | Tauri Mobile 채택 안 함. Swift 네이티브. |
| ADR-002 | Rust `translit.rs` 만 재사용, UniFFI 로 노출. |
| ADR-003 | ASR 는 whisper.cpp + Core ML encoder (MVP). WhisperKit 은 Phase 2 평가. |
| ADR-004 | 미디어 디코드는 AVFoundation. ffmpeg 사용 안 함. |
| ADR-005 | DB 는 GRDB.swift. Rust 코어는 DB 접근 안 함. |
| ADR-006 | 번역 기본 = Apple Translation (iOS 17.4+), 폴백 MyMemory. |
| ADR-007 | 모델은 첫 실행 시 다운로드. 앱 번들에 포함 안 함. |
| ADR-008 | 최소 OS = iOS 17.0. (`@Observable`, App Intents, 백그라운드 개선) |
| ADR-009 | 상태관리는 Swift Concurrency + `@Observable`. TCA 안 씀 (MVP). |
| ADR-010 | 디자인은 HIG 표준 컴포넌트 우선. 커스텀 최소화. |

---

## 16. 부록 — 참고 자료

- whisper.cpp iOS 가이드: <https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.objc>
- Core ML Whisper 변환: <https://github.com/ggerganov/whisper.cpp/blob/master/models/README.md#core-ml-support>
- WhisperKit (Argmax): <https://github.com/argmaxinc/WhisperKit>
- UniFFI 가이드: <https://mozilla.github.io/uniffi-rs/>
- Apple Translation framework: <https://developer.apple.com/documentation/translation>
- GRDB.swift: <https://github.com/groue/GRDB.swift>
- Apple HIG: <https://developer.apple.com/design/human-interface-guidelines/>
- Background Tasks: <https://developer.apple.com/documentation/backgroundtasks>
- Share Extension: <https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/ShareSheet.html>
- App Privacy Manifests: <https://developer.apple.com/documentation/bundleresources/privacy_manifest_files>
- 데스크탑 PRD (parent): `../../NGL(Go,Rust,...)/As-It-Sounds/.doc/PRD.md`
- 데스크탑 README: `../../NGL(Go,Rust,...)/As-It-Sounds/README.md`
- 웹 배포 README: `../../NGL(Go,Rust,...)/As-It-Sounds/README-Web.md`
