# 프로젝트 주니어 개발자 가이드 / Junior Developer Guide

> 이 문서는 한국어와 영어로 작성되었습니다.
> This document is written in both Korean and English.
>
> 대상 / Target: `As-It-Sounds-app/android`
> 작성일 / Date: 2026-05-29

---

## 1. 프로젝트 개요 / Project Overview

**[한국어]**

- **목적**: 영어·일본어·프랑스어·스페인어·독일어·러시아어 등 다국어 음성/영상 파일을 안드로이드 디바이스에서 **온디바이스로** Whisper.cpp 로 전사한 뒤, **들리는 발음 그대로** 한글로 음차하고 한국어 번역을 함께 보여주는 학습용 앱입니다.
- **핵심 기능**:
  - 로컬 Whisper STT (모델은 첫 실행 시 Hugging Face 에서 다운로드)
  - 다국어 → 한글 음차 (1,713 줄 Rust 음운 규칙, 데스크탑 코어와 동일)
  - 4종 번역 엔진 선택 (MyMemory / LibreTranslate / DeepL / Off)
  - GRDB 의 안드로이드 대응판인 자체 `SQLiteOpenHelper` 영구화
  - Media3 ExoPlayer + 세그먼트 자동 하이라이트
  - SRT / VTT / TXT 자막 내보내기 (SAF)
  - 잠금 화면 Foreground Service + Quick Settings Tile
- **기술 스택**: Kotlin 2.0.21 · Jetpack Compose (BOM 2024.12.01) · Material 3 · AndroidX Media3 1.5.1 · whisper.cpp v1.7.5 (정적 링크) · JNA 5.17.0 (AAR) · UniFFI Kotlin bindings · AGP 8.13.2 · NDK 30 · minSdk 28 · targetSdk 35.

**[English]**

- **Purpose**: An Android app that transcribes audio/video files in 6+ languages **on-device** with Whisper.cpp, then renders the result as Korean Hangul phonetics **mirroring the actual sound** plus a Korean translation side-by-side. Designed for language learners and content creators.
- **Core features**:
  - Local Whisper STT (model downloaded from Hugging Face on first run)
  - Multilingual → Hangul transliteration (1,713 lines of Rust phonetic rules, shared with the desktop core)
  - Pluggable translator (MyMemory / LibreTranslate / DeepL / Off)
  - Hand-rolled `SQLiteOpenHelper` persistence (the Android counterpart of the iOS GRDB layer)
  - Media3 ExoPlayer with auto-scrolling segment highlight
  - SRT / VTT / TXT subtitle export via SAF
  - Lock-screen Foreground Service + Quick Settings Tile
- **Tech stack**: Kotlin 2.0.21 · Jetpack Compose (BOM 2024.12.01) · Material 3 · AndroidX Media3 1.5.1 · whisper.cpp v1.7.5 (statically linked) · JNA 5.17.0 (AAR) · UniFFI Kotlin bindings · AGP 8.13.2 · NDK 30 · minSdk 28 · targetSdk 35.

---

## 2. 개발 환경 요구사항 / Prerequisites

**[한국어]**

