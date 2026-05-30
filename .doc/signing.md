# Code signing — As It Sounds

> 🌐 **한국어** · [English](signing.en.md)

릴리즈 빌드에 서명하는 방법 (양쪽 스토어). 로컬 및 CI 경로.

## iOS — Apple Developer ID

### 사전 준비

1. **Apple Developer Program** 멤버십 (유료, $99/년).
2. App ID `com.asitsounds.ios` 를
   [Certificates, IDs & Profiles](https://developer.apple.com/account/resources/identifiers/list)에 등록.
3. 배포 인증서 (Apple Distribution) — 빌드 호스트의 키체인에 설치.
4. 프로비저닝 프로파일 — App Store 용, App ID + 인증서 포함.

### 로컬 아카이브 + 공증

```bash
# 필수 환경 변수. TestFlight/공증 단계에서 AC_PASSWORD 레이블로
# macOS 키체인에 저장된 앱 전용 비밀번호를 사용합니다.
export APPLE_SIGNING_IDENTITY="Apple Distribution: WISEMAN LIM (TEAMID)"
export APPLE_ID="wiseman.lim@example.com"
export APPLE_PASSWORD="@keychain:AC_PASSWORD"
export APPLE_TEAM_ID="TEAMID"

cd ios
xcodebuild -project AsItSounds.xcodeproj \
  -scheme AsItSounds \
  -configuration Release \
  -archivePath build/AsItSounds.xcarchive \
  archive

xcodebuild -exportArchive \
  -archivePath build/AsItSounds.xcarchive \
  -exportOptionsPlist ../.doc/export-options.plist \
  -exportPath build/export
```

### `.doc/export-options.plist`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key><string>app-store</string>
    <key>teamID</key><string>TEAMID</string>
    <key>uploadBitcode</key><false/>
    <key>uploadSymbols</key><true/>
    <key>signingStyle</key><string>automatic</string>
</dict>
</plist>
```

### TestFlight 업로드

```bash
xcrun altool --upload-app -f build/export/AsItSounds.ipa \
  --apiKey "$APP_STORE_CONNECT_API_KEY_ID" \
  --apiIssuer "$APP_STORE_CONNECT_ISSUER_ID"
```

(`altool`은 공증 용도에서 `notarytool`로 대체 예정이며,
업로드 용도의 `xcrun altool` 또한 `Transporter`로 단계적으로 교체되고 있습니다.
CI에서는
[App Store Connect API 직접 호출](https://developer.apple.com/documentation/appstoreconnectapi)을 권장합니다.)

---

## Android — Play App Signing

업로드 키는 개발자가 보관하며, Play가 서버에서 자체 앱 서명 키로 재서명합니다.

### 최초 설정

```bash
# 업로드 키스토어 생성. 비밀번호를 반드시 보관하세요 —
# 지원팀에 문의하지 않으면 키를 교체할 수 없습니다.
keytool -genkey -v \
  -keystore android/upload-keystore.jks \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -alias asitsounds-upload \
  -storepass "${UPLOAD_STORE_PASSWORD}" \
  -keypass "${UPLOAD_KEY_PASSWORD}" \
  -dname "CN=As It Sounds, OU=Mobile, O=As It Sounds, L=Seoul, C=KR"
```

`~/.gradle/gradle.properties` 에 추가하거나 CI 시크릿으로 설정하세요 (절대 커밋 금지):

```properties
AIS_UPLOAD_STORE_FILE=/abs/path/to/upload-keystore.jks
AIS_UPLOAD_STORE_PASSWORD=...
AIS_UPLOAD_KEY_ALIAS=asitsounds-upload
AIS_UPLOAD_KEY_PASSWORD=...
```

### `android/app/build.gradle.kts` — 릴리즈 서명 설정

`android { ... }` 블록 안에 추가:

```kotlin
signingConfigs {
    create("release") {
        val p = project.providers
        storeFile     = file(p.gradleProperty("AIS_UPLOAD_STORE_FILE").get())
        storePassword = p.gradleProperty("AIS_UPLOAD_STORE_PASSWORD").get()
        keyAlias      = p.gradleProperty("AIS_UPLOAD_KEY_ALIAS").get()
        keyPassword   = p.gradleProperty("AIS_UPLOAD_KEY_PASSWORD").get()
    }
}

buildTypes {
    release {
        isMinifyEnabled = true
        signingConfig = signingConfigs.getByName("release")
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro",
        )
    }
}
```

### AAB 빌드

```bash
cd android
./gradlew :app:bundleRelease
# → android/app/build/outputs/bundle/release/app-release.aab
```

[Play Console](https://play.google.com/console) 또는
[Publisher API](https://developers.google.com/android-publisher)를 통해 업로드하세요.

---

## 시크릿 매트릭스 (CI)

GitHub Actions 저장소 시크릿에 다음 값들을 등록하세요:

### iOS

| 시크릿 | 출처 |
| :-- | :-- |
| `APPLE_TEAM_ID` | 개발자 포털 — Membership Details |
| `APPLE_SIGNING_IDENTITY` | 배포 인증서의 전체 Common Name |
| `APPLE_ID` | Apple ID 이메일 |
| `APPLE_PASSWORD` | appleid.apple.com에서 생성한 앱 전용 비밀번호 |
| `APPLE_CERT_P12` | 서명 인증서 `.p12` 내보내기 파일의 Base64 |
| `APPLE_CERT_P12_PASSWORD` | `.p12` 내보내기 시 설정한 비밀번호 |
| `APPLE_PROVISIONING_PROFILE` | App Store 프로파일의 Base64 |
| `APP_STORE_CONNECT_API_KEY_ID` | App Store Connect API |
| `APP_STORE_CONNECT_ISSUER_ID` | App Store Connect API |
| `APP_STORE_CONNECT_API_KEY` | `.p8` 개인 키의 Base64 |

### Android

| 시크릿 | 출처 |
| :-- | :-- |
| `AIS_UPLOAD_KEYSTORE_BASE64` | `base64 -i upload-keystore.jks` |
| `AIS_UPLOAD_STORE_PASSWORD` | `keytool -genkey` 실행 시 설정한 값 |
| `AIS_UPLOAD_KEY_PASSWORD` | 동일 |
| `AIS_UPLOAD_KEY_ALIAS` | `asitsounds-upload` |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play Developer API용 JSON 키 |

`release.yml` 워크플로우(다음 섹션)가 이 값들을 읽으며, 로그에 절대 출력하지 않습니다.
