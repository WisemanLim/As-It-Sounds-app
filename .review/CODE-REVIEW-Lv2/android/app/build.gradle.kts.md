# `android/app/build.gradle.kts` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../INDEX.md)

**파일 요약**
- 역할: AGP 빌드 설정 — namespace, SDK, NDK, Compose, externalNativeBuild, ABI splits, packaging, 의존성.
- LOC: 123
- 의존: `gradle/libs.versions.toml` (version catalog), `core/scripts/build-android.sh` 산출 (`jniLibs/`, `java/com/asitsounds/core/`)
- 부수효과: `app/build/`, `app/.cxx/` 생성

---

```kotlin path=android/app/build.gradle.kts lines=1-11
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.asitsounds.android"
    compileSdk = 35
    ndkVersion = "30.0.14904198"
```

**분석 (Level 2)**
- `alias(libs.plugins.*)`: version catalog 의 `[plugins]` 항목 참조. 버전 일원화.
- `kotlin.plugin.compose` (2.0.21) — Kotlin 2.0 부터 Compose compiler 가 Kotlin 본체 plugin 으로 통합. 이전 `kotlinCompilerExtensionVersion` 명시 패턴 obsolete.
- `compileSdk = 35` (Android 15) + `ndkVersion = "30.0.14904198"` 명시 — Gradle 이 NDK 위치 검색 시 정확한 버전 사용. `local.properties.ndk.dir` 사용 시 발생하던 CXX5106 deprecation 회피.

```kotlin path=android/app/build.gradle.kts lines=12-34
    defaultConfig {
        applicationId = "com.asitsounds.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28",
                )
            }
        }
    }
```

**분석 (Level 2)**
- `applicationId` ≠ `namespace`: AGP 7+ 이후 분리됨. namespace 는 R class + BuildConfig 의 패키지, applicationId 는 PlayStore identifier. 같은 값으로 통일됨 — 유지보수 단순.
- `minSdk = 28` (Android 9): 2018년부터 출시된 디바이스 커버. Play Store 통계 99%+. minSdk 가 낮을수록 새 API (`Foreground service media processing` API 34+) 사용 시 `Build.VERSION.SDK_INT` 가드 코드가 늘어남.
- `targetSdk = 35`: Play Console 2025-08 부터 모든 신규 앱 35 이상 요구. 본 빌드 ready.
- `ndk.abiFilters` (3개): `x86` 제외 — Android 의 x86 device 가 사실상 없음. APK 크기 절감.
- `externalNativeBuild.cmake`:
  - `cppFlags += "-std=c++17"` — whisper.cpp + ggml 가 C++17 사용. C++20 으로 올리면 ggml-cpu 의 일부 inline asm 충돌 가능.
  - `-DANDROID_STL=c++_shared`: STL 동적 공유. 정적 (`c++_static`) 보다 APK 크기 크나, JNA 같은 다른 native lib 와의 STL ABI 호환성 보장. **현재 단일 .so 만 사용 — 정적이 더 작을 수도** (검토 가치).
  - `-DANDROID_PLATFORM=android-28`: minSdk 와 일치 — c++_shared 의 STL 심볼이 API 28 에 보장되는 것만 사용.

```kotlin path=android/app/build.gradle.kts lines=36-42
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

**분석 (Level 2)**
- 동일 `externalNativeBuild` block 이 `defaultConfig` 안에는 build 옵션 (`cppFlags`/`arguments`), `android` block 안에는 CMake 파일 위치 (`path`/`version`) — AGP 의 split 컨벤션. version 명시 (3.22.1) 가 NDK 번들 cmake 선택.

```kotlin path=android/app/build.gradle.kts lines=43-69
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
```

**분석 (Level 2)**
- `release.isMinifyEnabled = true` + `isShrinkResources = true`: R8 (D8 + minify) 활성 → APK 크기 ~50% 절감, dead code 제거. proguard-rules.pro 에 JNA + UniFFI symbol keep 정의 (review: §proguard-rules.pro 미선택).
- `debug.isMinifyEnabled = false`: 디버그 빌드 빠른 컴파일 + 정확한 스택트레이스.
- JVM 17 / Kotlin jvmTarget 17 — Java 21 LTS 사용 안 함. AGP 8.13 가 17 권장. 18+ 는 일부 코틀린 plugin 호환성 미검증.
- `buildFeatures.compose = true` — Kotlin 2.0 통합 compose plugin 이 이 flag 보고 compiler 활성.
- `buildConfig = true` — `BuildConfig.DEBUG` 등 자동 생성. AGP 8 부터 기본 false 라 명시.

```kotlin path=android/app/build.gradle.kts lines=71-94
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}
```

**분석 (Level 2)**
- `sourceSets.main.jniLibs.srcDirs("src/main/jniLibs")` — cargo-ndk 가 emit 한 `.so` 들이 자동 packaging.
- `packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"`: Apache 2.0 / LGPL 2.1 LICENSE 파일 중복 충돌 회피. 의존성 META-INF clash 흔한 패턴.
- `packaging.jniLibs.useLegacyPackaging = false`: API 23+ 부터 ZIP-aligned uncompressed 직접 로드 — `libjnidispatch.so` 의 16 KB page alignment 와도 호환 (이전 phase-2 incident 해결 핵심).
- `splits.abi`:
  - `reset()` 후 3 ABI include — `defaultConfig.ndk.abiFilters` 와 중복. AGP 가 두 조합의 교집합 적용.
  - `isUniversalApk = true` — ABI splits 외에 모든 ABI 포함 universal APK 도 생성. CI 산출에 양쪽 모두 있어 디바이스 호환성 검증 용이.

