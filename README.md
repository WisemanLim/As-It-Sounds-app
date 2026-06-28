# As It Sounds — 모바일 (iOS + Android)

> 🌐 **한국어** · [English](README.en.md)

<div align="center">

**데스크톱/웹 "As It Sounds" 프로젝트의 네이티브 모바일 동반 앱 — 어떤 언어로 된 오디오/비디오든 전사하고, 실제 발음을 그대로 반영한 한국어 한글 표기로 렌더링하며, 한국어 번역을 함께 제공합니다. 모두 온디바이스로 처리됩니다.**

Swift 5.10 · SwiftUI · iOS 17+ &nbsp;·&nbsp; Kotlin 2.0 · Jetpack Compose · Android API 28–35 &nbsp;·&nbsp; Rust · UniFFI · whisper.cpp v1.7.5

</div>

---

> 데스크톱의 Rust 음역 코어를 `#[path]`로 그대로 공유합니다 (1,713 LOC, 회귀 테스트 30개) — 복사본도, 포크도 아닙니다. 나머지는 모두 플랫폼 네이티브입니다.

| 경로 | 스택 | 용도 |
| :-- | :-- | :-- |
| [`core/`](./core/) | Rust 워크스페이스, UniFFI 0.28, whisper.cpp v1.7.5 | 데스크톱의 `translit.rs`를 재출력; `transliterate()` / `version()`을 Swift + Kotlin에 노출; `AisCoreFFI.xcframework` + `libais_ffi.so × 4 ABI` 생성 |
| [`ios/`](./ios/) | Swift 5.10, SwiftUI `@Observable`, AVFoundation, GRDB.swift 6.29.3, BGProcessingTask, CloudKit, ActivityKit, AppIntents | iPhone / iPad 앱 |
| [`android/`](./android/) | Kotlin 2.0, Jetpack Compose, Material 3, Media3 ExoPlayer 1.5.1, JNA 5.17.0, SQLiteOpenHelper, Foreground Service `mediaProcessing` | Android 휴대폰 / 태블릿 앱 |
| [`.doc/`](./.doc/) | Markdown | PRD (iPhone + Android), 환경 설정, 서명, 스토어 메타데이터, KMM 마이그레이션 계획, Live Activity 가이드 |
| [`.github/workflows/`](./.github/workflows/) | YAML | iOS / Android / Release CI — 현재 `workflow_dispatch:`만 활성화 (자동 트리거 비활성화) |
| [`assets/`](./assets/) | SVG + shell | 앱 아이콘 SVG + `generate-icons.sh` (rsvg-convert) + `capture-screenshots.sh` |
| [`.review/`](#분석가-출력) | Markdown | 자동 생성된 심층 리뷰, 주니어 가이드, 라인별 코드 리뷰 (분석가 출력물, 재생성 가능) |

> 아래의 인라인 소스 경로는 축약 표기입니다. 실제 루트: iOS = `ios/AsItSounds/` (예: `Core/Pipeline.swift` → [`ios/AsItSounds/Core/Pipeline.swift`](./ios/AsItSounds/Core/Pipeline.swift)); Android = `android/app/src/main/java/com/asitsounds/android/` (예: `whisper/Pipeline.kt`).

---

## 기능 현황

### Phase 0 — 스캐폴딩 ✅

- Rust 워크스페이스 빌드 완료 (`cargo build --release`); 업스트림 `translit.rs` 테스트 30개 전부 통과.
- UniFFI Swift 바인딩: `core/bindings/swift/AisCoreFFI.swift`
- UniFFI Kotlin 바인딩: `core/bindings/kotlin/com/asitsounds/core/ais_ffi.kt`
- iOS 디바이스 정적 라이브러리 크로스 컴파일 완료 (`aarch64-apple-ios`).
- Android `.so × 4 ABI` (arm64-v8a / armeabi-v7a / x86_64 / x86) — cargo-ndk 사용.
- iOS SwiftUI 스캐폴드 + Android Compose 스캐폴드; 두 플랫폼 모두 FFI를 통한 `transliterate()` 왕복 동작 시연.

### Phase 1 — 온디바이스 전사 ✅

- Whisper.cpp v1.7.5 정적 링크
  - iOS: `whisper.xcframework` (디바이스 + 시뮬레이터 슬라이스) — `core/scripts/assemble-whisper-xcframework.sh` 사용
  - Android: ABI별 `libais_whisper.so` — CMake + JNI (`android/app/src/main/cpp/ais_whisper_jni.cpp`) 사용
- 오디오 디코드 → 16 kHz 모노 Float32 PCM
  - iOS: `AVAssetReader` + `AVAudioConverter` (`Core/AudioDecoder.swift`)
  - Android: `MediaExtractor` + `MediaCodec` + 선형 리샘플 (`whisper/AudioDecoder.kt`)
- 모델 다운로드: Hugging Face에서 `ggml-{tiny,base,small}.bin`, `.part` 임시 파일 + 원자적 이름 변경
- 파이프라인 오케스트레이션:
  - iOS: `PipelineRunner` `@MainActor @Observable` + `Task.detached`
  - Android: `Dispatchers.IO`에서 `Flow<PipelineEvent>`를 반환하는 `Pipeline.run(...)`

### Phase 2 — 실제 제품 ✅

- **번역기** — 플러그인 방식의 4가지 백엔드: MyMemory (무료, 기본값) / LibreTranslate (자체 호스팅) / DeepL (API 키) / 사용 안 함
- **영속성** — FK 캐스케이드를 갖는 3개 테이블, 플랫폼 간 공유 스키마
  - iOS: GRDB.swift 6.29.3 (`ios/packages/GRDB/`에 로컬 SwiftPM 클론)
  - Android: 수동 작성 `SQLiteOpenHelper` (KSP 2.0.21 이슈로 Room 차단 — [`.doc/signing.md`](.doc/signing.md) 참고)
- **백그라운드 처리**
  - iOS: `BackgroundTaskCoordinator` (BGProcessingTask, ID `com.asitsounds.ios.transcribe`)
  - Android: `TranscribeForegroundService` (`foregroundServiceType="mediaProcessing"`, API 34+)
- **자동 하이라이트 미디어 플레이어**
  - iOS: AVPlayer + `ScrollViewReader` (`Features/PlayerView.swift`)
  - Android: Media3 ExoPlayer 1.5.1 + LazyColumn (`ui/PlayerScreen.kt`)
- **자막 내보내기** — SRT / VTT / TXT
  - iOS: `UIDocumentPicker` (`Core/SubtitleExport.swift`)
  - Android: `ActivityResultContracts.CreateDocument` (`whisper/SubtitleExport.kt`)

### Phase 4 — 전사 품질 및 UX 향상 ✅

- **F1 진행률 표시** — `PipelineRunner` 에 `progressFraction` / `completedSegments` / `totalSegments` 추가. `PipelineView` 번역 루프 진행률 바(결정론적 `ProgressView`). Android: `PipelineEvent.TranslationProgress` + `LibraryScreen` 진행률 인디케이터 + "재개" 버튼.
- **F2 재시도/재개** — 라이브러리에서 오류·처리중 파일에 재시도 버튼 노출. iOS: `AppDatabase.clearSegments(fileId:)` + `LibraryView.ProjectDetailView` 재시도 버튼(`retryFile()`). Android: `LibraryViewModel.retryProject()` + WorkManager 체크포인트 이어쓰기. `AS-It-Sounds-iOS`: `Services.retryTranscription(fileId:)` + `TranscriptionRetryBannerView`.
- **F3 고품질 모델** — iOS: `ggml-medium.bin` (1,534 MB) + `ggml-large-v3-turbo.bin` (809 MB), HuggingFace `ggerganov/whisper.cpp` GGUF. Android: `MEDIUM` (1.5 GB, RAM 8 GB+) + `LARGE_V3_TURBO` (809 MB, RAM 6 GB+). 기기 RAM 가드 적용, 설정에서 선택 가능.

### Phase 3 — 플랫폼 통합 ✅ (스캐폴딩 완료)

- **iCloud 동기화** — `Core/CloudSync.swift` (CKDatabase 프라이빗 DB 푸시/풀; iCloud 기능 + 컨테이너 `iCloud.com.asitsounds.ios` 필요)
- **Siri / App Intents** — `Core/AppShortcuts.swift` (`OpenLatestProjectIntent` 음성 트리거; `TransliterateIntent` Shortcuts.app 항목)
- **Live Activity** — `Core/LiveActivity.swift` (`ActivityKit` 속성 + Coordinator); Widget Extension 타깃 = 수동 Xcode 단계 ([`.doc/live-activity.md`](.doc/live-activity.md) 참고)
- **퀵 세팅 타일 (Android)** — `service/TranscribeQuickTile.kt` (`AndroidManifest.xml`에 등록, 앱 실행)
- **실시간 마이크 STT** — 5초 롤링 버퍼 + Whisper 추론
  - iOS: `Core/LiveMicSTT.swift` (`AVAudioEngine` 탭 + `AVAudioConverter`)
  - Android: `whisper/LiveMicStt.kt` (`AudioRecord` Float32 네이티브 16 kHz)
- **KMM 마이그레이션 계획** — [`.doc/kmm-migration.md`](.doc/kmm-migration.md) (Phase 3.5 / 약 12 영업일로 연기)

### 완성도 ✅

- i18n: 영어 + 한국어 (`Localizable.strings` × 2 / `strings.xml` × 2, 각 50개 이상 키)
- 다크 모드: 시스템 / 항상 켜기 / 항상 끄기 — `Settings` (`AppSettings.colorScheme`) 경유
- Material 3 Dynamic Color (Android 12+) + iOS 시맨틱 컬러 (`Color.accentColor` / `Color(.systemGreen)`)
- 접근성 레이블: 세그먼트 행별 VoiceOver (iOS) + TalkBack (Android)
- 설정 화면: Whisper 모델, 번역기, 다크 모드, 라이선스 카드, 캐시 지우기, 디스크 사용량을 위한 실제 피커

### 출시 준비 ✅

- 앱 아이콘: SVG 소스 + `assets/generate-icons.sh` (rsvg-convert) → iOS 1024×1024 + Android 어댑티브 아이콘 × 5 밀도
- 스토어 메타데이터: [`.doc/store-metadata.md`](.doc/store-metadata.md) (App Store Connect + Play Console, en + ko)
- 서명: [`.doc/signing.md`](.doc/signing.md) (Apple Developer ID + Play 업로드 키 + CI 시크릿 매트릭스)
- CI: `.github/workflows/{ios,android,release}.yml` — **자동 트리거 주석 처리됨** (수동 `Run workflow`만 가능); 준비 완료 시 재활성화

---

## 빠른 시작

> Xcode GUI를 위해 **괄호 없는 심링크** `~/As-It-Sounds-app`를 사용하세요 — `(...)`가 포함된 macOS 경로는 Xcode의 일부 경로 정규화 단계를 깨뜨립니다:
> ```bash
> ln -sfn "/Users/$USER/Developer/Apps(Android,iOS,...)/As-It-Sounds-app" "$HOME/As-It-Sounds-app"
> ```

### 사전 요구사항

| 도구 | 버전 | macOS 설치 |
| :-- | :-- | :-- |
| Xcode | **26.0+** (iOS 17 SDK 포함) | Mac App Store |
| `xcode-select` | Xcode.app을 가리켜야 함, CommandLineTools **아님** | `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` |
| xcodegen | 2.45+ | `brew install xcodegen` |
| librsvg (rsvg-convert) | 최신 | `brew install librsvg` |
| Rust | rustup으로 1.82+ | `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \| sh` |
| Android Studio | Panda 4 (2025.3.4) 이상 | 직접 다운로드 |
| Android NDK | **30.0.14904198** | Android Studio → SDK Manager → SDK Tools → NDK (Side by side) |
| CMake | 3.22.1+ | Android Studio → SDK Manager → SDK Tools |
| cargo-ndk | 최신 | `cargo install --locked cargo-ndk` |

```bash
# 최초 1회: NDK + SDK 환경 설정
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.14904198"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 최초 1회: Rust 모바일 타깃 설치 (iOS + Android, 7개 타깃)
rustup target add \
  aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios \
  aarch64-linux-android armv7-linux-androideabi \
  x86_64-linux-android i686-linux-android
```

### 외부 소스 가져오기 (최초 1회)

```bash
# whisper.cpp v1.7.5 (두 플랫폼 모두 사용)
git clone --depth 1 --branch v1.7.5 \
  https://github.com/ggerganov/whisper.cpp \
  core/third_party/whisper.cpp

# GRDB.swift v6.29.3 (iOS 전용 — 괄호 경로 우회)
git clone --depth 1 --branch v6.29.3 \
  https://github.com/groue/GRDB.swift \
  ios/packages/GRDB
```

### 빌드 및 실행 — iOS

```bash
# 1. Rust 코어 빌드 → iOS xcframework + Swift 바인딩
cd core && ./scripts/build-ios.sh

# 2. whisper.cpp 빌드 → iOS xcframework
(cd core/third_party/whisper.cpp && ./build-xcframework.sh) || true
(cd core && ./scripts/assemble-whisper-xcframework.sh)

# 3. Xcode 프로젝트 생성
cd ../ios && xcodegen generate

# 4. Xcode에서 열기 (GUI는 괄호 없는 심링크 사용)
open AsItSounds.xcodeproj
# → iPhone 17 Pro Simulator (iOS 26.5) 또는 등록된 디바이스를 타깃으로 ⌘R
```

### 빌드 및 실행 — Android

```bash
# 1. Rust 코어 빌드 → 4 ABI .so + Kotlin 바인딩
cd core && ./scripts/build-android.sh

# 2. Android Studio Panda에서 android/ 디렉터리 열기
# → Gradle 동기화 자동 실행
# → Medium_Phone 에뮬레이터 (API 28+) 또는 디바이스에서 ▶︎ 실행

# 또는 CLI로
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb shell am start -n com.asitsounds.android/.MainActivity
```

---

## 사용 데모

두 앱 모두 4개 탭으로 시작합니다: **Transcribe** / **Library** / **Translit** / **Settings**.

1. **Transcribe** — 미디어 파일 선택 (iOS: Files / PhotoPicker / Share Sheet, Android: SAF) → 번역 엔진 선택 → *Transcribe* 탭 → 세그먼트가 EN / 한 / KO로 스트리밍.
2. **Library** — 실행 간 영속되는 프로젝트 + 파일 + 세그먼트; 프로젝트를 탭하여 검사.
3. **Translit** — Rust FFI를 통한 빠른 텍스트 → 한글 데모 (Whisper 불필요).
4. **Settings** — Whisper 모델, 기본 번역기, 다크 모드, 버전, 라이선스, 캐시 지우기.

### 샘플 입력 / 출력 (Translit 탭)

| 입력 | 예상 한글 출력 |
| :-- | :-- |
| `As it sounds, it works.` | 애즈 잇 사운즈, 잇 웍스. |
| `C. Right around the corner.` | 씨. 라이트 어라운드 더 코너. |
| `bonjour` | 봉주흐 |
| `こんにちは` | 콩니치하 |
| `Москва` | 모스크바 |
| `안녕하세요` | 안녕하세요 (pass-through) |

---

## 문서

- **PRD** — [iPhone](.doc/PRD-for-iPhone.md) · [Android](.doc/PRD-for-Android.md)
- **환경 설정** — [`.doc/ENV-setup.md`](.doc/ENV-setup.md)
- **스토어 메타데이터** (App Store Connect + Play Console, en + ko) — [`.doc/store-metadata.md`](.doc/store-metadata.md)
- **코드 서명** (Apple Developer ID + Play 업로드 키 + CI 시크릿) — [`.doc/signing.md`](.doc/signing.md)
- **Live Activity 설정** (Widget Extension 수동 Xcode 단계) — [`.doc/live-activity.md`](.doc/live-activity.md)
- **KMM 마이그레이션 계획** (Phase 3.5, 약 12 영업일) — [`.doc/kmm-migration.md`](.doc/kmm-migration.md)
- **플랫폼별 README** — [iOS](./ios/README.md) · [Android](./android/README.md)

### 분석가 출력

[`.review/`](./.review/) 하위에 자동 생성된 리포트 (재생성 가능; 삭제해도 무방):

| 리포트 | 범위 | 위치 |
| :-- | :-- | :-- |
| 심층 상용화 리뷰 | Android | [`android/.review/REVIEW-InDepth.md`](./android/.review/REVIEW-InDepth.md) |
| 심층 상용화 리뷰 | iOS | [`ios/.review/REVIEW-InDepth.md`](./ios/.review/REVIEW-InDepth.md) |
| 주니어 개발자 가이드 (이중 언어) | Android | [`android/.review/GUIDE4Junior.md`](./android/.review/GUIDE4Junior.md) |
| 주니어 개발자 가이드 (이중 언어) | iOS | [`ios/.review/GUIDE4Junior.md`](./ios/.review/GUIDE4Junior.md) |
| 구현 방법 가이드 (이중 언어) | Android | [`android/.review/GUIDE4ImpMethod.md`](./android/.review/GUIDE4ImpMethod.md) |
| 구현 방법 가이드 (이중 언어) | iOS | [`ios/.review/GUIDE4ImpMethod.md`](./ios/.review/GUIDE4ImpMethod.md) |
| 라인별 코드 리뷰 (Level 2) | 워크스페이스 | [`.review/CODE-REVIEW-Lv2/INDEX.md`](./.review/CODE-REVIEW-Lv2/INDEX.md) |

---

## CI / 릴리즈

세 개의 GitHub Actions 워크플로가 커밋되어 있으나 **자동 트리거는 비활성화** 상태입니다 (`workflow_dispatch:`만 활성):

| 워크플로 | 용도 | 재활성화 방법 |
| :-- | :-- | :-- |
| [`.github/workflows/ios.yml`](.github/workflows/ios.yml) | PR / main 푸시마다 macos-14 시뮬레이터 빌드 | `push` + `pull_request` 블록 주석 해제 |
| [`.github/workflows/android.yml`](.github/workflows/android.yml) | PR / main 푸시마다 ubuntu-22.04 디버그 APK | 동일 |
| [`.github/workflows/release.yml`](.github/workflows/release.yml) | `v*` 태그 푸시 시 서명된 IPA + AAB | `push.tags` 블록 주석 해제 |

수동 실행: GitHub → Actions → 워크플로 선택 → *Run workflow*.

---

## 테스트 배포 (Developer Mode 없이 설치 + 실행)

목표: Developer Mode가 **비활성화된** 테스터의 실물 디바이스에 앱을 설치하는 것.

### 로컬 Ad Hoc 빌드가 여전히 Developer Mode를 요구하는 이유

iOS 16+에서 **Developer Mode 요구사항은 앱이 디바이스에 도달하는 방식과 신뢰 채널**에 연결되어 있으며, 인증서만의 문제가 아닙니다:

- **App Store / TestFlight** — Apple이 App Store 배포 프로파일로 바이너리를 재서명하고 App Store / TestFlight 클라이언트를 통해 전달합니다. Developer Mode가 **절대** 불필요하고, UDID 등록도 불필요하며, "신뢰할 수 없는 개발자" 프롬프트도 나타나지 않습니다. Dev Mode 없이 보장되는 유일한 경로입니다.
- **Ad Hoc** (`Apple Distribution` 인증서 + ad-hoc 프로파일, 디바이스 UDID 내장) — `installation_proxy` 채널(Apple Configurator / `ideviceinstaller`)을 통해 설치됩니다. 최근 iOS(26.x 포함)에서 로컬로 사이드로드한 빌드를 실행하면 여전히 **"개발자 모드가 필요함"**이 발생할 수 있습니다. 비개발자 테스터에게는 불안정합니다.
- **Development** (`Apple Development` 인증서, `get-task-allow=true`) — 항상 Developer Mode가 필요합니다. 테스터용이 아닙니다.

**결론: iOS 테스트 배포에는 TestFlight를 사용하세요.** 배포 식별자: 번들 `com.asitsounds.ios`, 팀 `ALQEFZ23LN`, 버전 `0.1.0 (1)`.

### iOS — TestFlight (권장)

사전 조건: 유료 Apple Developer Program; `com.asitsounds.ios`에 대한 App Store Connect 앱 레코드.

**1. App Store Connect 레코드 생성** (최초 1회)
- appstoreconnect.apple.com → Apps → **+** → New App → iOS → 번들 ID `com.asitsounds.ios` 선택 → SKU `asitsounds-ios` → 생성.

**2. App Store 서명으로 아카이브**
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

**3. App Store IPA 내보내기** — `ios/build/exportOptions-appstore.plist`:
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

**4. App Store Connect에 업로드** — App Store Connect API 키 사용 (Users and Access → Integrations → **+**, 역할 *App Manager*; `AuthKey_<KEYID>.p8` 다운로드 → `~/.appstoreconnect/private_keys/`):
```bash
xcrun altool --upload-app -f build/appstore-ipa/AsItSounds.ipa -t ios \
  --apiKey <KEYID> --apiIssuer <ISSUER-UUID>
# 또는: xcrun notarytool / Transporter.app (.ipa 드래그)
```

**5. 빌드 배포**
- 처리 완료에 약 5–15분 소요 (App Store Connect → TestFlight 탭).
- **내부 테스트** — Users and Access에서 테스터 추가 (최대 100명, 팀원이어야 함). 빌드가 즉시 활성화되며 리뷰 불필요.
- **외부 테스트** — 그룹 생성 후 테스터 이메일 추가 또는 **공개 TestFlight 링크** 공유. 첫 외부 빌드는 짧은 Beta App Review 필요.
- 테스터가 App Store에서 **TestFlight** 앱 설치 → 초대 열기 → Install. **Developer Mode 불필요, UDID 불필요.**

> 빌드 만료: TestFlight 빌드는 90일 후 만료됩니다. 새 업로드마다 `CURRENT_PROJECT_VERSION` (빌드 번호)을 올리세요.

### iOS — Ad Hoc (대안, Developer Mode가 여전히 필요할 수 있음)

이 저장소에서 단일 등록 디바이스(`com.asitsounds.ios`)에 사용한 방법:
```bash
# 아카이브 (위와 동일), 이후 ad-hoc 내보내기:
#   method = ad-hoc  (Xcode 26에서는 "release-testing"으로 별칭)
xcodebuild -exportArchive -archivePath build/AsItSounds.xcarchive \
  -exportOptionsPlist build/exportOptions-adhoc.plist \
  -exportPath build/adhoc-ipa -allowProvisioningUpdates
# 비개발자 채널로 설치:
brew install ideviceinstaller
ideviceinstaller install build/adhoc-ipa/AsItSounds.ipa
```
디바이스 UDID를 포털에 먼저 등록해야 합니다 (대상 지정 `xcodebuild ... -allowProvisioningUpdates`가 빌드 오류가 나더라도 부작용으로 등록). 본인 개발 디바이스에만 사용하세요 — 테스터는 TestFlight를 사용해야 합니다.

---

## Android — 테스트 배포

식별자: applicationId `com.asitsounds.android`, `versionCode 1` / `versionName 0.1.0`, `minSdk 28`, `target/compileSdk 35`. Android에는 **Developer Mode에 상당하는 것이 없습니다** — 서명된 APK는 해당 출처를 허용하는 모든 디바이스에 설치됩니다. 세 가지 경로, 쉬운 것부터.

### 0. 릴리즈 키스토어 생성 (최초 1회)
```bash
keytool -genkeypair -v -keystore ~/keystores/asitsounds-release.jks \
  -alias asitsounds -keyalg RSA -keysize 2048 -validity 10000
```
`android/app/build.gradle.kts`에 연결 (시크릿은 git에서 제외 — `~/.gradle/gradle.properties`에서 읽기):
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

### Route A — 직접 APK (가장 빠름, 계정 불필요)
```bash
cd android
./gradlew :app:assembleRelease            # → app/build/outputs/apk/release/app-*-release.apk
# 범용 APK 전송 (AirDrop / 링크). 테스터가 "알 수 없는 앱 설치" 활성화
# 후 APK 탭하여 설치. 또는 adb로:
adb install -r app/build/outputs/apk/release/app-universal-release.apk
```

### Route B — Firebase App Distribution (관리형 테스터, 크로스 플랫폼)
```bash
npm i -g firebase-tools && firebase login
./gradlew :app:assembleRelease
firebase appdistribution:distribute \
  app/build/outputs/apk/release/app-universal-release.apk \
  --app <FIREBASE_ANDROID_APP_ID> \
  --groups "qa" --release-notes "0.1.0 build 1"
```
테스터가 이메일 + App Tester 앱을 수신합니다. (Firebase는 등록된 UDID 디바이스에 **iOS Ad Hoc IPA**도 배포합니다 — 단, Dev Mode 없는 iOS는 TestFlight를 권장합니다.)

### Route C — Google Play 내부 테스트 (프로덕션에 가장 유사)
```bash
./gradlew :app:bundleRelease              # → app/build/outputs/bundle/release/app-release.aab
```
Play Console → 앱 생성 → **Testing → Internal testing** → `.aab` 업로드 → 이메일 또는 옵트인 링크로 테스터 추가 → 테스터가 Play 스토어를 통해 설치. 최대 100명 내부 테스터, 리뷰 지연 없음. Play가 App Signing 키로 재서명.

### 배포 경로 요약

| 플랫폼 | 경로 | Dev Mode? | 테스터 상한 | 리뷰 |
| :-- | :-- | :-- | :-- | :-- |
| iOS | **TestFlight** (권장) | 없음 | 내부 100명 / 외부 10k | 외부만, 약 1일 |
| iOS | Ad Hoc + `ideviceinstaller` | 경우에 따라 | UDID 100개 | 없음 |
| Android | 직접 APK | 해당 없음 | 무제한 | 없음 |
| Android | Firebase App Distribution | 해당 없음 | 그룹별 | 없음 |
| Android | Play 내부 테스트 | 해당 없음 | 100명 | 없음 |

---

## 해결된 이슈 — 수정 로그

Phase 0–3 빌드업 중 해결된 누적 장애:

| 증상 | 근본 원인 | 수정 |
| :-- | :-- | :-- |
| `build-android.sh`에서 `bash: aarch64: unbound variable` | macOS bash 3.2 + `set -u` × `declare -A` | 연관 배열을 병렬 인덱스 배열로 교체 |
| Xcode GUI에서 iOS `XCFramework not found` | 경로의 `(...)`가 Xcode 26 경로 정규화를 깨뜨림 | `~/As-It-Sounds-app` 괄호 없는 심링크 사용 |
| `cannot find 'AisCoreFFI' in scope` | UniFFI Swift는 모듈 타입이 아닌 최상위 `func` | `transliterate(text:)` 직접 호출 |
| `[CXX5106] NDK was located by using ndk.dir property` | `local.properties`의 deprecated `ndk.dir` | 해당 줄 삭제; `app/build.gradle.kts`의 `ndkVersion` 사용 |
| `program alignment (8192) < page size (16384)` (Android 16 에뮬레이터) | 구 JNA 5.15의 8 KB 정렬 vs 16 KB 페이지 | JNA → 5.17.0 (AAR 변형)으로 업그레이드 |
| KSP `Wrong plugin option format: null` | Kotlin 2.0.21 + KSP 2.0.21-1.0.27 + AGP 8.7 충돌 | Room 제거, 수동 작성 `SQLiteOpenHelper` 사용 |
| GRDB SwiftPM `cannot use bare repository (safe.bareRepository is 'explicit')` | 괄호가 포함된 경로가 git safe-repo 정책 트리거 | `ios/packages/GRDB/`에 로컬 클론 + `path:` 참조 |
| `unknown platform: xros` (vtool) — whisper.xcframework 빌드 중 | whisper.cpp의 `build-xcframework.sh`가 visionOS 슬라이스 시도 | iOS 전용 `assemble-whisper-xcframework.sh` 사용 |
| `Undefined: _cblas_sgemm$NEWLAPACK$ILP64` | Accelerate 프레임워크 미링크 | `project.yml`에 4개 SDK 프레임워크 추가 (Accelerate / Metal / MetalKit / CoreML) |
| `Undefined: _whisper_coreml_*` | xcframework 조립 시 `libwhisper.coreml.a` 제외 | 슬라이스별 모든 `.a` 포함 |
| AppIntents `Invalid parameter type. AppEntity and AppEnum are the only allowed types` | AppShortcut 구문에 자유 형식 String | 매개변수 없는 인텐트에만 구문 바인딩 |

---

## 테스트 (`test/`)

iOS 시뮬레이터 + Android 에뮬레이터 및 공유 Rust 코어에 대한 시나리오 기반 검증. [`test/SCENARIOS.md`](test/SCENARIOS.md) 참고; 최신 실행 결과는 [`test/results/RESULTS.md`](test/results/RESULTS.md).

| ID | 범위 | 방법 | 상태 |
| :-- | :-- | :-- | :-- |
| S1.1 | Rust 코어 회귀 | `cargo test --workspace --release` (`core/` 내에서) | ✅ 30/30 |
| S1.2 | UniFFI 표면 | xcframework + jniLibs ×4 ABI 빌드 | ✅ 통과 |
| S2.1 | iOS 빌드 | `xcodegen` + `xcodebuild -sdk iphonesimulator` (iPhone 17) | ✅ BUILD SUCCEEDED |
| S2.2–2.4 | iOS 시뮬레이터 실행 | 부팅 + `simctl install/launch` + 스크린샷 | ✅ 홈 렌더링, 크래시 없음 |
| S3.1 | Android 빌드 | `./gradlew :app:assembleDebug` (NDK jniLibs) | ✅ BUILD SUCCESSFUL |
| S3.2–3.4 | Android 에뮬레이터 실행 | `Medium_Phone` AVD + `adb install` + 실행 + 화면 캡처 | ✅ 홈 렌더링, MainActivity 재개 |

증거 스크린샷: [`test/results/ios_home.png`](test/results/ios_home.png), [`test/results/android_home.png`](test/results/android_home.png).

> 이는 시뮬레이터/에뮬레이터 실행 + UI 스모크 검증입니다. 단위 수준의 `XCTest` + Android `app/src/test/` 픽스처 스캐폴드는 아래 로드맵에 남아 있습니다.

---

## 로드맵 (다음 단계)

| 우선순위 | 항목 | 위치 |
| :-- | :-- | :-- |
| Critical | `Pipeline.kt:66,70` 이중 해제 수정 (`nativeFree(handle)`이 예외 발생 시 `catch`와 `finally` 양쪽에서 실행) | [Pipeline.kt.md](.review/CODE-REVIEW-Lv2/android/app/src/main/java/com/asitsounds/android/whisper/Pipeline.kt.md) §watch-outs |
| Critical | `AudioDecoder.kt:61` 30분 이상 오디오에서 OOM 위험 (`ArrayList<Short>` 박싱) | `ShortBuffer` / `IntArray`로 교체 |
| High | Whisper 모델 SHA256 검증 (두 플랫폼 모두) | `ModelManager.{kt,swift}` |
| High | XCTest + Android `app/src/test/` 스캐폴드 (30개 이상 음역 회귀 픽스처) | 신규 |
| High | 첫 안정 태그 후 CI 자동 트리거 재활성화 | `.github/workflows/*.yml` |
| Medium | NOTICE 파일 + 자동 OSS 라이선스 화면 | `gradle-license-plugin` (Android) + `LicensePlist` (iOS) |
| Medium | Live Activity Widget Extension 타깃 (수동 Xcode 단계) | [`.doc/live-activity.md`](.doc/live-activity.md) |
| Medium | Crashlytics / MetricKit | 두 플랫폼 모두 |
| Low | KMM 마이그레이션 (Phase 3.5) | [`.doc/kmm-migration.md`](.doc/kmm-migration.md) |

---

## 라이선스

MIT — 업스트림 데스크톱 프로젝트와 동일. 전체 라이선스 파일은 저장소 루트의 `LICENSE`에 추가 예정.

### 서드파티 컴포넌트

- **whisper.cpp** v1.7.5 (`ggerganov/whisper.cpp`) — MIT
- **ggml** (whisper.cpp에 번들) — MIT
- **GRDB.swift** v6.29.3 (`groue/GRDB.swift`) — MIT
- **UniFFI** 0.28 (`mozilla/uniffi-rs`) — MPL-2.0 (생성된 바인딩 파일에만 적용)
- **JNA** 5.17.0 (`java-native-access/jna`) — Apache-2.0 OR LGPL-2.1+ (Apache-2.0 사용)
- **AndroidX / Compose / Media3** — Apache-2.0
- **Whisper 모델 가중치** (Hugging Face `ggerganov/whisper.cpp`) — MIT

---

## 감사의 말

- 업스트림 데스크톱/웹 앱: [`../NGL(Go,Rust,...)/As-It-Sounds`](../NGL(Go,Rust,...)/As-It-Sounds)
- `translit.rs` — 문맥 인식 다국어 음성 규칙 1,713줄 (영어, 일본어, 프랑스어, 스페인어, 독일어, 러시아어) — "As It Sounds"의 핵심
