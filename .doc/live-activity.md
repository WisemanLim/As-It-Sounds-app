# Live Activity + Widget Extension setup

The `LiveActivity.swift` file in the main app declares the `Attributes`
struct only. iOS requires the *rendering* of a Live Activity to live in a
separate **Widget Extension** target.

## One-time Xcode setup (cannot be automated via xcodegen alone)

1. **Add Widget Extension target**
   - Xcode → `File` → `New` → `Target…`
   - Widget Extension (iOS)
   - Product name: `AsItSoundsLiveActivity`
   - Bundle ID: `com.asitsounds.ios.liveactivity`
   - ✅ "Include Live Activity"
   - Add to project `AsItSounds`, embed in app

2. **Share `TranscribeActivityAttributes`**
   - Right-click `Core/LiveActivity.swift` → File Inspector
   - Target Membership → add `AsItSoundsLiveActivity`

3. **Replace the auto-generated widget**

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

4. **Enable Live Activities in `Info.plist`**

Add to the *main app* Info.plist:

```xml
<key>NSSupportsLiveActivities</key>
<true/>
```

5. **Call from the pipeline**

`PipelineRunner.run(...)` can start/update/end the activity:

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

## Why not include the extension target in xcodegen now

`project.yml` *can* declare a second target with `type: app-extension`, but
the Widget Extension also needs:

- A separate `Info.plist` with `NSExtension` keys
- A separate entitlements file with the App Group identifier
- The matching App Group capability on the main app

These are usually wired in Xcode once and never touched again. Adding them
to `project.yml` before they're verified by hand in Xcode tends to produce
"Frameworks/AsItSounds.app/PlugIns/AsItSoundsLiveActivity.appex
doesn't have a valid Info.plist" runtime errors that are easier to debug
by clicking through Xcode.

Add to `project.yml` only after the manual setup is proven to work in a
local Debug build.