| 도구 | 버전 | 비고 |
| :-- | :-- | :-- |
| **Android Studio** | Panda 4 (2025.3.4) 이상 | Iguana 이전 버전은 AGP 8.13 미지원 |
| **JDK** | 21 (Android Studio 번들 JBR 권장) | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` |
| **Android SDK** | API 35 (Android 15) | Android Studio SDK Manager 로 설치 |
| **Android NDK** | **30.0.14904198** | SDK Manager → SDK Tools → NDK (Side by side) |
| **CMake** | 3.22.1 이상 | SDK Manager → SDK Tools |
| **Rust** | 1.82+ (rustup) | `core/` Rust 코어 빌드용 |
| **cargo-ndk** | 최신 | `cargo install --locked cargo-ndk` |
| **Gradle** | 8.10.2 (wrapper 통해 자동) | 수동 설치 불필요 |
| Git | 2.30+ | 일반 |

- **권장 플러그인**: Android Studio 기본 (Kotlin, Compose, NDK, CMake). 추가로 "Detekt", "Compose Multiplatform IDE Support".
- **OS별 주의사항**:
  - macOS: 경로에 괄호/공백 포함 시 외부 도구가 깨질 수 있음. `~/As-It-Sounds-app` 같은 paren-free 심볼릭 링크 권장.
  - Windows: WSL2 권장. JDK 경로에 한글/공백 X.
  - Linux: `apt install libtinfo5` 가 NDK 가 요구 (Ubuntu 22.04+).

**[English]**

| Tool | Version | Notes |
| :-- | :-- | :-- |
| **Android Studio** | Panda 4 (2025.3.4) or newer | Iguana and earlier don't support AGP 8.13 |
| **JDK** | 21 (use the bundled JBR) | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` |
| **Android SDK** | API 35 (Android 15) | Install via SDK Manager |
| **Android NDK** | **30.0.14904198** | SDK Manager → SDK Tools → NDK (Side by side) |
| **CMake** | 3.22.1+ | SDK Manager → SDK Tools |
| **Rust** | 1.82+ via rustup | Required for the `core/` Rust crate |
| **cargo-ndk** | latest | `cargo install --locked cargo-ndk` |
| **Gradle** | 8.10.2 (auto via wrapper) | Don't install manually |
| Git | 2.30+ | |

- **Recommended plugins**: Android Studio defaults (Kotlin, Compose, NDK, CMake). Optional: "Detekt".
- **OS notes**:
  - macOS: Parens/spaces in the path break some external tools — keep a `~/As-It-Sounds-app` paren-free symlink for safety.
  - Windows: WSL2 recommended; avoid CJK characters and spaces in the JDK path.
  - Linux: NDK needs `libtinfo5` on Ubuntu 22.04+ (`sudo apt install libtinfo5`).

---

## 3. 개발 환경 구성 (단계별) / Environment Setup (Step-by-Step)

**[한국어]**

1. **저장소 클론**
   ```bash
   git clone <repo-url> ~/As-It-Sounds-app
   cd ~/As-It-Sounds-app
   ```
2. **NDK 경로 환경변수 설정**
   ```bash
   export ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS 기준
   export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.14904198"
   ```
3. **whisper.cpp 가져오기 (한 번만)**
   ```bash
   mkdir -p core/third_party
   git clone --depth 1 --branch v1.7.5 \
     https://github.com/ggerganov/whisper.cpp \
     core/third_party/whisper.cpp
   ```
4. **Rust 코어 + Kotlin 바인딩 빌드 (4 ABI)**
   ```bash
   cd core
   ./scripts/build-android.sh
   # → android/app/src/main/jniLibs/<abi>/libais_ffi.so
   # → android/app/src/main/java/com/asitsounds/core/ais_ffi.kt
   ```
5. **Android Studio 에서 열기**
   - `File → Open` → `android/` 디렉터리 선택
   - JDK 21 (JBR) 자동 사용 — Settings → Build Tools → Gradle → Gradle JDK 가 `Embedded JDK` 인지 확인
   - Gradle Sync 자동 실행 — `local.properties` 에 `sdk.dir` 만 있는지 확인 (`ndk.dir` 라인 있으면 삭제)
