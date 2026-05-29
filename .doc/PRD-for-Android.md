# As It Sounds — Android App PRD

> 데스크탑/웹 (Tauri + Rust) 의 핵심 가치를 Android-네이티브로 재구현. **Rust 코어 1713줄 translit + 음운 규칙 자산을 그대로 재사용**하는 게 핵심 전략 (iOS PRD 와 동일).

- **제품명(코드)**: `as-it-sounds-android` (applicationId `com.asitsounds.android`)
- **플랫폼**: Android 9.0 (API 28) ~ Android 15 (API 35). 권장 API 33+.
- **앱 분류**: Education / Productivity (Play Console)
- **언어**: Kotlin 2.0 · Jetpack Compose · Kotlin Coroutines / Flow
- **코어 재사용**: Rust 라이브러리 → `.so` (cargo-ndk) → JNI / UniFFI Kotlin 바인딩

---

## 1. 개요

Android 사용자가 외부 클라우드 업로드 없이 **로컬 디바이스에서** 영어·일본어·프랑스어 등 다국어 음성/영상을 **들리는 발음 그대로 한글로 음차** 하고 **한국어 번역** 까지 함께 본다. 파일 매니저·갤러리·녹음·공유 인텐트에서 모든 미디어 입수 가능.

### 1.1 핵심 가치 (데스크탑 대비 Android 특화)

1. **온디바이스 ASR** — whisper.cpp via JNI, 가능 시 NNAPI/Qualcomm Hexagon NPU 가속. 비행기 모드 동작.
2. **공유 인텐트 통합** — YouTube/Podcast/녹음앱에서 "공유 → As It Sounds" 한 번에 전사.
3. **Foreground Service + WorkManager** — 긴 파일 잠금화면에서도 계속 변환, 알림에 진행률.
4. **퀵 설정 타일 / 위젯** — 최근 프로젝트, 변환 진행률.
5. **ML Kit On-device Translation** — 키 없이 50+ 언어 → 한국어 (오프라인).

### 1.2 비목표(Non-goals, MVP)

- iOS 출시 (별도 트랙, [PRD-for-iPhone.md](./PRD-for-iPhone.md) 참조).
- 실시간 마이크 스트리밍 STT (Phase 2).
- 다중 사용자/클라우드 동기화 (Phase 3, Firebase 후보).
- Wear OS / Android TV / ChromeOS 전용 빌드 (단, ChromeOS 는 자동 호환).

---

## 2. 타겟 사용자 / 사용 시나리오

| 페르소나 | 시나리오 |
| :-- | :-- |
| 영어 학습자 (TOEIC, OPIc) | YouTube Premium 다운로드 → 공유 → 발음/번역 확인 |
| 통근중 팟캐스트 청취자 | Google Podcasts → 공유 → 출근길에 듣고 다시 한글로 검수 |
| 인터뷰어/콘텐츠 제작자 | 녹음기 앱으로 인터뷰 → 초안 transcript + 한국어 번역 |
| 일본어/프랑스어 입문자 | 회화 mp3 드롭 → 카타카나·악센트 대신 한글 음차로 따라 읽기 |

---

## 3. 기술 스택 (Android 네이티브)

### 3.1 선정 사유 — Tauri Mobile 안 쓰는 이유 (iOS 와 동일)

| 후보 | 선택? | 이유 |
| :-- | :-- | :-- |
| **Kotlin + Jetpack Compose** | ✅ 채택 | Play Store 정책, Foreground Service, 공유 인텐트, 위젯/퀵타일, NDK 풀 액세스, NNAPI 가속 가능 |
| Tauri 2 Mobile (Android) | ❌ | alpha 단계. WebView 메모리·배터리 오버헤드. WorkManager/공유 인텐트 미흡. |
| Flutter | ❌ | Rust FFI 가능하지만 Kotlin + Compose 가 플랫폼 자연스러움. ML Kit 통합 어색. |
| React Native | ❌ | Bridge 오버헤드, whisper.cpp NDK 통합 복잡. |
| Capacitor / Cordova | ❌ | 동상이몽. |
| Kotlin Multiplatform Mobile (KMM) | ⚠️ 보조 검토 | iOS 트랙과 비즈니스 로직 공유 가능성. MVP 는 단일 플랫폼 집중. Phase 3 평가. |

### 3.2 레이어별 스택

