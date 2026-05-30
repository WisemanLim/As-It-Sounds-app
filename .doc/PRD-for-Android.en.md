# As It Sounds — Android App PRD

> 🌐 [한국어](PRD-for-Android.md) · **English**

> Re-implementing the core value of the desktop/web (Tauri + Rust) as Android-native. The key strategy is **reusing the Rust core 1713-line translit + phonetic rules asset as-is** (same as iOS PRD).

- **Product name (code)**: `as-it-sounds-android` (applicationId `com.asitsounds.android`)
- **Platform**: Android 9.0 (API 28) ~ Android 15 (API 35). Recommended API 33+.
- **App category**: Education / Productivity (Play Console)
- **Language**: Kotlin 2.0 · Jetpack Compose · Kotlin Coroutines / Flow
- **Core reuse**: Rust library → `.so` (cargo-ndk) → JNI / UniFFI Kotlin bindings

---

## 1. Overview

Android users can **transliterate multilingual audio/video** (English, Japanese, French, etc.) **into Korean phonetic script (Hangul) exactly as heard**, and read the **Korean translation** alongside it — all on the **local device**, with no external cloud upload. Media can be ingested from the file manager, gallery, recorder, or share intent.

### 1.1 Core Value (Android-specific vs. desktop)

1. **On-device ASR** — whisper.cpp via JNI, with NNAPI/Qualcomm Hexagon NPU acceleration when available. Works in airplane mode.
2. **Share intent integration** — One tap: "Share → As It Sounds" from YouTube/Podcast/recorder apps to transcribe immediately.
3. **Foreground Service + WorkManager** — Long files keep converting even on the lock screen, with progress in the notification.
4. **Quick Settings Tile / Widget** — Recent projects, conversion progress.
5. **ML Kit On-device Translation** — 50+ languages → Korean, offline, no API key required.

### 1.2 Non-goals (MVP)

- iOS launch (separate track, see [PRD-for-iPhone.md](./PRD-for-iPhone.md)).
- Real-time microphone streaming STT (Phase 2).
- Multi-user / cloud sync (Phase 3, Firebase candidate).
- Wear OS / Android TV / ChromeOS dedicated builds (ChromeOS is compatible automatically).

---

## 2. Target Users / Use Scenarios

| Persona | Scenario |
| :-- | :-- |
| English learner (TOEIC, OPIc) | YouTube Premium download → share → check pronunciation/translation |
| Commuter podcast listener | Google Podcasts → share → listen on the way to work and review in Korean |
| Interviewer / content creator | Record interview with recorder app → draft transcript + Korean translation |
| Japanese/French beginner | Drop conversation mp3 → read along via Korean phonetics instead of katakana/accents |

---

## 3. Technology Stack (Android Native)

### 3.1 Rationale — Why Not Tauri Mobile (same as iOS)

| Candidate | Chosen? | Reason |
| :-- | :-- | :-- |
| **Kotlin + Jetpack Compose** | ✅ Adopted | Play Store policy, Foreground Service, share intent, widgets/quick tiles, full NDK access, NNAPI acceleration |
| Tauri 2 Mobile (Android) | ❌ | Alpha stage. WebView memory/battery overhead. WorkManager/share intent support lacking. |
| Flutter | ❌ | Rust FFI possible but Kotlin + Compose is more natural for the platform. Awkward ML Kit integration. |
| React Native | ❌ | Bridge overhead, complex whisper.cpp NDK integration. |
| Capacitor / Cordova | ❌ | Not suitable. |
| Kotlin Multiplatform Mobile (KMM) | ⚠️ Under review | Potential for sharing business logic with iOS track. MVP focuses on single platform. Evaluate in Phase 3. |

### 3.2 Layer-by-Layer Stack