6. **검증**
   ```bash
   ./gradlew :app:assembleDebug
   # 성공 시 → app/build/outputs/apk/debug/app-universal-debug.apk
   ```
   에뮬레이터/디바이스에 설치:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
   ```
   앱 실행 → Translit 탭에서 "As it sounds, it works." → 한글 음차 표시되면 OK.

**[English]**

1. **Clone the repo**
   ```bash
   git clone <repo-url> ~/As-It-Sounds-app
   cd ~/As-It-Sounds-app
   ```
2. **Export NDK paths**
   ```bash
   export ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS
   export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.14904198"
   ```
3. **Fetch whisper.cpp (one-time)**
   ```bash
   mkdir -p core/third_party
   git clone --depth 1 --branch v1.7.5 \
     https://github.com/ggerganov/whisper.cpp \
     core/third_party/whisper.cpp
   ```
4. **Build the Rust core + Kotlin bindings (4 ABIs)**
   ```bash
   cd core
   ./scripts/build-android.sh
   # → android/app/src/main/jniLibs/<abi>/libais_ffi.so
   # → android/app/src/main/java/com/asitsounds/core/ais_ffi.kt
   ```
5. **Open in Android Studio**
   - `File → Open` → select `android/`
   - JDK 21 (JBR) is auto-detected. Settings → Build Tools → Gradle → Gradle JDK should be `Embedded JDK`.
   - Gradle Sync runs automatically. Confirm `local.properties` contains only `sdk.dir`; if `ndk.dir` exists, delete it.
6. **Verify**
   ```bash
   ./gradlew :app:assembleDebug
   # → app/build/outputs/apk/debug/app-universal-debug.apk
   ```
   Install:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
   ```
   Launch → Translit tab → type "As it sounds, it works." → if Hangul appears, the setup is healthy.

---

## 4. 소스 구조 설명 / Source Structure

**[한국어]**

```tree
android/
├── README.md                          # 단축 빌드 가이드
├── settings.gradle.kts                # 모듈 선언 (:app)
├── build.gradle.kts                   # 루트 플러그인 alias
├── gradle.properties                  # configuration-cache, parallel
├── gradle/
│   └── libs.versions.toml             # 의존성 버전 카탈로그
├── local.properties                   # SDK 경로 (gitignored)
└── app/
    ├── build.gradle.kts               # AGP 설정 + ABI splits
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml        # 권한 + 서비스 + 인텐트
        ├── cpp/
        │   ├── CMakeLists.txt         # whisper.cpp 정적 링크
        │   └── ais_whisper_jni.cpp    # JNI 다리 (132 LOC)
        ├── jniLibs/<abi>/             # 빌드 산출 (gitignored)
        ├── java/com/asitsounds/
        │   ├── android/
        │   │   ├── AsItSoundsApp.kt   # Application class
        │   │   ├── MainActivity.kt    # Compose host
        │   │   ├── AppSettings.kt     # SharedPreferences 래퍼
        │   │   ├── ui/                # Compose 화면 (10 파일)
        │   │   ├── db/                # SQLiteOpenHelper + DAO
        │   │   ├── service/           # Foreground + Quick Tile
        │   │   └── whisper/           # 도메인 레이어 (8 파일)
        │   └── core/ais_ffi.kt        # UniFFI 생성 (gitignored)
        └── res/
            ├── values/strings.xml     # 영어
            ├── values-ko/strings.xml  # 한국어
            ├── values/themes.xml
            ├── mipmap-*/              # 아이콘
            ├── drawable/
            └── xml/                   # backup_rules, data_extraction
```

**핵심 모듈 의존 관계**:

- `ui/*` (Compose 뷰) → `whisper/Pipeline.run(...)` Flow 호출
- `whisper/Pipeline` → `AudioDecoder` (MediaCodec) → `WhisperNative` (JNI) → `Translator` (HTTP) → `com.asitsounds.core.transliterate` (UniFFI Kotlin)
- `service/TranscribeForegroundService` 는 같은 `Pipeline.run` 을 백그라운드에서 실행
- `db/AppDatabase` (싱글톤) 가 모든 Compose 뷰 + Pipeline 에서 공유
- `whisper/ModelManager` → HttpURLConnection → `filesDir/models/ggml-base.bin`

**[English]**

