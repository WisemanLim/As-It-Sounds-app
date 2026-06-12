# Live Activity + Widget Extension 설정

> 🌐 **한국어** · [English](live-activity.en.md)

메인 앱의 `LiveActivity.swift` 파일은 `Attributes` 구조체만 선언합니다.
iOS는 Live Activity의 *렌더링*을 별도의 **Widget Extension** 타겟에 위치시키도록 요구합니다.

## 일회성 Xcode 설정 (xcodegen만으로는 자동화 불가)

1. **Widget Extension 타겟 추가**
   - Xcode → `File` → `New` → `Target…`
   - Widget Extension (iOS)
   - Product name: `AsItSoundsLiveActivity`
   - Bundle ID: `com.asitsounds.ios.liveactivity`
   - ✅ "Include Live Activity"
   - 프로젝트 `AsItSounds`에 추가하고 앱에 embed

2. **`TranscribeActivityAttributes` 공유**
   - `Core/LiveActivity.swift` 우클릭 → File Inspector
   - Target Membership → `AsItSoundsLiveActivity` 추가

3. **자동 생성된 위젯 교체**

```swift
import ActivityKit
import SwiftUI
import WidgetKit

@available(iOS 16.1, *)
struct TranscribeActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TranscribeActivityAttributes.self) { context in
            // Lock screen / banner
            HStack(spacing: 12) {
                Image(systemName: "waveform")
                VStack(alignment: .leading) {
                    Text(context.attributes.fileName).font(.headline)
                    Text(context.state.phase).font(.caption).foregroundStyle(.secondary)
                    ProgressView(value: context.state.progress)
                }
            }
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "waveform")
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text("\(Int(context.state.progress * 100))%")
                        .font(.caption.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ProgressView(value: context.state.progress)
                }
            } compactLeading: {
                Image(systemName: "waveform")
            } compactTrailing: {
                Text("\(Int(context.state.progress * 100))")
                    .font(.caption2.monospacedDigit())
            } minimal: {
                Image(systemName: "waveform")
            }
        }
    }
}

@main
struct AsItSoundsLiveActivityBundle: WidgetBundle {
    var body: some Widget { TranscribeActivityWidget() }
}
```

4. **`Info.plist`에서 Live Activities 활성화**

*메인 앱*의 Info.plist에 추가:

```xml
<key>NSSupportsLiveActivities</key>
<true/>
```

5. **파이프라인에서 호출**

`PipelineRunner.run(...)`으로 액티비티를 시작/업데이트/종료할 수 있습니다:

```swift
LiveActivityCoordinator.start(
    projectName: "Inbox",
    fileName: audioURL.lastPathComponent,
    totalSeconds: estimatedSeconds
)
…
await LiveActivityCoordinator.update(phase: "transcribing", progress: 0.4,
                                      processed: 12, total: 30)
…
await LiveActivityCoordinator.end()
```

## 현재 xcodegen에 extension 타겟을 포함하지 않는 이유

`project.yml`은 `type: app-extension`으로 두 번째 타겟을 선언할 수 *있지만*,
Widget Extension에는 다음이 추가로 필요합니다:

- `NSExtension` 키가 있는 별도의 `Info.plist`
- App Group 식별자가 있는 별도의 entitlements 파일
- 메인 앱의 매칭되는 App Group capability

이 항목들은 보통 Xcode에서 한 번 설정하고 다시 건드리지 않습니다. Xcode에서 직접
검증하기 전에 `project.yml`에 추가하면 다음과 같은 런타임 오류가 발생하기 쉬우며,
Xcode를 통해 클릭하면서 디버깅하는 편이 더 수월합니다:
`"Frameworks/AsItSounds.app/PlugIns/AsItSoundsLiveActivity.appex
doesn't have a valid Info.plist"`

로컬 Debug 빌드에서 수동 설정이 정상 동작하는 것을 확인한 후에만 `project.yml`에 추가하세요.