| 계층 | 선택 | 비고 |
| :-- | :-- | :-- |
| UI | **Jetpack Compose 1.7+** (Material 3 Expressive) | Compose Multiplatform 도 옵션 (Phase 3) |
| 상태/DI | **Hilt** + **Coroutines/Flow** + Compose `ViewModel` | TCA 류 외부 의존 없음 |
| 디자인 시스템 | **Material 3** + Dynamic Color (Android 12+) + 자체 토큰 | Tailwind 대체 — Material You 준수 |
| 네비게이션 | **androidx.navigation.compose** + Type-safe nav (Kotlin 2.0) | 데스크탑 React Router 대응 |
| 영구 저장 | **Room 2.6+ (SQLite, FTS5)** | Rust 측 `rusqlite` 와 같은 스키마. Realm 안 씀 |
| ASR | **whisper.cpp + JNI** (sub-module 또는 prebuilt `.so`) | NDK r26+, ARM64 우선 |
| ASR 가속 | **OpenBLAS / OpenCL / Vulkan (whisper.cpp 옵션)**, NNAPI experimental | Pixel 9 = Tensor G4 (TPU), Galaxy S24+ = NPU |
| 음차 (다국어→한글) | **Rust `translit.rs` 그대로** → cargo-ndk `.so` → Kotlin JNI/UniFFI | **재구현 금지** (1713줄, 핵심 IP) |
| 번역 | **추상 인터페이스**: ML Kit Translation · MyMemory · LibreTranslate · DeepL | ML Kit = 키 0, 온디바이스, 50+ 언어 |
| 미디어 디코드 | **MediaCodec + MediaExtractor** → 16 kHz mono Float PCM | ffmpeg 불필요. HEVC/HLS OS 처리 |
| 미디어 재생 | **Media3 ExoPlayer 1.4+** (영상/음성 통합) | seek/구간 반복 native |
| 백그라운드 처리 | **WorkManager 2.9+** + **Foreground Service (mediaProcessing type, API 34+)** | 잠금화면 진행률 알림 |
| 파일 입수 | **Storage Access Framework (ACTION_OPEN_DOCUMENT)**, **PhotoPicker (API 33+)**, **공유 인텐트**, **드래그앤드롭 (large screen)** | Scoped Storage 준수 |
| 키/시크릿 | **EncryptedSharedPreferences** + **Android Keystore** | API 키 (DeepL/LibreTranslate) 저장 |
| 분석 | **Logcat (Timber)** + 옵션 Firebase Crashlytics | PII 0 |
| 결제 | **Google Play Billing 7.0+** | Pro 모델/큰 파일 잠금 해제 (선택) |

### 3.3 Rust 코어 ↔ Kotlin 브릿지

```
┌──────────────── Android App (Kotlin) ────────────────┐
│  Compose Screens ─ ViewModels (StateFlow)             │
│        │                                              │
│        ▼  (suspend fun, Flow)                         │
│  PipelineService (singleton, Hilt)                    │
│        │                                              │
│        ├─▶  WhisperEngine.kt (JNI)                    │
│        │     └─▶ libwhisper.so + ggml                 │
│        │         (Core ML 대신 OpenCL/Vulkan/NNAPI)   │
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

**선정 도구**: [UniFFI (Mozilla)](https://github.com/mozilla/uniffi-rs) — Kotlin 바인딩 공식 지원
- `.udl` 인터페이스 정의 → Kotlin 바인딩 자동 생성 (iOS Swift 와 동일 `.udl` 재사용)
- 대안: `jni` crate + 수동 JNI (덜 안전, 보일러플레이트 多), `flapigen-rs`, `mozilla/glean` 패턴

**빌드 파이프라인** (cargo-ndk):
```bash
# 4 ABI 빌드
cargo install cargo-ndk
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -t x86 \
  -o ./android/app/src/main/jniLibs \
  build --release