```tree
android/
├── README.md
├── settings.gradle.kts
├── build.gradle.kts                   # root plugin aliases
├── gradle.properties                  # configuration-cache, parallel
├── gradle/
│   └── libs.versions.toml             # version catalog
├── local.properties                   # SDK path (gitignored)
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/
        │   ├── CMakeLists.txt
        │   └── ais_whisper_jni.cpp    # JNI bridge, 132 LOC
        ├── jniLibs/<abi>/             # build output (gitignored)
        ├── java/com/asitsounds/
        │   ├── android/
        │   │   ├── AsItSoundsApp.kt
        │   │   ├── MainActivity.kt    # Compose host
        │   │   ├── AppSettings.kt     # SharedPreferences wrapper
        │   │   ├── ui/                # 10 Compose files
        │   │   ├── db/                # SQLiteOpenHelper + DAOs
        │   │   ├── service/           # Foreground + Quick Tile
        │   │   └── whisper/           # 8 domain files
        │   └── core/ais_ffi.kt        # UniFFI generated (gitignored)
        └── res/
            ├── values/strings.xml     # English
            ├── values-ko/strings.xml  # Korean
            ├── values/themes.xml
            ├── mipmap-*/              # icons
            ├── drawable/
            └── xml/                   # backup_rules, data_extraction
```

**Core module dependencies**:

- `ui/*` (Compose views) → calls `whisper/Pipeline.run(...)` (a Kotlin Flow)
- `whisper/Pipeline` → `AudioDecoder` (MediaCodec) → `WhisperNative` (JNI) → `Translator` (HTTP) → `com.asitsounds.core.transliterate` (UniFFI Kotlin)
- `service/TranscribeForegroundService` runs the same `Pipeline.run` in the background
- `db/AppDatabase` (singleton) is shared by every Compose view and the pipeline
- `whisper/ModelManager` → HttpURLConnection → `filesDir/models/ggml-base.bin`

---

## 5. 로컬 빌드 방법 / Local Build

**[한국어]**

```bash
# 디버그 빌드 (개발용)
./gradlew :app:assembleDebug
# 산출: app/build/outputs/apk/debug/app-{universal,arm64-v8a,armeabi-v7a,x86_64}-debug.apk

# 릴리스 빌드 (서명 필요 — .doc/signing.md 참조)
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease    # AAB (Play Console 권장 형식)

# Native 만 다시 빌드 (Rust 코어 + JNI .so 갱신)
cd ../core && ./scripts/build-android.sh
```

**빌드 결과물 위치**:
- APK: `app/build/outputs/apk/{debug,release}/`
- AAB: `app/build/outputs/bundle/release/`
- Native lib: `app/build/intermediates/cxx/Debug/.../obj/<abi>/libais_whisper.so`

**자주 발생하는 빌드 오류**:

| 오류 | 원인 | 해결 |
| :-- | :-- | :-- |
| `[CXX5106] NDK was located by using ndk.dir property` | `local.properties` 에 deprecated `ndk.dir` 라인 존재 | 해당 라인 삭제, `app/build.gradle.kts` 의 `ndkVersion = "30.0.14904198"` 만 유지 |
| `whisper.cpp not found at ${WHISPER_DIR}` | git clone 누락 | Step 3.3 의 `git clone whisper.cpp` 실행 |
| `Wrong plugin option format: null` (KSP) | Kotlin 2.0.21 + KSP 호환 이슈 | 본 프로젝트는 Room 대신 SQLiteOpenHelper 사용으로 회피. KSP 플러그인 추가하지 말 것 |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | x86_64 APK 를 arm64 에뮬에 설치 | `app-universal-debug.apk` 또는 `app-arm64-v8a-debug.apk` 사용 |
| `program alignment (8192) < page size (16384)` | API 37+ 에뮬 16KB page + 구 JNA | JNA 5.17.0 이상 필수 (현재 충족) |

**[English]**

```bash
# Debug build (dev)
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-{universal,arm64-v8a,...}-debug.apk

# Release build (signing required — see .doc/signing.md)
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease    # AAB for Play Console

# Rebuild native only
cd ../core && ./scripts/build-android.sh
```

**Build outputs**:
- APK: `app/build/outputs/apk/{debug,release}/`
- AAB: `app/build/outputs/bundle/release/`
- Native libs: `app/build/intermediates/cxx/Debug/.../obj/<abi>/libais_whisper.so`

**Common build errors**:

