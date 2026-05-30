# As It Sounds — Android app

> 🌐 [한국어](README.md) · **English**

Native Compose app that consumes the shared Rust core (`../core/`) via UniFFI + JNA.

## Setup (one-time)

```bash
# 1. Install the Android NDK (open Android Studio → SDK Manager → SDK Tools → NDK (Side by side) → install r27+).
#    Or via command line if sdkmanager is in PATH:
#    sdkmanager --install "ndk;27.2.12479018"

# 2. Tell shell where the NDK is (also picked up by build-android.sh)
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.2.12479018"

# 3. Build the Rust core for all 4 ABIs + emit Kotlin bindings
cd ../core
./scripts/build-android.sh
# → ../android/app/src/main/jniLibs/<abi>/libais_ffi.so
# → ../android/app/src/main/java/com/asitsounds/core/ais_ffi.kt

# 4. Open the project in Android Studio
#    File → Open → As-It-Sounds-app/android
#    Let Gradle sync, then Run ▶︎ on an emulator or device.
```

## Repo layout

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml          # Version catalog
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
│       └── jniLibs/                # (built — gitignored) per-ABI libais_ffi.so
└── README.md
```

`build-android.sh` writes:
- `app/src/main/jniLibs/{arm64-v8a, armeabi-v7a, x86_64, x86}/libais_ffi.so`
- `app/src/main/java/com/asitsounds/core/ais_ffi.kt` (Kotlin FFI binding)

Both directories are gitignored — regenerated from source.

## Phase-0 demo

`TranslitDemoScreen` calls `com.asitsounds.core.transliterate(input)` directly. Validates the JNA + FFI bridge before adding Whisper, MediaCodec, Room, etc.

## Next steps (matches `.doc/PRD-for-Android.md`)

| Phase | Work |
|:--|:--|
| 1 | whisper.cpp via JNI + Vulkan/OpenCL |
| 2 | MediaCodec audio decode + Media3 ExoPlayer |
| 3 | Room (project/file/segment schema) |
| 4 | Translator interface (ML Kit + 3 fallbacks) |
| 5 | Foreground Service + WorkManager |
| 6 | Share intent target, Settings UI, PAD model delivery |
