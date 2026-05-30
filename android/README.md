# As It Sounds — Android 앱

> 🌐 **한국어** · [English](README.en.md)

공유 Rust 코어(`../core/`)를 UniFFI + JNA를 통해 사용하는 네이티브 Compose 앱.

## 초기 설정 (최초 1회)

```bash
# 1. Android NDK 설치 (Android Studio → SDK Manager → SDK Tools → NDK (Side by side) → r27+ 설치).
#    또는 sdkmanager가 PATH에 있다면 커맨드라인으로:
#    sdkmanager --install "ndk;27.2.12479018"

# 2. NDK 경로를 셸에 지정 (build-android.sh에서도 사용됨)
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.2.12479018"

# 3. 모든 4가지 ABI용 Rust 코어 빌드 + Kotlin 바인딩 생성
cd ../core
./scripts/build-android.sh
# → ../android/app/src/main/jniLibs/<abi>/libais_ffi.so
# → ../android/app/src/main/java/com/asitsounds/core/ais_ffi.kt

# 4. Android Studio에서 프로젝트 열기
#    File → Open → As-It-Sounds-app/android
#    Gradle 동기화 후 에뮬레이터 또는 기기에서 실행 ▶︎
```

## 저장소 구조

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml          # 버전 카탈로그
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/asitsounds/android/
│       │   ├── AsItSoundsApp.kt
│       │   ├── MainActivity.kt
│       │   └── ui/                 # Compose UI
│       ├── res/values/
│       └── jniLibs/                # (빌드 산출물 — gitignored) ABI별 libais_ffi.so
└── README.md
```

`build-android.sh` 생성 파일:
- `app/src/main/jniLibs/{arm64-v8a, armeabi-v7a, x86_64, x86}/libais_ffi.so`
- `app/src/main/java/com/asitsounds/core/ais_ffi.kt` (Kotlin FFI 바인딩)

두 디렉터리 모두 gitignored 처리되며 소스에서 재생성됩니다.

## Phase-0 데모

`TranslitDemoScreen`이 `com.asitsounds.core.transliterate(input)`을 직접 호출합니다. Whisper, MediaCodec, Room 등을 추가하기 전에 JNA + FFI 브리지를 검증합니다.

## 다음 단계 (`.doc/PRD-for-Android.md` 참조)

| 단계 | 작업 |
|:--|:--|
| 1 | JNI + Vulkan/OpenCL을 통한 whisper.cpp |
| 2 | MediaCodec 오디오 디코드 + Media3 ExoPlayer |
| 3 | Room (프로젝트/파일/세그먼트 스키마) |
| 4 | Translator 인터페이스 (ML Kit + 3가지 폴백) |
| 5 | Foreground Service + WorkManager |
| 6 | Share intent 타겟, Settings UI, PAD 모델 배포 |