| Error | Cause | Fix |
| :-- | :-- | :-- |
| `[CXX5106] NDK was located by using ndk.dir property` | Deprecated `ndk.dir` in `local.properties` | Delete that line; keep only `app/build.gradle.kts` `ndkVersion` |
| `whisper.cpp not found at ${WHISPER_DIR}` | Missing git clone | Run the `git clone whisper.cpp` step in 3.3 |
| `Wrong plugin option format: null` (KSP) | Kotlin 2.0.21 + KSP incompatibility | We use SQLiteOpenHelper instead of Room to avoid KSP entirely; don't add the KSP plugin |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Installed x86_64 APK on arm64 emulator | Use `app-universal-debug.apk` or `app-arm64-v8a-debug.apk` |
| `program alignment (8192) < page size (16384)` | API 37+ emulator (16 KB page) with old JNA | Requires JNA 5.17.0+ (already satisfied) |

---

## 6. 로컬 실행 방법 / Running Locally

**[한국어]**

```bash
# 1. 에뮬레이터 부팅 (Android Studio AVD Manager 또는 CLI)
$ANDROID_HOME/emulator/emulator -avd Medium_Phone

# 2. 설치
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk

# 3. 실행
adb shell am start -n com.asitsounds.android/.MainActivity

# 4. 로그 보기
adb logcat | grep -iE "asitsounds|AisWhisper|FATAL"

# 5. 모델 사전 푸시 (옵션 — 첫 실행 시 142 MB 다운로드 회피)
adb push <path>/ggml-base.bin /data/local/tmp/ggml-base.bin
adb shell "cat /data/local/tmp/ggml-base.bin | \
  run-as com.asitsounds.android sh -c \
  'mkdir -p files/models && cat > files/models/ggml-base.bin'"
```

**접속/포트**: 모바일 앱이므로 포트 없음. 호스트에서 ADB (5037) + 에뮬레이터 (5554/5556) 만 점유.

**실행 확인**:
- `adb shell pidof com.asitsounds.android` 가 PID 반환하면 실행 중
- 앱 화면에 "Transcribe / Library / Translit / Settings" 4탭 표시
- Translit 탭 입력 → 한글 출력 표시 시 정상

**[English]**

```bash
# 1. Boot an emulator (Android Studio AVD Manager or CLI)
$ANDROID_HOME/emulator/emulator -avd Medium_Phone

# 2. Install
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk

# 3. Launch
adb shell am start -n com.asitsounds.android/.MainActivity

# 4. Tail logs
adb logcat | grep -iE "asitsounds|AisWhisper|FATAL"

# 5. Pre-seed the Whisper model (optional — skip 142 MB first-run download)
adb push <path>/ggml-base.bin /data/local/tmp/ggml-base.bin
adb shell "cat /data/local/tmp/ggml-base.bin | \
  run-as com.asitsounds.android sh -c \
  'mkdir -p files/models && cat > files/models/ggml-base.bin'"
```

**Ports**: None (mobile app). Only ADB (5037) + emulator (5554/5556) on the host.

**Verify**:
- `adb shell pidof com.asitsounds.android` returns a PID → running
- The app shows 4 tabs: Transcribe / Library / Translit / Settings
- Typing in the Translit tab returns Hangul output → end-to-end healthy

---

## 7. 테스트 실행 / Running Tests

**[한국어]**

⚠️ **현재 본 프로젝트는 단위/통합 테스트가 작성되어 있지 않습니다.** `app/src/test/` 및 `app/src/androidTest/` 디렉터리가 존재하지 않습니다. 테스트 의존성 (JUnit 4, AndroidX Test, Espresso) 만 `libs.versions.toml` 에 선언되어 있고 사용처 없음.

향후 테스트 작성 시 권장:

```bash
# 단위 테스트 (작성 후)
./gradlew :app:testDebugUnitTest

# 인스트루먼티드 테스트 (에뮬/디바이스 필요)
./gradlew :app:connectedDebugAndroidTest

# 커버리지 (jacoco 플러그인 추가 필요)
./gradlew :app:createDebugCoverageReport
```

**픽스처 권고**:
- 음차 회귀 검증용 fixture: 데스크탑 코어 `As-It-Sounds/.samples/01_19_04.mp3` 의 transcribe 결과 → translit 출력을 `assertEquals` 30 세트.
- Rust 측은 이미 `cargo test --release` 30 통과 — 동일 입력을 Kotlin 측에서 `transliterate(...)` 호출하여 같은 출력 확인.

