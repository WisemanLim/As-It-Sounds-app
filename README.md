# As It Sounds — Mobile (iOS + Android)

Native mobile companions to the desktop / web app at `../NGL(Go,Rust,...)/As-It-Sounds`. Shares the desktop's Rust transliteration core verbatim; everything else is platform-native.

| Path | Stack | Purpose |
| :-- | :-- | :-- |
| [`core/`](./core/) | Rust workspace, UniFFI | Re-exports the desktop's `translit.rs` and exposes `transliterate()` / `version()` to Swift + Kotlin |
| [`ios/`](./ios/) | Swift 5.10, SwiftUI, iOS 17+ | iPhone / iPad app |
| [`android/`](./android/) | Kotlin 2.0, Jetpack Compose, API 28–35 | Android phone / tablet app |
| [`.doc/`](./.doc/) | — | PRDs for iPhone and Android |

The desktop crate's `translit.rs` (1713 LOC, 30 regression tests) is pulled in by [`core/crates/ais-translit/src/lib.rs`](core/crates/ais-translit/src/lib.rs) via `#[path]` — no copy, no fork.

## Phase-0 status (this scaffold)

- ✅ Rust workspace builds (`cargo build --release`)
- ✅ All 30 upstream tests pass (`cargo test --release`)
- ✅ Swift binding generated: [`core/bindings/swift/AisCoreFFI.swift`](core/bindings/swift/AisCoreFFI.swift)
- ✅ Kotlin binding generated: [`core/bindings/kotlin/com/asitsounds/core/ais_ffi.kt`](core/bindings/kotlin/com/asitsounds/core/ais_ffi.kt)
- ✅ iOS device static lib cross-compiled: `libais_ffi.a` for `aarch64-apple-ios`
- ✅ iOS SwiftUI scaffold (`TranslitDemoView` calls FFI directly)
- ✅ Android Compose scaffold (`TranslitDemoScreen` calls FFI directly)
- ⏳ Xcode project generation — pending `xcode-select` switch + `xcodegen`
- ⏳ Android NDK build — pending NDK install

## Environment setup remaining (one-time)

These two steps require your action (sudo + Android Studio UI):

### 1. Point xcode-select at Xcode.app

Currently your `xcode-select -p` returns `/Library/Developer/CommandLineTools`, which lacks the iOS SDK.

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
# Verify
xcode-select -p   # → /Applications/Xcode.app/Contents/Developer
xcodebuild -version
```

Then install `xcodegen`:

```bash
brew install xcodegen
```

### 2. Install Android NDK + command-line tools

Open **Android Studio Panda → Settings → Languages & Frameworks → Android SDK → SDK Tools**. Check:

- **NDK (Side by side)** — install r27 or later
- **CMake** — install the latest
- **Android SDK Command-line Tools (latest)**

Then export the NDK path:

```bash
# Find the version you installed
ls ~/Library/Android/sdk/ndk
# e.g. 27.2.12479018

# Add to ~/.zshrc (or ~/.bash_profile)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

## Build & run

Once the environment is set up:

### iOS

```bash
cd core && ./scripts/build-ios.sh
cd ../ios && xcodegen generate
open AsItSounds.xcodeproj
# → ⌘R in Xcode (target: iPhone 15 simulator or any iOS 17+ device)
```

### Android

```bash
cd core && ./scripts/build-android.sh
# Then open ./android/ in Android Studio Panda
# → Run ▶︎ (target: emulator API 28+ or physical device)
```

## What runs in the Phase-0 demo

Both apps boot to a 3-tab shell. The **Translit** tab has a text box pre-filled with `"As it sounds, it works."` and a button that calls `transliterate()` through the Rust FFI. Output is rendered in a Hangul-styled card. This proves the entire toolchain works before any Whisper / media / DB code is added.

Try changing the input to e.g.:

| Input | Expected output |
| :-- | :-- |
| `As it sounds, it works.` | 애즈 잇 사운즈, 잇 웍스 (approx) |
| `C. Right around the corner.` | 씨. 라이트 어라운드 더 코너 |
| `bonjour` | 봉주흐 |
| `Москва` | 모스크바 |
| `안녕하세요` | 안녕하세요 (pass-through) |

## Roadmap

Per [PRD-for-iPhone.md](.doc/PRD-for-iPhone.md) and [PRD-for-Android.md](.doc/PRD-for-Android.md), Phase-1 will add per platform:

1. Whisper.cpp integration (Core ML on iOS, JNI on Android)
2. Media decode (AVFoundation / MediaCodec)
3. Persistence (GRDB / Room) with shared schema
4. Translator protocol with 4 backends (Apple Translation / ML Kit + MyMemory + LibreTranslate + DeepL)
5. Pipeline orchestration + progress events
6. File import (Files/PhotoPicker/Share/SAF)
7. Background processing (BGTask / Foreground Service)

Total estimate: 11 weeks iOS, 12 weeks Android (solo dev).