| Layer | Choice | Notes |
| :-- | :-- | :-- |
| UI | **Jetpack Compose 1.7+** (Material 3 Expressive) | Compose Multiplatform also an option (Phase 3) |
| State/DI | **Hilt** + **Coroutines/Flow** + Compose `ViewModel` | No external TCA-style dependency |
| Design system | **Material 3** + Dynamic Color (Android 12+) + custom tokens | Replaces Tailwind — Material You compliant |
| Navigation | **androidx.navigation.compose** + Type-safe nav (Kotlin 2.0) | Corresponds to desktop React Router |
| Persistent storage | **Room 2.6+ (SQLite, FTS5)** | Same schema as Rust-side `rusqlite`. No Realm. |
| ASR | **whisper.cpp + JNI** (submodule or prebuilt `.so`) | NDK r26+, ARM64 first |
| ASR acceleration | **OpenBLAS / OpenCL / Vulkan (whisper.cpp options)**, NNAPI experimental | Pixel 9 = Tensor G4 (TPU), Galaxy S24+ = NPU |
| Transliteration (multilingual→Hangul) | **Rust `translit.rs` as-is** → cargo-ndk `.so` → Kotlin JNI/UniFFI | **No reimplementation** (1713 lines, core IP) |
| Translation | **Abstract interface**: ML Kit Translation · MyMemory · LibreTranslate · DeepL | ML Kit = no key, on-device, 50+ languages |
| Media decode | **MediaCodec + MediaExtractor** → 16 kHz mono Float PCM | No ffmpeg needed. HEVC/HLS handled by OS |
| Media playback | **Media3 ExoPlayer 1.4+** (unified audio/video) | Native seek / loop section |
| Background processing | **WorkManager 2.9+** + **Foreground Service (mediaProcessing type, API 34+)** | Progress notification on lock screen |
| File ingestion | **Storage Access Framework (ACTION_OPEN_DOCUMENT)**, **PhotoPicker (API 33+)**, **share intent**, **drag & drop (large screen)** | Scoped Storage compliant |
| Keys/secrets | **EncryptedSharedPreferences** + **Android Keystore** | Stores API keys (DeepL/LibreTranslate) |
| Analytics | **Logcat (Timber)** + optional Firebase Crashlytics | Zero PII |
| Billing | **Google Play Billing 7.0+** | Pro model / large file unlock (optional) |

### 3.3 Rust Core ↔ Kotlin Bridge

```
┌──────────────── Android App (Kotlin) ────────────────┐
│  Compose Screens ─ ViewModels (StateFlow)             │
│        │                                              │
│        ▼  (suspend fun, Flow)                         │
│  PipelineService (singleton, Hilt)                    │
│        │                                              │
│        ├─▶  WhisperEngine.kt (JNI)                    │
│        │     └─▶ libwhisper.so + ggml                 │
│        │         (OpenCL/Vulkan/NNAPI instead of Core ML) │
│        │                                              │
│        ├─▶  AudioDecoder.kt (MediaCodec)              │
│        │                                              │
│        ├─▶  Translator (interface)                    │
│        │     ├─ MLKitTranslator (Google ML Kit)       │
│        │     ├─ MyMemoryTranslator                    │
│        │     ├─ LibreTranslateTranslator              │
│        │     └─ DeepLTranslator                       │
│        │                                              │
│        └─▶  AisCore (UniFFI Kotlin bindings)          │
│                  │                                    │
│                  ▼                                    │
│         ┌─────────────────────────────┐              │
│         │  libas_it_sounds_core.so    │              │
│         │   (arm64-v8a, armeabi-v7a,  │              │
│         │    x86_64 for emulator)     │              │
│         │   • translit.rs (1713 LOC)  │              │
│         │   • script detect           │              │
│         │   • phonetic rules engine   │              │
│         └─────────────────────────────┘              │
└───────────────────────────────────────────────────────┘
```

