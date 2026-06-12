# KMM (Kotlin Multiplatform) 마이그레이션 계획

> 🌐 **한국어** · [English](kmm-migration.en.md)

Phase 3.5 목표: iOS와 Android 간의 *비-UI* 비즈니스 로직을 통합하여, 현재 Swift + Kotlin으로 중복된 코드를 단일 Kotlin 소스로 대체하는 것이다. Rust 변환 코어는 유지되며, Kotlin↔Swift 중복 비즈니스 코드만 이동한다.

## `shared/commonMain`으로 이동할 항목

| 현재 모듈 | Phase 3.5 이동 위치 | 비고 |
| :-- | :-- | :-- |
| `Translator.kt` + `Translator.swift` | `shared/commonMain/Translator.kt` | HttpURLConnection + URLSession을 Ktor 클라이언트로 대체 |
| `ModelManager.kt` + `ModelManager.swift` | `shared/commonMain/ModelManager.kt` | OS 파일 시스템에 `expect/actual` 사용 |
| `Pipeline.kt` + `Pipeline.swift` | `shared/commonMain/Pipeline.kt` | 오케스트레이션: 순수 Kotlin Flow |
| `AppSettings.kt` + `AppSettings.swift` | `shared/commonMain/Settings.kt` | `multiplatform-settings` 라이브러리 |
| `Entities.kt` + `Database.swift` | `shared/commonMain/Db.kt` | `SQLDelight` 스키마가 양쪽 플랫폼 DAO를 생성 |
| `SubtitleExport.kt` + `SubtitleExport.swift` | `shared/commonMain/SubtitleExport.kt` | 순수 Kotlin 문자열 빌더 |

## 플랫폼 네이티브로 유지할 항목

| 컴포넌트 | 이유 |
| :-- | :-- |
| SwiftUI / Compose UI | 플랫폼별 관용적 UX가 핵심이기 때문 |
| `WhisperEngine.mm` + `ais_whisper_jni.cpp` | JNI / Obj-C++ 브릿지는 Kotlin으로 대체 불가 |
| `AudioDecoder.swift` (AVFoundation) / `AudioDecoder.kt` (MediaCodec) | OS 전용 미디어 API |
| `BackgroundTask.swift` (BGProcessingTask) / `TranscribeForegroundService.kt` | OS 라이프사이클 API |
| `PlayerView.swift` (AVPlayer) / `PlayerScreen.kt` (Media3) | OS 전용 미디어 재생 |
| `LiveActivity.swift` / `TranscribeQuickTile.kt` | OS 전용 UI 표면 |
| `CloudSync.swift` (CloudKit) | iOS 전용 API |
| `AppShortcuts.swift` (App Intents) | iOS 전용 API |

## 목표 모듈 구조

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

iOS는 Gradle 태스크 실행 시 XCFramework으로 내보내지는 `framework { baseName = "AisShared" }` 를 통해 사용한다. Swift 코드는 일반 바이너리 프레임워크처럼 임포트한다.

## 추가할 의존성 (Gradle)

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

## 마이그레이션 단계 (예상 일정)

1. **스캐폴딩**: KMP 플러그인으로 `shared/` 모듈 생성 — 1일.
2. **Translator** (위험도 가장 낮음, DB 없음): Swift/Kotlin 포팅 및 호출부 교체 — 2일.
3. **Settings** + **SubtitleExport** (둘 다 순수 로직) — 1일.
4. **ModelManager** (`expect` 파일시스템 필요) — 1일.
5. **Pipeline** (Whisper 핸들을 `expect`로 전달 필요) — 2일.
6. **SQLDelight 스키마** + DAO 마이그레이션 (GRDB + SQLiteOpenHelper 제거) — 3일.
7. **XCFramework 내보내기** + Swift 호출부 업데이트 — 1일.
8. **CI 업데이트**: iOS 워크플로우 내에서 Gradle이 iOS XCFramework를 빌드하도록 설정 — 1일.

총 약 12 근무일 (솔로 개발자 기준; iOS 개발자가 페어링 시 훨씬 단축 가능).

## 리스크

- **KSP 버전 불안정성** (Kotlin 2.0.x 환경): Phase 2에서 `Wrong plugin option format: null` 오류 발생 이력 있음. SQLDelight가 KSP를 사용하므로 스키마 포팅 전 재검증 필요.
- **Ktor `Darwin` 엔진**은 IPA에 ~600 KB를 추가함 — 반드시 먼저 측정할 것.
- **Compose Multiplatform**은 이번 마이그레이션 *범위 외*; UI는 네이티브 유지.

## 지금 당장 하지 않는 이유

KMM 통합을 Phase 3.5로 미룬 이유:

1. Phase 0–2에서 양 플랫폼에 검증된 동작하는 코드가 먼저 필요했다;
   검증되지 않은 코드를 공유하는 것은 리스크를 두 배로 늘리는 것뿐이다.
2. 가장 높은 가치를 지닌 공유 자산인 변환 코어는 이미 UniFFI로 공유됨 —
   KMM은 비즈니스 로직 레이어에만 가치를 더한다.
3. KSP/Kotlin 컴파일러 불안정성은 실재한다 ([P2-2b 인시던트](signing.md) 참조);
   KMP 추가 전 Phase-2 스택을 안정화하면 연쇄적인 디버깅 세션을 방지할 수 있다.

Phase 2가 프로덕션에서 안정화되면 (출시 후 약 2주간의 텔레메트리 이후), Phase 3.5로 전환한다.
