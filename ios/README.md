# As It Sounds — iOS 앱

> 🌐 **한국어** · [English](README.en.md)

Rust 코어(`../core/`)를 UniFFI를 통해 사용하는 네이티브 SwiftUI 앱입니다.

## 초기 설정 (최초 1회)

```bash
# 1. xcode-select를 Xcode.app으로 지정 (CommandLineTools만으로는 부족함)
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer

# 2. xcodegen 설치
brew install xcodegen

# 3. iOS 기기 + 시뮬레이터용 Rust 코어 빌드
cd ../core
./scripts/build-ios.sh

# 4. Xcode 프로젝트 생성
cd ../ios
xcodegen generate

# 5. 열기 및 실행
open AsItSounds.xcodeproj
```

`build-ios.sh` 실행 결과:
- `core/target/AisCoreFFI.xcframework` (링크됨, 임베드 아님 — 순수 Rust 정적 라이브러리)
- `core/bindings/swift/AisCoreFFI.swift` (xcodegen에 의해 소스 파일로 추가됨)

## 저장소 구조

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

## Phase-0 데모

`TranslitDemoView`는 Rust 코어에 텍스트를 입력하여 한글 출력을 렌더링합니다. Whisper, AVFoundation, GRDB 등을 추가하기 전에 FFI 전체 흐름을 검증합니다.

## 다음 단계 (`.doc/PRD-for-iPhone.md` 참고)

| 단계 | 작업 |
|:--|:--|
| 1 | Whisper.cpp + Core ML 통합 (Obj-C++ 브리지) |
| 2 | AVFoundation 오디오 디코드 |
| 3 | GRDB + 프로젝트/파일/세그먼트 스키마 |
| 4 | 번역기 프로토콜 (Apple Translation + 3개 폴백) |
| 5 | 파이프라인 오케스트레이션 (actor) + AsyncStream |
| 6 | Share Extension, BGProcessingTask, 설정 UI |