**Selected tool**: [UniFFI (Mozilla)](https://github.com/mozilla/uniffi-rs) — official Kotlin binding support
- `.udl` interface definition → Kotlin bindings auto-generated (same `.udl` reused as iOS Swift)
- Alternatives: `jni` crate + manual JNI (less safe, more boilerplate), `flapigen-rs`, `mozilla/glean` pattern

**Build pipeline** (cargo-ndk):
```bash
# Build 4 ABIs
cargo install cargo-ndk
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -t x86 \
  -o ./android/app/src/main/jniLibs \
  build --release
```

Per-target outputs:
- `arm64-v8a/libas_it_sounds_core.so`  ← most physical devices
- `armeabi-v7a/libas_it_sounds_core.so` ← older 32-bit devices
- `x86_64/libas_it_sounds_core.so` ← emulator (including Apple Silicon Android Studio)
- `x86/libas_it_sounds_core.so` ← largely obsolete. Can be dropped in Phase 2

CI: GitHub Actions `ubuntu-22.04` runner auto-builds with Android NDK r26d + Rust 1.82+ → produces AAR → imported as a dependency in the app module.

### 3.4 Whisper.cpp Android Integration

- JNI wrapper following the official [whisper.cpp/examples/whisper.android](https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android) pattern.
- Build options:
  - `WHISPER_OPENBLAS=1` — CPU SIMD
  - `WHISPER_OPENCL=1` — Mobile GPU (Mali, Adreno)
  - `WHISPER_VULKAN=1` — Better for newer SoCs (Tensor G3+, Snapdragon 8 Gen 2+)
  - `GGML_USE_NNAPI` — Experimental
- Memory: similar to iOS but thermal throttle is more conservative
  - `tiny.en` ≈ 130 MB peak
  - `base` ≈ 250 MB
  - `small` ≈ 800 MB
  - `medium` ≈ 1.8 GB (8 GB RAM devices only)

### 3.5 Recommended Model Matrix

| Device class | RAM | Recommended model | Notes |
| :-- | :-- | :-- | :-- |
| Budget (Galaxy A series, ~2022) | 4 GB | `tiny.en` (39 MB) / `tiny` (75 MB) | base possible but thermal concern |
| Mid-range (Pixel 7a, Galaxy A55) | 6–8 GB | `base` (142 MB) | Default |
| Flagship (Pixel 9, S24, S25) | 8–12 GB | `small` (466 MB) | Recommended — Vulkan acceleration |
| Fold/Tab/Tablet | 8–16 GB | `medium` (1.5 GB) | Optional |

Automatic device tier detection: `ActivityManager.MemoryInfo.totalMem` + `Build.SOC_MODEL` whitelist.

---

## 4. Functional Requirements

### 4.1 MVP (Phase 1, Initial Release)

| ID | Feature | Description |
| :-- | :-- | :-- |
| F-1 | Project CRUD | Bottom NavigationBar / NavigationRail (tabs) |
| F-2 | Multi-path file ingestion | (a) SAF (`ACTION_OPEN_DOCUMENT`), (b) PhotoPicker (video), (c) Share intent, (d) Drag & Drop (large screen), (e) Nearby Share |
| F-3 | Supported formats | `mp3, m4a, aac, ogg, flac, wav, mp4, mov, mkv, webm, 3gp, 3g2` (anything MediaExtractor supports). HEVC ok. |
| F-4 | Auto language detection | Whisper auto-detect; user override option also available. |
| F-5 | Pipeline execution | "Start conversion" button → decode → ASR → transliterate → translate. Progress % + ETA. |
| F-6 | 3-row segment view | EN / Hangul transliteration / KO translation. Currently playing segment highlighted. |
| F-7 | Seek/tap jump | Tap segment = `ExoPlayer.seekTo(ms)`. |
| F-8 | Media controls | Play/pause, 0.5/0.75/1/1.25/1.5/2× speed, ±5 seconds, A-B loop |
| F-9 | Local storage | Room. Includes Auto Backup (`android:allowBackup="true"`). Models/large files excluded from backup. |
| F-10 | Settings | Model selection/download, translation engine, dark/light/Dynamic Color, font size, data deletion |
| F-11 | Model manager | Download with progress via Hugging Face or **Play Asset Delivery (PAD, on-demand)**. WorkManager background download. |
| F-12 | Share intent | `<intent-filter>` for `audio/*`, `video/*` → import as new project and start conversion |
| F-13 | Result sharing/export | Per-segment copy, full text/SRT share (`ACTION_SEND`) |
| F-14 | Offline mode indicator | Clearly indicate when translation engine requires network. Guide ML Kit model download |

### 4.2 Phase 2 (Expansion)

- **SRT/VTT/TXT/Markdown export** — `ACTION_CREATE_DOCUMENT` (SAF)
- **Segment editing** → automatic transliteration/translation regeneration
- **Quick Settings Tile** — quick conversion start
- **App Widgets (Glance)** — recent projects, learning stats
- **Shortcuts API** — Assistant "Hey Google, convert today's recording with As It Sounds"
- **AirPlay alternative: Cast / Bluetooth headphones** shadow-reading (loop section)
- **Live microphone STT** — `AudioRecord` + Whisper VAD chunks

### 4.3 Phase 3

- Foldable / tab multi-window / Stage Manager (Pixel Fold, Z Fold)
- ChromeOS keyboard/mouse optimization
- Wear OS companion (quick recording → convert on phone)
- KMM to unify iOS business logic
- Learning mode (enter correct answer → score)
- Pronunciation scoring (DTW + MFCC)

### 4.4 Non-functional Requirements

| Item | Target |
| :-- | :-- |
| **Cold start** | < 1.8 seconds (Pixel 7) |
| **First segment display** | ≤ 10 seconds for a 30-second video (base, Pixel 9) |
| **RTF** | base ≤ 0.5, small ≤ 1.0 (Pixel 9, Vulkan) |
| **Memory peak** | ≤ 400 MB while base model is running |
| **Battery** | ≤ 6% drain for 10-minute conversion (Pixel 9, screen OFF) |
| **APK/AAB size** | < 60 MB (excluding models, ABI split + R8 minify) |
| **App size policy** | Models delivered via PAD on-demand or first-run download (unrelated to Play Console 200 MB Wear/Auto limit; base APK 50 MB recommended for regular apps) |
| **Accessibility** | Full support for TalkBack, font size 200%, color-blind modes |
| **Privacy** | Clear trigger for RECORD_AUDIO / READ_MEDIA_* permissions. Zero external transmissions except translation calls |

---

## 5. UI/UX (Material 3 Based)

### 5.1 Information Architecture

```
NavigationBar (phone) / NavigationRail (tablet, foldable)
├── Home          (recent projects, quick import)
├── Library       (full project list, search, filter)
├── Record        (Phase 2 — live recording/STT)
└── Settings      (model, translation, language, about)
```

Compact: `NavigationBar` 4-tab bottom. Medium/Expanded: `NavigationRail` or `NavigationDrawer`.

### 5.2 Key Screens

**Home**
```
☰ As It Sounds                              ⚙
─────────────────────────────────────────────
Today's study
  [▶ Resume: Interview 02 — 13:24]
─────────────────────────────────────────────
Quick start
  [📁 File]  [🎞 Gallery]  [🎙 Record]
─────────────────────────────────────────────
Recent projects
  • TOEIC LC 03    English · 8m · Done
  • Meeting 0524   Korean · 22m · Processing 41%
```

**Project Detail**
```
← Interview 02                       ⋮ (share/delete)
────────────────────────────────────────────
[ExoPlayer / audio waveform]
────────────────────────────────────────────
[00:03 ─ 00:05]                              ⋮
EN  As it sounds, it works.
한  애즈 잇 사운즈, 잇 웍스.
KO  들리는 대로, 동작합니다.
────────────────────────────────────────────
[00:05 ─ 00:08]
...
────────────────────────────────────────────
FAB / bottom controls
  [↺5] [▶︎] [↻5]   1× ▾   A↔B
```

**Settings**
```
Whisper model
  Current: base (142 MB)           [Change]
  Space: 21.4 GB available

Translation engine
  ML Kit Translation               [Default]
  ▸ MyMemory · LibreTranslate · DeepL · Off

Display
  Dynamic Color                    [System]
  Dark mode                        [System]
  Font size                        [System]

Data
  Storage used: 1.2 GB
  [Clear cache]  [Delete all data]

About
  Version 1.0 (1)
  Open source licenses >
```

### 5.3 Design Tokens

- **Color**: Material 3 dynamic color (Android 12+) → extracted from system wallpaper. Fallback accent `#4F8DFF`.
- **Typography**: `Roboto Flex` (system) + `JetBrains Mono` (timecodes). Material 3 type scale compliant.
- **Spacing**: 4 / 8 / 16 / 24 / 32 grid.
- **Corners**: 12 (card), 8 (button), 28 (BottomSheet).
- **Motion**: Material Motion. Fade only when `Reduce animations` is ON.

### 5.4 Accessibility Checklist

- TalkBack labels: segment = combined label of "timecode 00:03 to 00:05, English As it sounds it works, Hangul transliteration …, Korean …" (`contentDescription`).
- Switch Access — all touch targets ≥ 48dp.
- Color blindness: progress state uses color + icon + text (triple redundancy).
- Captions: this app is itself a caption tool, so dogfooding applies.
- RTL: Korean is LTR but font fallback defined for phonetic comparison.

---

## 6. Architecture

### 6.1 Modules (Gradle KTS Multi-module)

```
As-It-Sounds-android/
├── settings.gradle.kts
├── build.gradle.kts (root)
├── app/                          # Application module
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/asitsounds/android/
│       │   ├── App.kt            # @HiltAndroidApp
│       │   ├── MainActivity.kt   # Compose host
│       │   ├── ui/               # Screens, ViewModels
│       │   └── di/               # Hilt modules
│       └── AndroidManifest.xml
├── feature-home/
├── feature-library/
├── feature-project/
├── feature-settings/
├── feature-share/                # ShareTargetActivity
├── core-design/                  # Material3 theme, tokens
├── core-data/                    # Room, repositories
├── core-pipeline/                # PipelineService, Whisper, ASR orchestration
├── core-whisper/                 # JNI wrapper, model manager
├── core-translit/                # AisCore (UniFFI Kotlin)
├── core-translate/               # Translator implementations (4)
├── core-media/                   # MediaCodec decoder, ExoPlayer wrapper
└── core-common/                  # Utilities, Result types
```

### 6.2 Pipeline

```kotlin
@Singleton
class PipelineService @Inject constructor(...) {
    fun run(fileIds: List<String>, options: PipelineOptions): Flow<PipelineEvent>
}

sealed interface PipelineEvent {
    data class ModelDownloading(val progress: Float): PipelineEvent
    data class Decoding(val fileId: String, val progress: Float): PipelineEvent
    data class AsrProgress(val fileId: String, val progress: Float): PipelineEvent
    data class Segment(val seg: SegmentModel): PipelineEvent
    data class TranslatingSegment(val idx: Int): PipelineEvent
    data class FileDone(val fileId: String): PipelineEvent
    data class Failed(val fileId: String?, val error: AisError): PipelineEvent
}
```

Processing stages:

```
MediaFile (SAF / PhotoPicker / Share)
  └─▶ MediaExtractor + MediaCodec : 16 kHz mono Float32 PCM
        └─▶ WhisperEngine (Vulkan/OpenCL) : segments[{start,end,text,lang}]
              ├─▶ AisCore.transliterate(text, scriptHint) → Hangul
              └─▶ TranslateKit.translate(text, src→ko) → KO (parallel)
                    └─▶ Room.insertSegments + Flow.emit(Segment(...))
```

Threading:
- Decode: `Dispatchers.IO`
- ASR: dedicated `Executor(1)` (single model instance, no concurrency)
- Translation: `Dispatchers.IO` + `flatMapMerge(concurrency = 4)`
- Full pipeline: **Foreground Service** + `WorkManager.OneTimeWorkRequest` combined — Service handles notification, Worker handles retry/constraints (charging, Wi-Fi, etc.)

### 6.3 Data Model (Room)

```kotlin
// Identical 1:1 to desktop/iOS SQLite schema
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "media_files",
    foreignKeys = [ForeignKey(
        entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class MediaFile(
    @PrimaryKey val id: String,
    val projectId: String,
    val path: String,              // app-internal path or content://
    val displayName: String,
    val mime: String?,
    val durationMs: Long?,
    val status: FileStatus,
    val error: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(
        entity = MediaFile::class, parentColumns = ["id"], childColumns = ["fileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["fileId", "idx"])]
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    val fileId: String,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val textEn: String,
    val textHangul: String?,
    val textKo: String?,
)
```

- Migration: `AutoMigration` combined with explicit `Migration` classes.
- **FTS5**: `@Fts4` (Room officially supports up to fts4; fts5 uses raw SQL migration) — Phase 2 search.

### 6.4 Translation Engine Abstraction

```kotlin
interface Translator {
    val name: String
    val requiresNetwork: Boolean
    suspend fun translate(text: String, src: String, dst: String): String
}

class MLKitTranslator @Inject constructor(...) : Translator { ... }
class MyMemoryTranslator @Inject constructor(client: OkHttpClient) : Translator { ... }
class LibreTranslateTranslator @Inject constructor(...) : Translator { ... }
class DeepLTranslator @Inject constructor(...) : Translator { ... }
```

ML Kit Translation specifics:
- Language model downloaded on first use (≈ 30 MB / language pair, EN↔KO = once)
- Download condition `DownloadConditions.Builder().requireWifi().build()` (user setting)
- Fully offline inference

### 6.5 Model Storage

- Location: `context.filesDir/models/ggml-<name>.bin` (app-internal, excluded from backup)
- Excluded from `BackupAgent`, or excluded via `android:fullBackupContent="@xml/backup_rules"` for the models directory
- If disk is low: attempt cleanup with `StorageManager.allocateBytes()` → guide user

### 6.6 Background Processing

| Scenario | Strategy |
| :-- | :-- |
| Model download | `WorkManager(constraints: networkType=UNMETERED)` + `Foreground` or PAD on-demand |
| Long file conversion (>1 min) | **Foreground Service (`mediaProcessing` type, API 34+)** + WorkManager + checkpoint saving. Handles process death. |
| Short file | Foreground ViewModel + coroutineScope (no Service) |
| Doze mode | Foreground Service is doze-exempt. Regular Workers use user-initiated job |

**Important**: Starting Android 14 (API 34), Foreground Service requires explicit type declaration. `mediaProcessing` is the correct category. Manifest:

```xml
<service
    android:name=".pipeline.PipelineForegroundService"
    android:foregroundServiceType="mediaProcessing"
    android:exported="false" />
```

### 6.7 Error Handling

```kotlin
sealed class AisError : Throwable() {
    data class AudioDecode(val reason: String) : AisError()
    data class ModelMissing(val name: String) : AisError()
    data class ModelDownloadFailed(val cause: Throwable?) : AisError()
    data class WhisperRuntime(val code: Int, override val message: String) : AisError()
    data class TranslateFailed(val engine: String, override val cause: Throwable) : AisError()
    data class DiskFull(val neededMB: Long, val freeMB: Long) : AisError()
    object Cancelled : AisError()
}
```

Per-segment failures go to retry queue; per-file failures show a UI badge (same policy as desktop/iOS).

---

## 7. Permissions / Manifest

| Permission | Reason / API level |
| :-- | :-- |
| `RECORD_AUDIO` | Phase 2 live STT (declared in advance for MVP too) |
| `READ_MEDIA_AUDIO` (API 33+) | Music/recording files |
| `READ_MEDIA_VIDEO` (API 33+) | Gallery videos |
| `READ_MEDIA_VISUAL_USER_SELECTED` (API 34+) | PhotoPicker partial access |
| `READ_EXTERNAL_STORAGE` (API ≤ 32) | Legacy compatibility |
| `POST_NOTIFICATIONS` (API 33+) | Foreground Service progress notification |
| `FOREGROUND_SERVICE` | Background conversion |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` (API 34+) | Exact type |
| `INTERNET` | Translation API, model download |
| `ACCESS_NETWORK_STATE` | Wi-Fi download condition |
| `WAKE_LOCK` | Prevent sleep during conversion (Service handles automatically) |

`AndroidManifest.xml` essentials:
```xml
<intent-filter>
  <action android:name="android.intent.action.SEND" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:mimeType="audio/*" />
  <data android:mimeType="video/*" />
</intent-filter>

<intent-filter>
  <action android:name="android.intent.action.SEND_MULTIPLE" />
  <category android:name="android.intent.category.DEFAULT" />
  <data android:mimeType="audio/*" />
  <data android:mimeType="video/*" />
</intent-filter>
```

---

## 8. Security / Privacy

- **Network Security Config**: block cleartext, optional pinning for translation domains only.
- **Scoped Storage**: all user files received via SAF/PhotoPicker and stored as internal copies in `filesDir`. No external absolute paths.
- **Keystore + EncryptedSharedPreferences**: API key storage.
- **Logcat**: zero PII — no text logging. Only `DebugTree` from Timber in debug builds.
- **Play Data Safety form** (Play Console): answer accurately with "Data not collected" or "Crash logs (not linked, not shared with third parties)".
- **R8 / ProGuard**: minify enabled, keep only model and whisper.cpp symbols.
- **APK Signing v3+**: Play App Signing recommended.

---

## 9. Test Strategy

| Layer | Tool |
| :-- | :-- |
| Unit (Kotlin) | **JUnit 5** + **MockK** + **Turbine** (Flow verification) |
| Unit (Rust core) | Existing `cargo test` (translit accuracy regression) |
| UI | **Compose UI Test** (`composeTestRule`) |
| Integration (pipeline) | **AndroidX Test** + short .wav fixtures (reusing `.samples/*.mp3` → `.wav` conversions) |
| Screenshots | **Paparazzi (Cash App)** or **Roborazzi** — light/dark, font scale matrix |
| Performance | **Macrobenchmark** + **Baseline Profiles** — startup, first segment latency |
| Regression — transliteration accuracy | Rust workspace existing fixture (`test.sh`) — runs on every PR in CI |
| Device matrix | **Firebase Test Lab** or GitHub Actions `reactivecircus/android-emulator-runner` — API 28 / 33 / 35 |

Success criteria (same as desktop/iOS PRD):
- 10-minute English video segment accuracy ≥ 85%
- Transliteration readability ≥ 4 / 5 (beta users)
- Cold start → first segment ≤ 12 seconds (base, Pixel 9)
- 100% functional in airplane mode (with ML Kit or translation disabled)

---

## 10. Deployment / Build

### 10.1 Build System

- **Android Studio Ladybug** (2024.2) or later
- **AGP 8.7+**, **Gradle 8.10+**
- **Kotlin 2.0.20+** (K2 compiler), **JDK 17**
- **NDK r26d+** (whisper.cpp + cargo-ndk)
- **Compose Compiler Plugin** (integrated with Kotlin 2.0)

### 10.2 External Dependencies (version catalog `libs.versions.toml`)

| Dependency | Purpose | License |
| :-- | :-- | :-- |
| `androidx.compose.bom` | Compose | Apache-2 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | VM | Apache-2 |
| `androidx.navigation:navigation-compose` | Navigation | Apache-2 |
| `androidx.hilt:hilt-navigation-compose` + `com.google.dagger:hilt-android` | DI | Apache-2 |
| `androidx.room:room-runtime` + `room-ktx` + `room-compiler` | SQLite | Apache-2 |
| `androidx.work:work-runtime-ktx` | WorkManager | Apache-2 |
| `androidx.media3:media3-exoplayer` + `media3-ui-compose` | Media | Apache-2 |
| `androidx.datastore:datastore-preferences` | Settings | Apache-2 |
| `androidx.security:security-crypto` | Encrypted prefs | Apache-2 |
| `com.google.mlkit:translate` | ML Kit Translation | Google ToS |
| `com.squareup.okhttp3:okhttp` + `logging-interceptor` | HTTP | Apache-2 |
| `com.squareup.retrofit2:retrofit` + `converter-moshi` | REST | Apache-2 |
| `com.squareup.moshi:moshi-kotlin` | JSON | Apache-2 |
| `com.jakewharton.timber:timber` | Logging | Apache-2 |
| `io.coil-kt.coil3:coil-compose` | Thumbnails | Apache-2 |

Testing:
| | |
| :-- | :-- |
| `org.junit.jupiter:junit-jupiter` | JUnit 5 |
| `io.mockk:mockk` | Mock |
| `app.cash.turbine:turbine` | Flow testing |
| `app.cash.paparazzi:paparazzi` or `io.github.takahirom.roborazzi:roborazzi` | Screenshots |
| `androidx.benchmark:benchmark-macro-junit4` | Performance |

Whisper:
- `whisper.cpp` included as a **git submodule**, `CMakeLists.txt` builds per ABI (`externalNativeBuild`)
- Or place a prebuilt AAR in `libs/` and import as a dependency (CI artifact)

### 10.3 CI/CD

- GitHub Actions `ubuntu-22.04` runner + optional `macos-14` (Apple Silicon emulator acceleration)
- Steps:
  1. Rust → cargo-ndk 4 ABI build → `app/src/main/jniLibs/<abi>/`
  2. whisper.cpp CMake build (CI cache)
  3. `./gradlew :app:assembleRelease` (R8, ABI split)
  4. `./gradlew :app:bundleRelease` → AAB
  5. `./gradlew :app:connectedAndroidTest` (Firebase Test Lab optional)
  6. Play Console upload (`gradle-play-publisher` plugin)
- Secrets: `PLAY_STORE_SERVICE_ACCOUNT_JSON`, `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`
- Code signing: **Play App Signing** (stored by Google) + upload key only on local/CI

### 10.4 Play Store Submission Checklist

- 8-size screenshots (phone, 7" and 10" tablets)
- 30-second promo video (optional)
- **Data Safety** questionnaire (see §8 above)
- Content rating (IARC questionnaire) — Education / Productivity = Everyone
- Target API: API 35 (Android 15) — required by Play policy after August 2025
- 64-bit only ABI recommended
- Category: Education (sub Productivity)
- Pricing: Free + IAP (Pro model unlock) or Free
- **Play Asset Delivery (PAD)**: large model on-demand pack

---

## 11. Roadmap (Proposal)

| Phase | Duration (business days) | Deliverable |
| :-- | :-- | :-- |
| **0. Initial setup** | 5d | Android Studio project, multi-module, CI skeleton, Rust cargo-ndk build verification |
| **1. Whisper JNI integration** | 8d | Single short .wav → segment display (UI-agnostic demo) |
| **2. Rust transliteration bridge** | 4d | UniFFI Kotlin bindings, fixture match with desktop/iOS confirmed |
| **3. UI shell (Home/Library/Project)** | 9d | End-to-end navigation with mock data, Material 3 Dynamic Color |
| **4. File ingestion 4 paths** | 6d | SAF / PhotoPicker / share intent / drag-drop |
| **5. Pipeline + Foreground Service** | 7d | Real audio → result display, notification progress |
| **6. 4 translation engines** | 4d | ML Kit / MyMemory / Libre / DeepL toggle |
| **7. Model manager + WorkManager** | 5d | Download progress, PAD option |
| **8. Settings / polish / accessibility** | 5d | Settings, TalkBack, dynamic font |
| **9. Beta (Play Console Internal/Closed)** | 4d | External beta with 100 users |
| **10. Play Store submission** | 3d | Screenshots, Data Safety, review response |

Total **approximately 60 business days** (≈ 12 weeks, solo dev). 2-person team ≈ 8 weeks.

---

## 12. Risks / Mitigation

| Risk | Impact | Mitigation |
| :-- | :-- | :-- |
| Whisper model memory (low-end devices) | OOM crash | Automatic guard based on device RAM, model fallback per ABI |
| Fragmentation (many SoCs) | Acceleration only on some devices | NNAPI is experimental so fallback chain (Vulkan → OpenCL → CPU) |
| Rust cargo-ndk build complexity | Barrier for new developers | Distribute CI artifact as AAR, local build optional |
| Foreground Service policy changes | Additional constraints in Android 15+ | Explicitly declare `mediaProcessing` type, list all declared types |
| Play review: automatic external model download | Possible rejection | Explicit consent on first run + recommend Wi-Fi. Or use PAD on-demand with Google infrastructure |
| ML Kit model download failure (China, etc.) | Translation unavailable | Auto-fallback (ML Kit → MyMemory → off) |
| Background processing restrictions (Doze, App Standby) | Long conversion delayed | Foreground Service is doze-exempt. Use user-initiated job |
| Some codecs (HEVC, DTS) unsupported on certain devices | Conversion failure | MediaCodec query → guide user when fallback conversion is needed |
| Transliteration quality (multilingual) | Degraded learning effectiveness | Desktop/iOS regression fixtures reused as-is → equivalence guaranteed |
| AAB size (whisper.cpp + abi splits) | Exceeds base APK 50 MB | ABI per-APK download (automatic by Play), models via PAD |
| Room ↔ Rust SQLite concurrency | Data corruption | Rust core **does not use SQLite**, DB is 100% Kotlin |

---

## 13. Code / IP Reuse Matrix

| Asset | Location | Android reuse method |
| :-- | :-- | :-- |
| `translit.rs` (1713 LOC, multilingual phonetic rules) | `src-tauri/src/core/translit.rs` | **Compiled as-is**, cargo-ndk → `.so` → exposed via UniFFI Kotlin |
| `models.rs`, `error.rs` | Same | Partial type mirroring (Kotlin sealed class) |
| `asr.rs` (whisper-rs wrapper) | Rust | Not reused — Kotlin JNI calls whisper.cpp directly |
| `media.rs` (ffmpeg sidecar) | Rust | Not reused — MediaCodec used instead |
| `translate.rs` | Rust | Reference only — rewritten as Kotlin `TranslateKit` (ML Kit added) |
| `db.rs` (rusqlite) | Rust | Reference only — rewritten as Room. **Schema kept 1:1** |
| `pipeline.rs` | Rust | Re-implemented as Kotlin `PipelineService` + Foreground Service |
| Frontend React | `src/` | Zero reuse — new Compose UI |
| `.samples/*.mp3` fixtures | repo root | **Used directly as regression test input** |
| iOS UniFFI `.udl` | iOS PRD | **Reusable** — same `.udl` generates both Swift and Kotlin bindings |

**Conclusion**: Same strategy as iOS. Only `translit.rs` is reused as-is; everything else is Android-native. Consistency ensured by sharing the `.udl` interface with iOS.

---

## 14. Success Metrics (KPIs)

- **Day-30 retention** ≥ 22% (accounting for Android average being lower than iOS)
- **First conversion completion rate** ≥ 65%
- **Rating** Play Store ≥ 4.4
- **Crash-free sessions** (Crashlytics) ≥ 99.5%
- **ANR rate** ≤ 0.5%
- **Segment accuracy** ≥ 85% (English), ≥ 78% (Japanese, French)
- **Beta NPS** ≥ 35

---

## 15. Decision Summary (Architectural Decisions)

| ADR | Decision |
| :-- | :-- |
| ADR-001 | No Tauri Mobile. Native Kotlin + Compose. |
| ADR-002 | Reuse only Rust `translit.rs`, UniFFI Kotlin bindings. Share iOS `.udl`. |
| ADR-003 | ASR is whisper.cpp + JNI (MVP). Acceleration priority: Vulkan > OpenCL > CPU. |
| ADR-004 | Media decode uses MediaCodec. No ffmpeg. |
| ADR-005 | DB is Room. Rust core does not access DB. |
| ADR-006 | Default translation = ML Kit (on-device), fallback MyMemory. |
| ADR-007 | Models downloaded on first run or PAD on-demand. Not bundled in APK. |
| ADR-008 | Min SDK = API 28 (Android 9.0). Target SDK = 35. |
| ADR-009 | State management with Hilt + Coroutines/Flow + Compose. |
| ADR-010 | Design is Material 3 + Dynamic Color. Minimize custom styling. |
| ADR-011 | Multi-module (feature + core). KMM in Phase 3. |
| ADR-012 | Foreground Service type = `mediaProcessing` (API 34+). |

---

## 16. Comparison with iOS PRD (Symmetry / Asymmetry)

| Area | iOS | Android |
| :-- | :-- | :-- |
| Language | Swift 5.10 | Kotlin 2.0 |
| UI | SwiftUI | Jetpack Compose |
| DI | manual / actor | Hilt |
| Async | Swift Concurrency | Coroutines/Flow |
| DB | GRDB.swift | Room |
| Media | AVFoundation | MediaCodec + Media3 |
| ASR acceleration | Core ML encoder | Vulkan/OpenCL (+ experimental NNAPI) |
| Default translation | Apple Translation | ML Kit Translation |
| Background | BGProcessingTask | Foreground Service + WorkManager |
| Sharing | Share Extension | Intent filter |
| Model delivery | First-run download | First-run download or PAD |
| Code signing | fastlane match | Play App Signing |
| **Common** | **Rust `translit.rs` + UniFFI same `.udl`** | Same |
| **Common** | **DB schema 1:1** | Same |
| **Common** | **Abstract translation engine interface** | Same |

→ Business logic ≈ 80% abstract match; only UI/platform APIs diverge. When KMM is introduced in Phase 3, `core-pipeline`, `core-translate`, and `core-data` can be unified.

---

## 17. Appendix — References

- whisper.cpp Android guide: <https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android>
- whisper.cpp Vulkan build: <https://github.com/ggerganov/whisper.cpp/blob/master/docs/build.md>
- UniFFI Kotlin guide: <https://mozilla.github.io/uniffi-rs/kotlin/>
- cargo-ndk: <https://github.com/bbqsrc/cargo-ndk>
- Room: <https://developer.android.com/training/data-storage/room>
- ML Kit Translation: <https://developers.google.com/ml-kit/language/translation/android>
- Media3 ExoPlayer: <https://developer.android.com/media/media3/exoplayer>
- WorkManager: <https://developer.android.com/topic/libraries/architecture/workmanager>
- Foreground Service types (API 34): <https://developer.android.com/about/versions/14/changes/fgs-types-required>
- Play Asset Delivery: <https://developer.android.com/guide/playcore/asset-delivery>
- Material 3: <https://m3.material.io/>
- iOS companion document: [PRD-for-iPhone.md](./PRD-for-iPhone.md)
- Desktop PRD: `../../NGL(Go,Rust,...)/As-It-Sounds/.doc/PRD.md`
- Desktop README: `../../NGL(Go,Rust,...)/As-It-Sounds/README.md`
- Web deployment README: `../../NGL(Go,Rust,...)/As-It-Sounds/README-Web.md`
