# Environment setup — As It Sounds (mobile)

> 🌐 [한국어](ENV-setup.md) · **English**

This is what the Phase-0 scaffold expects on your machine, in dependency order. Items marked **automated** are already taken care of by the build scripts; the rest you do once.

## Already verified ✅

| Tool | Version | Path |
| :-- | :-- | :-- |
| Rust toolchain | 1.95+ | `~/.cargo/bin/cargo` |
| iOS Rust targets | aarch64 / aarch64-sim / x86_64 | installed |
| Android Rust targets | aarch64 / armv7 / x86_64 / i686 (-linux-android) | installed |
| Swift | 6.3 | `/usr/bin/swift` |
| macOS SDK | 26.0 | bundled with CommandLineTools |
| Android SDK base | — | `~/Library/Android/sdk` |

## Action required ⏳

### A. Switch `xcode-select` to Xcode.app (needed for iOS SDK)

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
xcode-select -p          # confirm
xcodebuild -version      # confirm
```

Without this, `xcrun --sdk iphoneos --show-sdk-path` fails and `cargo build --target aarch64-apple-ios` emits a linker warning. The build technically still produces `libais_ffi.a`, but you cannot build the Xcode app from it.

### B. Install xcodegen (project generator)

```bash
brew install xcodegen
xcodegen --version
```

We use yaml (`ios/project.yml`) instead of checking in `.xcodeproj`, which is generated. No merge conflicts on a binary file.

### C. Install Android NDK + command-line tools

Easiest via Android Studio:

1. Open **Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools** tab
2. Check ✓ on these and click Apply:
   - **NDK (Side by side)** → pick the latest (r27 or newer)
   - **CMake**
   - **Android SDK Command-line Tools (latest)**

Or via `sdkmanager` if you already have command-line tools:

```bash
sdkmanager --install "ndk;27.2.12479018" "cmake;3.30.5"
```

Then export to your shell rc file:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"   # adjust to your version
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### D. Install cargo-ndk (Android cross-build helper)

**Automated**: `core/scripts/build-android.sh` runs `cargo install --locked cargo-ndk` if it's missing. You can also do it explicitly:

```bash
cargo install --locked cargo-ndk
```

## Verifying the full chain

```bash
cd As-It-Sounds-app/core

# Host build + tests (no extra setup required, works today)
cargo test --release
# → "30 passed; 0 failed"

# iOS cross-build (needs step A done)
./scripts/build-ios.sh
# → produces target/AisCoreFFI.xcframework

# Android cross-build (needs steps C, D done)
./scripts/build-android.sh
# → produces ../android/app/src/main/jniLibs/<abi>/libais_ffi.so for 4 ABIs
```

## What each step unblocks

| Step | Unblocks |
| :-- | :-- |
| A — xcode-select | iOS SDK lookup, simulator / device builds, xcodebuild |
| B — xcodegen | `ios/project.yml` → `ios/AsItSounds.xcodeproj` |
| C — NDK + cmake | Android cross-compile, future whisper.cpp JNI |
| D — cargo-ndk | One-shot multi-ABI Rust build for Android |

## Troubleshooting

| Symptom | Fix |
| :-- | :-- |
| `xcrun: error: SDK "iphoneos" cannot be located` | Run step A |
| `xcodegen: command not found` | Run step B |
| `ANDROID_NDK_HOME must be set` | Run step C |
| `cargo-ndk: command not found` | Run step D |
| `Cannot find module 'AisCoreFFI'` in Xcode | Run `core/scripts/build-ios.sh` first; then `xcodegen generate` again |
| Compose import `com.asitsounds.core.transliterate` red | Run `core/scripts/build-android.sh` to emit the Kotlin binding into `app/src/main/java/com/asitsounds/core/` |
