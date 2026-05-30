# As It Sounds — iOS app

> 🌐 [한국어](README.md) · **English**

Native SwiftUI app that consumes the shared Rust core (`../core/`) via UniFFI.

## Setup (one-time)

```bash
# 1. Point xcode-select at Xcode.app (CommandLineTools alone is not enough)
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer

# 2. Install xcodegen
brew install xcodegen

# 3. Build the Rust core for iOS device + simulator
cd ../core
./scripts/build-ios.sh

# 4. Generate the Xcode project
cd ../ios
xcodegen generate

# 5. Open and run
open AsItSounds.xcodeproj
```

`build-ios.sh` produces:
- `core/target/AisCoreFFI.xcframework` (linked, not embedded — pure Rust static lib)
- `core/bindings/swift/AisCoreFFI.swift` (added as a source file by xcodegen)

## Repo layout

```
ios/
├── project.yml                  # xcodegen spec
├── AsItSounds/
│   ├── AsItSoundsApp.swift      # @main, scene root
│   ├── Features/
│   │   ├── RootView.swift       # TabView shell
│   │   ├── TranslitDemoView.swift   # Phase-0 demo: text → Hangul via Rust FFI
│   │   ├── LibraryPlaceholderView.swift
│   │   └── SettingsPlaceholderView.swift
│   └── Resources/
│       ├── Info.plist
│       └── Assets.xcassets/
└── README.md
```

## Phase-0 demo

`TranslitDemoView` types into the Rust core and renders Hangul output. Validates the FFI end-to-end before adding Whisper, AVFoundation, GRDB, etc.

## Next steps (matches `.doc/PRD-for-iPhone.md`)

| Phase | Work |
|:--|:--|
| 1 | Whisper.cpp + Core ML integration (Obj-C++ bridge) |
| 2 | AVFoundation audio decode |
| 3 | GRDB + project/file/segment schema |
| 4 | Translator protocol (Apple Translation + 3 fallbacks) |
| 5 | Pipeline orchestration (actor) + AsyncStream |
| 6 | Share Extension, BGProcessingTask, settings UI |