```

타겟별 산출물:
- `arm64-v8a/libas_it_sounds_core.so`  ← 실기 대부분
- `armeabi-v7a/libas_it_sounds_core.so` ← 구형 32-bit
- `x86_64/libas_it_sounds_core.so` ← 에뮬레이터 (Apple Silicon Android Studio 포함)
- `x86/libas_it_sounds_core.so` ← 거의 사장. Phase 2 제거 가능

CI: GitHub Actions `ubuntu-22.04` 러너에서 Android NDK r26d + Rust 1.82+ 로 자동 빌드 → AAR 산출 → 앱 모듈에서 의존성으로 임포트.

### 3.4 Whisper.cpp Android 통합

- 공식 [whisper.cpp/examples/whisper.android](https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android) 패턴 따라 JNI 래퍼.
- 빌드 옵션:
  - `WHISPER_OPENBLAS=1` — CPU SIMD
  - `WHISPER_OPENCL=1` — 모바일 GPU (Mali, Adreno)
  - `WHISPER_VULKAN=1` — 최신 SoC 더 유리 (Tensor G3+, Snapdragon 8 Gen 2+)
  - `GGML_USE_NNAPI` — 실험적
- 메모리: iOS 와 유사하나 thermal throttle 보수적
  - `tiny.en` ≈ 130 MB peak
  - `base` ≈ 250 MB
  - `small` ≈ 800 MB
  - `medium` ≈ 1.8 GB (8 GB RAM 디바이스만)

### 3.5 모델 권장 매트릭스

| 기기 클래스 | RAM | 권장 모델 | 비고 |
| :-- | :-- | :-- | :-- |
| 보급형 (Galaxy A 시리즈, ~2022) | 4 GB | `tiny.en` (39 MB) / `tiny` (75 MB) | base 도 가능하나 thermal |
| 미드레인지 (Pixel 7a, Galaxy A55) | 6–8 GB | `base` (142 MB) | 기본값 |
| 플래그십 (Pixel 9, S24, S25) | 8–12 GB | `small` (466 MB) | 권장 — Vulkan 가속 |
| 폴드/탭/태블릿 | 8–16 GB | `medium` (1.5 GB) | 옵션 |

자동 디바이스 등급 판정: `ActivityManager.MemoryInfo.totalMem` + `Build.SOC_MODEL` 화이트리스트.

---

## 4. 기능 요구사항

### 4.1 MVP (Phase 1, 첫 출시)

| ID | 기능 | 설명 |
| :-- | :-- | :-- |
| F-1 | 프로젝트 CRUD | 하단 NavigationBar / NavigationRail (탭) |
| F-2 | 파일 입수 다중 경로 | (a) SAF (`ACTION_OPEN_DOCUMENT`), (b) PhotoPicker (영상), (c) 공유 인텐트, (d) Drag & Drop (large screen), (e) Nearby Share |
| F-3 | 지원 포맷 | `mp3, m4a, aac, ogg, flac, wav, mp4, mov, mkv, webm, 3gp, 3g2` (MediaExtractor 가능한 것). HEVC ok. |
| F-4 | 자동 언어 감지 | Whisper auto-detect; 사용자 강제 지정 옵션도. |
| F-5 | 파이프라인 실행 | "변환 시작" 버튼 → 디코드 → ASR → 음차 → 번역. 진행률 % + ETA. |
| F-6 | 3단 세그먼트 뷰 | EN / 한글 음차 / KO 번역. 현재 재생 세그먼트 하이라이트. |
| F-7 | 시크/탭 점프 | 세그먼트 탭 = `ExoPlayer.seekTo(ms)`. |
| F-8 | 미디어 컨트롤 | 재생/일시정지, 0.5/0.75/1/1.25/1.5/2× 속도, 5초 ±, A-B 구간 반복 |
| F-9 | 로컬 저장 | Room. Auto Backup 포함 (`android:allowBackup="true"`). 모델/큰 파일은 백업 제외. |
| F-10 | 설정 | 모델 선택/다운로드, 번역 엔진, 다크/라이트/Dynamic Color, 글자 크기, 데이터 삭제 |
| F-11 | 모델 매니저 | Hugging Face 또는 **Play Asset Delivery (PAD, on-demand)** 로 progress 표시. WorkManager 백그라운드 다운로드. |
| F-12 | 공유 인텐트 | `<intent-filter>` 로 `audio/*`, `video/*` → 새 프로젝트 임포트 후 변환 시작 |
| F-13 | 결과 공유/내보내기 | 세그먼트 단위 복사, 전체 텍스트/SRT 공유 (`ACTION_SEND`) |
| F-14 | 오프라인 모드 인디케이터 | 번역 엔진이 네트워크 필요 시 명시. ML Kit 모델 다운로드 안내 |

### 4.2 Phase 2 (확장)

- **SRT/VTT/TXT/Markdown 내보내기** — `ACTION_CREATE_DOCUMENT` (SAF)
- **세그먼트 편집** → 음차/번역 자동 재생성
- **Quick Settings Tile** — 빠른 변환 시작
- **App Widgets (Glance)** — 최근 프로젝트, 학습 통계
- **Shortcuts API** — Assistant "헤이 구글, As It Sounds 로 오늘 녹음 변환해"
- **AirPlay 대안: Cast / Bluetooth 헤드폰** 따라 말하기 (구간 반복)
- **마이크 라이브 STT** — `AudioRecord` + Whisper VAD 청크

### 4.3 Phase 3

- 폴더블 / 탭 멀티윈도우 / Stage Manager (Pixel Fold, Z Fold)
- ChromeOS 키보드/마우스 최적화
- Wear OS 컴패니언 (간단 녹음 → 폰에서 변환)
- KMM 으로 iOS 비즈니스 로직 통합
- 학습 모드 (정답 입력 → 채점)
- 발음 점수 (DTW + MFCC)

### 4.4 비기능 요구사항

| 항목 | 목표 |
| :-- | :-- |
| **콜드 스타트** | < 1.8 초 (Pixel 7) |
| **첫 세그먼트 표시** | 30 초 영상에서 ≤ 10 초 (base, Pixel 9) |
| **RTF** | base ≤ 0.5, small ≤ 1.0 (Pixel 9, Vulkan) |
| **메모리 피크** | base 모델 동작 중 ≤ 400 MB |
| **배터리** | 10 분 변환 ≤ 6 % 소모 (Pixel 9, 화면 OFF) |
| **APK/AAB 크기** | < 60 MB (모델 제외, ABI split + R8 minify) |
| **앱 크기 정책** | 모델은 PAD on-demand 또는 첫 실행 다운로드 (Play Console 200 MB Wear/Auto 한도 무관, 일반 앱은 base APK 50 MB 권장) |
| **접근성** | TalkBack, 글자 크기 200%, 색약 모드 모두 대응 |
| **프라이버시** | RECORD_AUDIO / READ_MEDIA_* 권한 명확한 트리거. 외부 전송은 번역 호출 외 0 |

---

## 5. UI/UX (Material 3 기반)

### 5.1 정보 구조

```
NavigationBar (phone) / NavigationRail (tablet, foldable)
├── Home          (최근 프로젝트, 빠른 임포트)
├── Library       (전체 프로젝트 리스트, 검색, 필터)
├── Record        (Phase 2 — 라이브 녹음/STT)
└── Settings      (모델, 번역, 언어, 정보)
```

Compact: `NavigationBar` 4탭 하단. Medium/Expanded: `NavigationRail` 또는 `NavigationDrawer`.

### 5.2 주요 화면

**Home**
```
☰ As It Sounds                              ⚙
─────────────────────────────────────────────
오늘의 학습
  [▶ 이어보기: 인터뷰 02 — 13:24]
─────────────────────────────────────────────
빠른 시작
  [📁 파일]  [🎞 갤러리]  [🎙 녹음]
─────────────────────────────────────────────
최근 프로젝트
  • TOEIC LC 03    영어 · 8m · 변환 완료
  • 회의 0524      한국어 · 22m · 처리 중 41%
```

**Project Detail**
```
← 인터뷰 02                          ⋮ (공유/삭제)
────────────────────────────────────────────
[ExoPlayer / 오디오 파형]
────────────────────────────────────────────
[00:03 ─ 00:05]                              ⋮
EN  As it sounds, it works.
한  애즈 잇 사운즈, 잇 웍스.
KO  들리는 대로, 동작합니다.
────────────────────────────────────────────
[00:05 ─ 00:08]
...
────────────────────────────────────────────
FAB / 하단 컨트롤
  [↺5] [▶︎] [↻5]   1× ▾   A↔B
```

**Settings**
```
Whisper 모델
  현재: base (142 MB)              [변경]
  공간: 21.4 GB 사용 가능

번역 엔진
  ML Kit Translation               [기본]
  ▸ MyMemory · LibreTranslate · DeepL · 끄기

표시
  Dynamic Color                    [시스템]
  다크 모드                        [시스템]
  글자 크기                        [시스템]

데이터
  저장 사용량 1.2 GB
  [캐시 비우기]  [모든 데이터 삭제]

정보
  버전 1.0 (1)
  오픈소스 라이선스 >
```

### 5.3 디자인 토큰

- **컬러**: Material 3 dynamic color (Android 12+) → 시스템 wallpaper 추출. fallback 액센트 `#4F8DFF`.
- **타이포**: `Roboto Flex` (system) + `JetBrains Mono` (타임코드). Material 3 type scale 준수.
- **간격**: 4 / 8 / 16 / 24 / 32 grid.
- **모서리**: 12 (카드), 8 (버튼), 28 (BottomSheet).
- **모션**: Material Motion. `Reduce animations` 설정 ON 시 fade only.

### 5.4 접근성 체크리스트

- TalkBack 라벨: 세그먼트 = "타임코드 00:03 부터 00:05, 영어 As it sounds it works, 한글 음차 …, 한국어 …" 결합 라벨 (`contentDescription`).
- Switch Access — 모든 터치 타깃 ≥ 48dp.
- 색맹: 진행 상태는 색 + 아이콘 + 텍스트 3중.
- 자막: 본인이 자막 도구이므로 dogfooding.
- RTL: 한국어가 LTR 이지만 음차 비교용 폰트 fallback 정의.

---

## 6. 아키텍처

### 6.1 모듈 (Gradle KTS 멀티모듈)

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

### 6.2 파이프라인

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

처리 단계:

```
MediaFile (SAF / PhotoPicker / Share)
  └─▶ MediaExtractor + MediaCodec : 16 kHz mono Float32 PCM
        └─▶ WhisperEngine (Vulkan/OpenCL) : segments[{start,end,text,lang}]
              ├─▶ AisCore.transliterate(text, scriptHint) → 한글
              └─▶ TranslateKit.translate(text, src→ko) → KO (병렬)
                    └─▶ Room.insertSegments + Flow.emit(Segment(...))
```

스레딩:
- 디코드: `Dispatchers.IO`
- ASR: 전용 `Executor(1)` (모델 1 인스턴스, 동시성 X)
- 번역: `Dispatchers.IO` + `flatMapMerge(concurrency = 4)`
- 전체 파이프라인: **Foreground Service** + `WorkManager.OneTimeWorkRequest` 결합 — Service 가 알림, Worker 가 재시도/제약 (충전중·Wi-Fi 등)

### 6.3 데이터 모델 (Room)

```kotlin
// 데스크탑/iOS SQLite 스키마와 1:1 동일
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

- 마이그레이션: `AutoMigration` + 명시 `Migration` 클래스 병행.
- **FTS5**: `@Fts4` (Room 은 fts4 까지 공식, fts5 는 raw SQL 마이그레이션) — Phase 2 검색.

### 6.4 번역 엔진 추상

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

ML Kit Translation 특이사항:
- 첫 사용 시 언어 모델 다운로드 (≈ 30 MB / 언어 쌍, 영↔한 = 한 번)
- 다운로드 조건 `DownloadConditions.Builder().requireWifi().build()` (사용자 설정)
- 완전 오프라인 추론

### 6.5 모델 저장

- 위치: `context.filesDir/models/ggml-<name>.bin` (앱 내부, 백업 제외)
- `BackupAgent` 에서 제외, 또는 `android:fullBackupContent="@xml/backup_rules"` 로 모델 디렉터리 exclude
- 디스크 부족 시: `StorageManager.allocateBytes()` 로 정리 시도 → 안내

### 6.6 백그라운드 처리

| 시나리오 | 전략 |
| :-- | :-- |
| 모델 다운로드 | `WorkManager(constraints: networkType=UNMETERED)` + `Foreground` 또는 PAD on-demand |
| 긴 파일 변환 (>1분) | **Foreground Service (`mediaProcessing` type, API 34+)** + WorkManager + checkpoint 저장. Process death 대응. |
| 짧은 파일 | 포그라운드 ViewModel + coroutineScope (Service 없이) |
| 도즈 모드 | Foreground Service 면 doze 면제. 일반 Worker 는 user-initiated job 사용 |

**중요**: Android 14 (API 34) 부터 Foreground Service 는 type 선언 필수. `mediaProcessing` 이 정확한 카테고리. Manifest:

```xml
<service
    android:name=".pipeline.PipelineForegroundService"
    android:foregroundServiceType="mediaProcessing"
    android:exported="false" />
```

### 6.7 에러 처리

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

세그먼트 단위 실패는 재시도 큐, 파일 단위 실패는 UI 배지 (데스크탑/iOS 와 동일 정책).

---

## 7. 권한 / Manifest

| 권한 | 사유 / API 레벨 |
| :-- | :-- |
| `RECORD_AUDIO` | Phase 2 라이브 STT (MVP 도 위해 미리 선언) |
| `READ_MEDIA_AUDIO` (API 33+) | 음악/녹음 파일 |
| `READ_MEDIA_VIDEO` (API 33+) | 갤러리 영상 |
| `READ_MEDIA_VISUAL_USER_SELECTED` (API 34+) | PhotoPicker 부분 액세스 |
| `READ_EXTERNAL_STORAGE` (API ≤ 32) | 구형 호환 |
| `POST_NOTIFICATIONS` (API 33+) | Foreground Service 진행률 알림 |
| `FOREGROUND_SERVICE` | 백그라운드 변환 |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` (API 34+) | 정확한 type |
| `INTERNET` | 번역 API, 모델 다운로드 |
| `ACCESS_NETWORK_STATE` | Wi-Fi 다운로드 조건 |
| `WAKE_LOCK` | 변환 중 sleep 방지 (Service 가 자동 처리) |

`AndroidManifest.xml` 핵심:
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

## 8. 보안 / 프라이버시

- **Network Security Config**: cleartext 차단, 번역 도메인만 핀닝 (옵션).
- **Scoped Storage**: 모든 사용자 파일은 SAF/PhotoPicker 로 받고 `filesDir` 내부 사본으로 저장. 외부 절대 경로 X.
- **Keystore + EncryptedSharedPreferences**: API 키 보관.
- **Logcat**: PII 없음 — 텍스트 로깅 금지. Timber 의 `DebugTree` 만 debug build.
- **Play Data Safety form** (Play Console): "Data not collected" 또는 "Crash logs (not linked, not shared with third parties)" 정확히 답변.
- **R8 / ProGuard**: minify enable, 모델·whisper.cpp 심볼만 keep.
- **APK Signing v3+**: Play App Signing 권장.

---

## 9. 테스트 전략

| 레이어 | 도구 |
| :-- | :-- |
| 단위 (Kotlin) | **JUnit 5** + **MockK** + **Turbine** (Flow 검증) |
| 단위 (Rust 코어) | 기존 `cargo test` (translit 정확도 회귀) |
| UI | **Compose UI Test** (`composeTestRule`) |
| 통합 (파이프라인) | **AndroidX Test** + 짧은 .wav 픽스처 (`.samples/*.mp3` → `.wav` 변환본 재사용) |
| 스크린샷 | **Paparazzi (Cash App)** 또는 **Roborazzi** — light/dark, font scale 매트릭스 |
| 성능 | **Macrobenchmark** + **Baseline Profiles** — startup, first segment latency |
| 회귀 — 음차 정확도 | Rust workspace 기존 fixture (`test.sh`) — CI 매 PR 실행 |
| 디바이스 매트릭스 | **Firebase Test Lab** 또는 GitHub Actions `reactivecircus/android-emulator-runner` — API 28 / 33 / 35 |

성공 기준 (데스크탑/iOS PRD 와 동일):
- 10 분 영어 영상 세그먼트 정확도 ≥ 85%
- 음차 가독성 ≥ 4 / 5 (베타 사용자)
- 콜드 스타트 → 첫 세그먼트 ≤ 12 초 (base, Pixel 9)
- 비행기 모드에서 100% 동작 (ML Kit 또는 번역 끄기 시)

---

## 10. 배포 / 빌드

### 10.1 빌드 시스템

- **Android Studio Ladybug** (2024.2) 이상
- **AGP 8.7+**, **Gradle 8.10+**
- **Kotlin 2.0.20+** (K2 컴파일러), **JDK 17**
- **NDK r26d+** (whisper.cpp + cargo-ndk)
- **Compose Compiler Plugin** (Kotlin 2.0 통합)

### 10.2 외부 의존성 (version catalog `libs.versions.toml`)

| 의존성 | 목적 | 라이선스 |
| :-- | :-- | :-- |
| `androidx.compose.bom` | Compose | Apache-2 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | VM | Apache-2 |
| `androidx.navigation:navigation-compose` | Nav | Apache-2 |
| `androidx.hilt:hilt-navigation-compose` + `com.google.dagger:hilt-android` | DI | Apache-2 |
| `androidx.room:room-runtime` + `room-ktx` + `room-compiler` | SQLite | Apache-2 |
| `androidx.work:work-runtime-ktx` | WorkManager | Apache-2 |
| `androidx.media3:media3-exoplayer` + `media3-ui-compose` | 미디어 | Apache-2 |
| `androidx.datastore:datastore-preferences` | 설정 | Apache-2 |
| `androidx.security:security-crypto` | Encrypted prefs | Apache-2 |
| `com.google.mlkit:translate` | ML Kit Translation | Google ToS |
| `com.squareup.okhttp3:okhttp` + `logging-interceptor` | HTTP | Apache-2 |
| `com.squareup.retrofit2:retrofit` + `converter-moshi` | REST | Apache-2 |
| `com.squareup.moshi:moshi-kotlin` | JSON | Apache-2 |
| `com.jakewharton.timber:timber` | 로깅 | Apache-2 |
| `io.coil-kt.coil3:coil-compose` | 썸네일 | Apache-2 |

테스트:
| | |
| :-- | :-- |
| `org.junit.jupiter:junit-jupiter` | JUnit 5 |
| `io.mockk:mockk` | Mock |
| `app.cash.turbine:turbine` | Flow 테스트 |
| `app.cash.paparazzi:paparazzi` 또는 `io.github.takahirom.roborazzi:roborazzi` | 스크린샷 |
| `androidx.benchmark:benchmark-macro-junit4` | 성능 |

Whisper:
- `whisper.cpp` 는 **git submodule** 로 포함, `CMakeLists.txt` 가 ABI 별 빌드 (`externalNativeBuild`)
- 또는 prebuilt AAR 을 `libs/` 에 두고 의존성으로 임포트 (CI 산출물)

### 10.3 CI/CD

- GitHub Actions `ubuntu-22.04` 러너 + `macos-14` (Apple Silicon emulator 가속) 옵션
- 단계:
  1. Rust → cargo-ndk 4 ABI 빌드 → `app/src/main/jniLibs/<abi>/`
  2. whisper.cpp CMake 빌드 (CI 캐시)
  3. `./gradlew :app:assembleRelease` (R8, ABI split)
  4. `./gradlew :app:bundleRelease` → AAB
  5. `./gradlew :app:connectedAndroidTest` (Firebase Test Lab 옵션)
  6. Play Console 업로드 (`gradle-play-publisher` 플러그인)
- 시크릿: `PLAY_STORE_SERVICE_ACCOUNT_JSON`, `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`
- 코드사이닝: **Play App Signing** (Google 측 보관) + 업로드 키만 로컬/CI

### 10.4 Play Store 제출 준비물

- 8 size 스크린샷 (휴대전화, 7"·10" 태블릿)
- 30초 프로모 비디오 (옵션)
- **Data Safety** 설문 (위 §8)
- 콘텐츠 등급 (IARC 설문) — Education / Productivity = 전체 이용가
- 타깃 API: API 35 (Android 15) — Play 정책 2025년 8월 이후 필수
- 64-bit 전용 ABI 권장
- 카테고리: Education (서브 Productivity)
- 가격: Free + IAP (Pro 모델 잠금 해제) 또는 Free
- **Play Asset Delivery (PAD)**: large model on-demand pack

---

## 11. 로드맵 (제안)

| Phase | 기간 (영업일) | 산출물 |
| :-- | :-- | :-- |
| **0. 초기 셋업** | 5d | Android Studio 프로젝트, 멀티모듈, CI 골격, Rust cargo-ndk 빌드 검증 |
| **1. Whisper JNI 통합** | 8d | 단일 짧은 .wav → segments 표시 (UI 무관 데모) |
| **2. Rust 음차 브릿지** | 4d | UniFFI Kotlin 바인딩, 데스크탑/iOS 와 fixture 일치 확인 |
| **3. UI 셸 (Home/Library/Project)** | 9d | Mock 데이터로 end-to-end navigation, Material 3 Dynamic Color |
| **4. 파일 입수 4 경로** | 6d | SAF / PhotoPicker / 공유 인텐트 / Drag-Drop |
| **5. 파이프라인 + Foreground Service** | 7d | 실제 음원 → 결과 표시, 알림 진행률 |
| **6. 번역 엔진 4종** | 4d | ML Kit / MyMemory / Libre / DeepL 토글 |
| **7. 모델 매니저 + WorkManager** | 5d | 다운로드 progress, PAD 옵션 |
| **8. 설정/Polish/접근성** | 5d | Settings, TalkBack, Dynamic font |
| **9. 베타 (Play Console Internal/Closed)** | 4d | 100명 외부 베타 |
| **10. Play Store 제출** | 3d | 스크린샷, Data Safety, 심사 대응 |

총 **약 60 영업일** (≈ 12 주, solo dev). 2인 ≈ 8주.

---

## 12. 리스크 / 대응

| 리스크 | 영향 | 대응 |
| :-- | :-- | :-- |
| Whisper 모델 메모리 (저사양 단말) | OOM 크래시 | 디바이스 RAM 기반 자동 가드, ABI 별 모델 폴백 |
| 단편화 (수많은 SoC) | 일부에서만 가속 | NNAPI 실험적이라 fallback 체인 (Vulkan → OpenCL → CPU) |
| Rust cargo-ndk 빌드 복잡도 | 신규 dev 진입장벽 | CI 산출물 AAR 로 배포, 로컬 빌드는 옵션 |
| Foreground Service 정책 변화 | Android 15+ 추가 제약 | `mediaProcessing` type 명시, declared types 빠짐없이 |
| Play 심사: 외부 모델 자동 다운로드 | 거절 가능성 | 첫 실행 시 명시적 동의 + Wi-Fi 권장. 또는 PAD on-demand 로 Google 인프라 사용 |
| ML Kit 모델 다운로드 실패 (중국 등) | 번역 안 됨 | 자동 폴백 (ML Kit → MyMemory → 끄기) |
| 백그라운드 처리 제약 (Doze, App Standby) | 장문 변환 지연 | Foreground Service 면 doze 면제. user-initiated job |
| 일부 코덱 (HEVC, DTS) 단말 미지원 | 변환 실패 | MediaCodec query → fallback 변환 시 안내 |
| 음차 품질 (다국어) | 학습 효과 저하 | 데스크탑/iOS 회귀 fixture 그대로 → 동등 보장 |
| AAB 크기 (whisper.cpp + abi splits) | base APK 50 MB 초과 | ABI per-APK 다운로드 (Play 자동), 모델 PAD |
| Room ↔ Rust SQLite 동시성 | 데이터 손상 | Rust 코어는 **SQLite 안 씀**, DB 100% Kotlin |

---

## 13. 코드/IP 재사용 매트릭스

| 자산 | 위치 | Android 재사용 방식 |
| :-- | :-- | :-- |
| `translit.rs` (1713 LOC, 다국어 음운 규칙) | `src-tauri/src/core/translit.rs` | **그대로 컴파일**, cargo-ndk → `.so` → UniFFI Kotlin 노출 |
| `models.rs`, `error.rs` | 동상 | 일부 타입 미러링 (Kotlin sealed class) |
| `asr.rs` (whisper-rs 래퍼) | Rust | 재사용 안 함 — Kotlin JNI 가 직접 whisper.cpp 호출 |
| `media.rs` (ffmpeg 사이드카) | Rust | 재사용 안 함 — MediaCodec 사용 |
| `translate.rs` | Rust | 참조용 — Kotlin `TranslateKit` 재작성 (ML Kit 추가) |
| `db.rs` (rusqlite) | Rust | 참조용 — Room 재작성. **스키마 1:1 유지** |
| `pipeline.rs` | Rust | 재구현 — Kotlin `PipelineService` + Foreground Service |
| 프론트엔드 React | `src/` | 재사용 0 — Compose 신규 |
| `.samples/*.mp3` 픽스처 | repo 루트 | **그대로 회귀 테스트 입력** |
| iOS 의 UniFFI `.udl` | iOS PRD | **재사용 가능** — 같은 `.udl` 로 Swift/Kotlin 둘 다 바인딩 생성 |

**결론**: iOS 와 동일 전략. translit.rs 만 그대로, 나머지는 Android-native. iOS 와 `.udl` 인터페이스 공유로 일관성 확보.

---

## 14. 성공 지표 (KPI)

- **Day-30 retention** ≥ 22 % (Android 평균은 iOS 보다 낮음을 감안)
- **첫 변환 완료율** ≥ 65 %
- **평점** Play Store ≥ 4.4
- **크래시-free 세션** (Crashlytics) ≥ 99.5 %
- **ANR 율** ≤ 0.5 %
- **세그먼트 정확도** ≥ 85 % (영어), ≥ 78 % (일·프)
- **베타 NPS** ≥ 35

---

## 15. 결정사항 요약 (Architectural Decisions)

| ADR | 결정 |
| :-- | :-- |
| ADR-001 | Tauri Mobile 채택 안 함. Kotlin + Compose 네이티브. |
| ADR-002 | Rust `translit.rs` 만 재사용, UniFFI Kotlin 바인딩. iOS `.udl` 공유. |
| ADR-003 | ASR 는 whisper.cpp + JNI (MVP). 가속 우선순위 Vulkan > OpenCL > CPU. |
| ADR-004 | 미디어 디코드는 MediaCodec. ffmpeg 사용 안 함. |
| ADR-005 | DB 는 Room. Rust 코어는 DB 접근 안 함. |
| ADR-006 | 번역 기본 = ML Kit (온디바이스), 폴백 MyMemory. |
| ADR-007 | 모델은 첫 실행 시 다운로드 또는 PAD on-demand. APK 에 포함 안 함. |
| ADR-008 | 최소 SDK = API 28 (Android 9.0). 타깃 SDK = 35. |
| ADR-009 | 상태관리는 Hilt + Coroutines/Flow + Compose. |
| ADR-010 | 디자인은 Material 3 + Dynamic Color. 커스텀 최소화. |
| ADR-011 | 멀티모듈 (feature + core). KMM 은 Phase 3. |
| ADR-012 | Foreground Service type = `mediaProcessing` (API 34+). |

---

## 16. iOS PRD 와의 비교 (대칭/비대칭)

| 영역 | iOS | Android |
| :-- | :-- | :-- |
| 언어 | Swift 5.10 | Kotlin 2.0 |
| UI | SwiftUI | Jetpack Compose |
| DI | manual / actor | Hilt |
| 비동기 | Swift Concurrency | Coroutines/Flow |
| DB | GRDB.swift | Room |
| 미디어 | AVFoundation | MediaCodec + Media3 |
| ASR 가속 | Core ML encoder | Vulkan/OpenCL (+ 실험적 NNAPI) |
| 번역 기본 | Apple Translation | ML Kit Translation |
| 백그라운드 | BGProcessingTask | Foreground Service + WorkManager |
| 공유 | Share Extension | Intent filter |
| 모델 배포 | 첫 실행 다운로드 | 첫 실행 다운로드 또는 PAD |
| 코드사이닝 | fastlane match | Play App Signing |
| **공통** | **Rust `translit.rs` + UniFFI 동일 `.udl`** | 동상 |
| **공통** | **DB 스키마 1:1** | 동상 |
| **공통** | **번역 엔진 추상 인터페이스** | 동상 |

→ 비즈니스 로직 ≈ 80% 추상 일치, UI/플랫폼 API 만 분기. Phase 3 KMM 도입 시 `core-pipeline`, `core-translate`, `core-data` 통합 가능성.

---

## 17. 부록 — 참고 자료

- whisper.cpp Android 가이드: <https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android>
- whisper.cpp Vulkan 빌드: <https://github.com/ggerganov/whisper.cpp/blob/master/docs/build.md>
- UniFFI Kotlin 가이드: <https://mozilla.github.io/uniffi-rs/kotlin/>
- cargo-ndk: <https://github.com/bbqsrc/cargo-ndk>
- Room: <https://developer.android.com/training/data-storage/room>
- ML Kit Translation: <https://developers.google.com/ml-kit/language/translation/android>
- Media3 ExoPlayer: <https://developer.android.com/media/media3/exoplayer>
- WorkManager: <https://developer.android.com/topic/libraries/architecture/workmanager>
- Foreground Service types (API 34): <https://developer.android.com/about/versions/14/changes/fgs-types-required>
- Play Asset Delivery: <https://developer.android.com/guide/playcore/asset-delivery>
- Material 3: <https://m3.material.io/>
- iOS 자매 문서: [PRD-for-iPhone.md](./PRD-for-iPhone.md)
- 데스크탑 PRD: `../../NGL(Go,Rust,...)/As-It-Sounds/.doc/PRD.md`
- 데스크탑 README: `../../NGL(Go,Rust,...)/As-It-Sounds/README.md`
- 웹 배포 README: `../../NGL(Go,Rust,...)/As-It-Sounds/README-Web.md`