```kotlin path=android/app/build.gradle.kts lines=97-122
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // JNA Android distribution ships as AAR (contains classes.jar + jni/<abi>/libjnidispatch.so).
    // The @aar extension forces Gradle to fetch the AAR variant instead of the plain JAR.
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}
```

**분석 (Level 2)**
- `platform(libs.compose.bom)`: Compose BOM (Bill of Materials) — 단일 BOM 버전이 모든 compose-ui-* 의존성을 호환 set 으로 고정. compose-ui-* 의 개별 버전 지정 불필요.
- **JNA 직접 string**: `libs.versions.toml` 의 `jna = { module = ... }` 가 있음에도 `implementation("net.java.dev.jna:jna:5.17.0@aar")` 로 inline — `@aar` extension 명시가 version catalog 표준 syntax 로는 어색하기 때문. catalog 자체에 `@aar` 표기를 못 넣음. **risk**: 5.17.0 버전이 두 곳에 (catalog + 이 라인) 박힘 → drift 가능.
- `debugImplementation` 으로 compose-ui-tooling 분리 — release APK 에서 ~3 MB 절감 + Preview 코드 제외.
- 테스트 의존성 4종 declared but unused (테스트 파일 부재) — risk: dead dep, 그러나 곧 도입 가정 시 OK.

**짚어볼 점 / Watch-outs**
- `versionCode = 1` — Play Store 출시 후 매번 +1 필요. CI 의 자동 bump 미설정.
- `versionName = "0.1.0"` — semver 사용 OK.
- `splits.abi.isUniversalApk = true` 가 universal + 3 split = 4 APK 동시 생성. 디스크 4× 사용, CI 시간 증가. Production 출시는 AAB (bundleRelease) 사용 — `isUniversalApk` 는 사실상 dev/manual install 용.
- `ksp` plugin 카탈로그에 선언만 — Room 미사용으로 비활성. dead declaration.
- proguard-rules.pro 의 내용 미검토 — JNA + UniFFI keep 필수. minify enable 상태에서 미흡 시 release crash 가능.

**파일 수준 평가**
- 강점:
  - AGP 8.13 modern 패턴 (Kotlin 2.0 Compose plugin, namespace, externalNativeBuild split).
  - ABI splits + universal APK 동시 산출 — 호환성 + 사이즈 절감.
  - JNA 16 KB page-size 호환 (useLegacyPackaging=false + JNA 5.17.0).
- 위험:
  - JNA 버전이 catalog + inline 두 곳에 drift 가능.
  - `versionCode` CI 자동 bump 미설정.
  - proguard-rules.pro 내용 sanity 미검증.
- 개선 권고:
  1. `libs.versions.toml` 에 `jna-aar = "net.java.dev.jna:jna:5.17.0@aar"` 같은 string 형식 도입 검토 (catalog `[plugins]` 가 아닌 `[bundles]` 활용 또는 그냥 inline 유지).
  2. CI 워크플로우에서 `versionCode = git rev-list HEAD --count` 같은 자동화.
  3. proguard-rules.pro 검토 + ProGuard test build 검증.
- 재읽기 권고 레벨: 3 (Level 3 은 ABI splits 의 trade-off, proguard-rules.pro 영향까지 들여다 볼 가치)