**[English]**

⚠️ **This project currently has no unit or integration tests.** Neither `app/src/test/` nor `app/src/androidTest/` exists. Test dependencies (JUnit 4, AndroidX Test, Espresso) are declared in `libs.versions.toml` but unused.

When tests are added, run them via:

```bash
# Unit tests
./gradlew :app:testDebugUnitTest

# Instrumented tests (require emulator/device)
./gradlew :app:connectedDebugAndroidTest

# Coverage (requires jacoco plugin)
./gradlew :app:createDebugCoverageReport
```

**Recommended fixtures**:
- Transliteration regression: feed the desktop core's `As-It-Sounds/.samples/01_19_04.mp3` transcripts through `transliterate(...)` from Kotlin and assert against 30 known-good Hangul outputs.
- The Rust side already has `cargo test --release` (30 tests passing). Mirror the same inputs from Kotlin to confirm the FFI bridge preserves results.

---

## 8. 코드 기여 가이드라인 / Contribution Guidelines

**[한국어]**

⚠️ 별도 `CONTRIBUTING.md` / `CODE_OF_CONDUCT.md` 파일은 부재. 아래는 워크스페이스 PRD 와 코드 패턴에서 유추한 권장 사항입니다.

- **브랜치 전략**:
  - `main` = 항상 빌드 가능한 단일 트렁크
  - `feature/<slug>` 짧게 살린 후 PR → `main` squash merge
  - 릴리스는 태그 (`v0.1.0`, `v0.1.1`, …) 로 마킹
- **커밋 메시지**:
  - 제목 1줄 (≤ 70자) + 본문 (선택). 예: `feat(pipeline): wire Translator into Flow`
  - 본문에 "왜" 를 적되 "무엇" 은 diff 가 말해줌
- **PR / 코드 리뷰 절차**:
  - GitHub Actions `android.yml` 자동 빌드 통과 필수 (현재 수동 트리거)
  - 최소 1 reviewer 승인
  - whisper / JNI 변경 시 양 ABI (arm64-v8a + x86_64) 동시 검증
- **코딩 스타일**:
  - Kotlin 공식 스타일 (Android Studio 기본). `gradle.properties` 에 `kotlin.code.style=official`
  - Compose: state hoisting + 단방향 데이터 흐름
  - 명명: `*Screen` = Compose 최상위, `*ViewModel` 또는 `UiState` = state holder
  - 네이티브: `nativeXxx` prefix 로 JNI 메서드 명시, 절대 Public Kotlin API 로 노출하지 말 것 (래퍼 `WhisperNative` 사용)

**[English]**

⚠️ No dedicated `CONTRIBUTING.md` or `CODE_OF_CONDUCT.md`. The following are inferred from the workspace PRD and code patterns.

- **Branching**:
  - `main` = always-buildable trunk
  - `feature/<slug>` short-lived branches → squash-merged via PR
  - Releases tagged `v0.1.0`, `v0.1.1`, …
- **Commits**:
  - Subject ≤ 70 chars + optional body. Example: `feat(pipeline): wire Translator into Flow`
  - Use the body for "why"; the diff already tells you "what"
- **PR / review**:
  - The `android.yml` GitHub Actions build must pass (currently manual trigger; see Stage 2.5 disabled state)
  - ≥ 1 reviewer
  - Whisper / JNI changes must be verified on both arm64-v8a and x86_64 emulators
- **Style**:
  - Official Kotlin style (Android Studio default). `gradle.properties` enforces `kotlin.code.style=official`
  - Compose: hoist state, single-direction data flow
  - Naming: `*Screen` = top-level composable, `UiState` = state holder
  - Native: prefix JNI methods with `nativeXxx`; never expose them as public Kotlin API — wrap them in `WhisperNative`

---

## 9. 자주 묻는 질문 / FAQ

**[한국어]**

