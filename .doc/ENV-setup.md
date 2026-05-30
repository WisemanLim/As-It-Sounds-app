# 환경 설정 — As It Sounds (모바일)

> 🌐 **한국어** · [English](ENV-setup.en.md)

Phase-0 스캐폴드가 머신에 요구하는 항목을 의존성 순서대로 정리한 문서입니다. **자동화됨**으로 표시된 항목은 빌드 스크립트가 처리하며, 나머지는 한 번만 수동으로 설정합니다.

## 이미 확인됨 ✅

| 도구 | 버전 | 경로 |
| :-- | :-- | :-- |
| Rust 툴체인 | 1.95+ | `~/.cargo/bin/cargo` |
| iOS Rust 타겟 | aarch64 / aarch64-sim / x86_64 | 설치됨 |
| Android Rust 타겟 | aarch64 / armv7 / x86_64 / i686 (-linux-android) | 설치됨 |
| Swift | 6.3 | `/usr/bin/swift` |
| macOS SDK | 26.0 | CommandLineTools에 번들 포함 |
| Android SDK 기본 | — | `~/Library/Android/sdk` |

## 조치 필요 ⏳

### A. `xcode-select`를 Xcode.app으로 전환 (iOS SDK에 필요)

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
xcode-select -p          # 확인
xcodebuild -version      # 확인
```

이 단계를 건너뛰면 `xcrun --sdk iphoneos --show-sdk-path`가 실패하고 `cargo build --target aarch64-apple-ios` 실행 시 링커 경고가 발생합니다. 빌드 자체는 `libais_ffi.a`를 생성하지만, 해당 파일로 Xcode 앱을 빌드할 수 없습니다.

### B. xcodegen 설치 (프로젝트 생성기)

```bash
brew install xcodegen
xcodegen --version
```

`.xcodeproj`를 직접 커밋하는 대신 yaml 파일(`ios/project.yml`)을 사용하며, `.xcodeproj`는 생성됩니다. 바이너리 파일의 머지 충돌이 발생하지 않습니다.

### C. Android NDK + 커맨드라인 도구 설치

Android Studio를 통한 설치가 가장 간편합니다:

1. **Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools** 탭 열기
2. 다음 항목을 체크 ✓ 하고 Apply 클릭:
   - **NDK (Side by side)** → 최신 버전 선택 (r27 이상)
   - **CMake**
   - **Android SDK Command-line Tools (latest)**

이미 커맨드라인 도구가 설치된 경우 `sdkmanager`로도 설치 가능합니다:

```bash
sdkmanager --install "ndk;27.2.12479018" "cmake;3.30.5"
```

이후 셸 rc 파일에 다음을 추가합니다:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"   # 설치된 버전에 맞게 조정
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### D. cargo-ndk 설치 (Android 크로스 빌드 도우미)

**자동화됨**: `core/scripts/build-android.sh`가 누락된 경우 `cargo install --locked cargo-ndk`를 자동 실행합니다. 명시적으로 직접 설치할 수도 있습니다:

```bash
cargo install --locked cargo-ndk
```

## 전체 체인 검증

```bash
cd As-It-Sounds-app/core

# 호스트 빌드 + 테스트 (추가 설정 불필요, 현재 동작)
cargo test --release
# → "30 passed; 0 failed"

# iOS 크로스 빌드 (단계 A 완료 필요)
./scripts/build-ios.sh
# → target/AisCoreFFI.xcframework 생성

# Android 크로스 빌드 (단계 C, D 완료 필요)
./scripts/build-android.sh
# → ../android/app/src/main/jniLibs/<abi>/libais_ffi.so (4개 ABI) 생성
```

## 각 단계가 해제하는 기능

| 단계 | 해제되는 기능 |
| :-- | :-- |
| A — xcode-select | iOS SDK 조회, 시뮬레이터 / 디바이스 빌드, xcodebuild |
| B — xcodegen | `ios/project.yml` → `ios/AsItSounds.xcodeproj` |
| C — NDK + cmake | Android 크로스 컴파일, 향후 whisper.cpp JNI |
| D — cargo-ndk | Android용 원샷 멀티 ABI Rust 빌드 |

## 문제 해결

| 증상 | 해결 방법 |
| :-- | :-- |
| `xcrun: error: SDK "iphoneos" cannot be located` | 단계 A 실행 |
| `xcodegen: command not found` | 단계 B 실행 |
| `ANDROID_NDK_HOME must be set` | 단계 C 실행 |
| `cargo-ndk: command not found` | 단계 D 실행 |
| Xcode에서 `Cannot find module 'AisCoreFFI'` | `core/scripts/build-ios.sh` 먼저 실행 후 `xcodegen generate` 재실행 |
| Compose에서 `com.asitsounds.core.transliterate` 임포트 빨간색 | `core/scripts/build-android.sh` 실행하여 Kotlin 바인딩을 `app/src/main/java/com/asitsounds/core/`에 생성 |
