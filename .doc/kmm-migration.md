# KMM (Kotlin Multiplatform) migration plan

Phase 3.5 goal: consolidate the *non-UI* business logic between iOS and
Android so a single Kotlin source of truth replaces today's Swift + Kotlin
duplicate. The Rust translit core stays — only Kotlin↔Swift duplicated
business code moves.

## What moves to `shared/commonMain`

| Module today | Phase 3.5 destination | Note |
| :-- | :-- | :-- |
| `Translator.kt` + `Translator.swift` | `shared/commonMain/Translator.kt` | Ktor client replaces HttpURLConnection + URLSession |
| `ModelManager.kt` + `ModelManager.swift` | `shared/commonMain/ModelManager.kt` | `expect/actual` for the OS file system |
| `Pipeline.kt` + `Pipeline.swift` | `shared/commonMain/Pipeline.kt` | Orchestration: pure Kotlin Flow |
| `AppSettings.kt` + `AppSettings.swift` | `shared/commonMain/Settings.kt` | `multiplatform-settings` library |
| `Entities.kt` + `Database.swift` | `shared/commonMain/Db.kt` | `SQLDelight` schema generates DAOs for both |
| `SubtitleExport.kt` + `SubtitleExport.swift` | `shared/commonMain/SubtitleExport.kt` | Pure Kotlin string builders |

## What stays platform-native

| Component | Reason |
| :-- | :-- |
| SwiftUI / Compose UI | Per-platform idiomatic UX is the point |
| `WhisperEngine.mm` + `ais_whisper_jni.cpp` | JNI / Obj-C++ bridges can't be Kotlin |
| `AudioDecoder.swift` (AVFoundation) / `AudioDecoder.kt` (MediaCodec) | OS-specific media APIs |
| `BackgroundTask.swift` (BGProcessingTask) / `TranscribeForegroundService.kt` | OS lifecycle APIs |
| `PlayerView.swift` (AVPlayer) / `PlayerScreen.kt` (Media3) | OS-specific media playback |
| `LiveActivity.swift` / `TranscribeQuickTile.kt` | OS-specific UI surfaces |
| `CloudSync.swift` (CloudKit) | iOS-only API |
| `AppShortcuts.swift` (App Intents) | iOS-only API |

## Target module layout

```
shared/
├── build.gradle.kts                  (Kotlin Multiplatform plugin)
├── src/
│   ├── commonMain/kotlin/com/asitsounds/shared/
│   │   ├── translator/        (Translator + 4 impls via Ktor)
│   │   ├── model/             (ModelManager + WhisperModel)
│   │   ├── pipeline/          (Pipeline orchestration + events)
│   │   ├── settings/          (multiplatform-settings wrapper)
│   │   ├── db/                (SQLDelight schema + DAOs)
│   │   └── export/            (SRT/VTT/TXT)
│   ├── androidMain/kotlin/    (FileSystem actual, SQLDelight Android driver)
│   ├── iosMain/kotlin/        (FileSystem actual, SQLDelight Native driver)
│   └── commonTest/kotlin/     (fixtures shared with desktop translit tests)
```

iOS consumes via `framework { baseName = "AisShared" }` exported as an XCFramework
during a Gradle task. Swift code imports it like any binary framework.

## Dependencies to add (Gradle)

```kotlin
commonMain.dependencies {
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("com.russhwolf:multiplatform-settings:1.1.1")
    implementation("app.cash.sqldelight:runtime:2.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
androidMain.dependencies {
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("app.cash.sqldelight:android-driver:2.0.2")
}
iosMain.dependencies {
    implementation("io.ktor:ktor-client-darwin:2.3.12")
    implementation("app.cash.sqldelight:native-driver:2.0.2")
}
```

## Migration steps (estimated)

1. **Scaffold** the `shared/` module with KMP plugin — 1 day.
2. **Translator** (lowest risk, no DB) — port + replace Swift/Kotlin sites — 2 days.
3. **Settings** + **SubtitleExport** (both pure) — 1 day.
4. **ModelManager** (needs `expect` filesystem) — 1 day.
5. **Pipeline** (needs Whisper handle passed in via `expect`) — 2 days.
6. **SQLDelight schema** + DAO migration (deletes GRDB + SQLiteOpenHelper) — 3 days.
7. **XCFramework export** + Swift call site update — 1 day.
8. **CI updates**: Gradle builds the iOS XCFramework as part of the iOS workflow — 1 day.

Total ≈ 12 working days for a solo developer, much faster if the iOS dev pairs.

## Risks

- **KSP version churn** with Kotlin 2.0.x: we hit `Wrong plugin option format: null` during Phase 2; SQLDelight uses KSP, so this needs to be re-validated before the schema port.
- **Ktor `Darwin` engine** adds ~600 KB to the IPA — measure first.
- **Compose Multiplatform** is *out of scope* for this migration; UI stays native.

## Why not do this now

We deferred KMM consolidation to Phase 3.5 because:

1. Phase 0–2 needed validated, working code on both platforms first;
   sharing untested code is just duplicating risk.
2. The translit core (which is the highest-value shared asset) is already
   shared via UniFFI — KMM only adds value for the business-logic layer.
3. KSP/Kotlin compiler turbulence is real (see [P2-2b incident](signing.md));
   stabilising the Phase-2 stack before adding KMP avoids cascading
   debug sessions.

When Phase 2 stabilises in production (≈ 2 weeks of telemetry post-launch),
flip to Phase 3.5.