1. **Q: Gradle Sync 시 `[CXX5106] NDK was located by using ndk.dir property` 경고가 나옵니다.**
   A: `local.properties` 의 `ndk.dir=...` 라인을 삭제하세요. NDK 버전은 `app/build.gradle.kts` 의 `ndkVersion = "30.0.14904198"` 에서 관리됩니다.

2. **Q: 앱이 즉시 종료되고 logcat 에 `UnsatisfiedLinkError: libjnidispatch.so ... program alignment (8192) < page size (16384)` 가 표시됩니다.**
   A: API 36+ 에뮬레이터의 16 KB page size 와 구 JNA (5.15) 8 KB 정렬 충돌입니다. `libs.versions.toml` 의 JNA 버전이 5.17.0 이상인지 확인하세요.

3. **Q: 빌드는 성공하는데 `adb install` 이 "INSTALL_FAILED_NO_MATCHING_ABIS" 로 실패합니다.**
   A: 에뮬레이터 아키텍처와 APK ABI 가 안 맞습니다. `app-universal-debug.apk` (모든 ABI 포함) 또는 `app-arm64-v8a-debug.apk` 를 설치하세요.

4. **Q: Whisper 모델 다운로드가 매번 처음부터 다시 됩니다.**
   A: `ModelManager.download` 가 `.part` 임시 파일을 사용합니다. 다운로드 도중 앱이 죽으면 `.part` 만 남고 `.bin` 으로 rename 안 됨. 안전한 디렉터리 정리: `adb shell run-as com.asitsounds.android rm files/models/*.part`.

5. **Q: 번역이 "(translate failed)" 로 표시됩니다.**
   A: MyMemory 무료 한도 (1000 단어/일) 초과 또는 네트워크 차단. Settings → Translator 에서 LibreTranslate 셀프호스트 또는 Off 로 전환.

6. **Q: `git clone whisper.cpp` 단계를 건너뛰면 어떻게 됩니까?**
   A: CMake 가 `FATAL_ERROR: whisper.cpp not found at ${WHISPER_DIR}` 로 빌드 중단합니다. `core/scripts/fetch-whisper.sh` 또는 수동 clone 필수.

7. **Q: Android Studio 의 Gradle JDK 설정이 자꾸 시스템 JDK 로 되돌아갑니다.**
   A: Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK 를 명시적으로 `Embedded JDK` (Android Studio 의 JBR) 로 변경하세요.

**[English]**

1. **Q: Gradle sync warns `[CXX5106] NDK was located by using ndk.dir property`.**
   A: Delete the `ndk.dir=...` line in `local.properties`. The NDK version is owned by `ndkVersion = "30.0.14904198"` in `app/build.gradle.kts`.

2. **Q: The app crashes immediately with `UnsatisfiedLinkError: libjnidispatch.so ... program alignment (8192) < page size (16384)`.**
   A: API 36+ emulators use 16 KB pages but old JNA (5.15) is built with 8 KB alignment. Make sure `libs.versions.toml` pins JNA to **5.17.0 or newer**.

3. **Q: Build succeeds but `adb install` fails with "INSTALL_FAILED_NO_MATCHING_ABIS".**
   A: Emulator ABI doesn't match. Install `app-universal-debug.apk` (all ABIs) or the matching per-ABI APK.

4. **Q: Whisper model downloads from scratch every time.**
   A: `ModelManager.download` writes a `.part` temp file then renames. If the app dies mid-download, the `.part` stays. Clean it: `adb shell run-as com.asitsounds.android rm files/models/*.part`.

5. **Q: Translation shows "(translate failed)".**
   A: MyMemory's free tier (1000 words/day) is hit, or the network is blocked. Switch to a self-hosted LibreTranslate or "Off" in Settings → Translator.

6. **Q: What if I skip the `git clone whisper.cpp` step?**
   A: CMake aborts with `FATAL_ERROR: whisper.cpp not found at ${WHISPER_DIR}`. Either run `core/scripts/fetch-whisper.sh` or clone manually before building.

7. **Q: Android Studio keeps reverting Gradle JDK to my system JDK.**
   A: Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK — explicitly select **Embedded JDK** (the bundled JBR).
