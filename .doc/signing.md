# Code signing — As It Sounds

How to sign release builds for both stores. Local + CI paths.

## iOS — Apple Developer ID

### Prerequisites

1. **Apple Developer Program** membership (paid, $99/yr).
2. App ID `com.asitsounds.ios` registered in
   [Certificates, IDs & Profiles](https://developer.apple.com/account/resources/identifiers/list).
3. Distribution certificate (Apple Distribution) — keychain on the build host.
4. Provisioning profile — App Store, includes the App ID + the cert.

### Local archive + notarize

```bash
# Required env. The TestFlight/notarize step uses an app-specific password
# stored in the macOS keychain under the AC_PASSWORD label.
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

### TestFlight upload

```bash
xcrun altool --upload-app -f build/export/AsItSounds.ipa \
  --apiKey "$APP_STORE_CONNECT_API_KEY_ID" \
  --apiIssuer "$APP_STORE_CONNECT_ISSUER_ID"
```

(`altool` is deprecated in favour of `notarytool` for notarization, and
`xcrun altool` is being phased out for upload in favour of `Transporter`.
For CI prefer the
[App Store Connect API directly](https://developer.apple.com/documentation/appstoreconnectapi).)

---

## Android — Play App Signing

Upload key is held by the developer; Play re-signs with its own app signing
key on the server.

### One-time setup

```bash
# Generate the upload keystore. KEEP THE PASSWORDS — Play will not let you
# rotate the key without contacting support.
keytool -genkey -v \
  -keystore android/upload-keystore.jks \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -alias asitsounds-upload \
  -storepass "${UPLOAD_STORE_PASSWORD}" \
  -keypass "${UPLOAD_KEY_PASSWORD}" \
  -dname "CN=As It Sounds, OU=Mobile, O=As It Sounds, L=Seoul, C=KR"
```

Add to `~/.gradle/gradle.properties` (or to CI secrets, never commit):

```properties
AIS_UPLOAD_STORE_FILE=/abs/path/to/upload-keystore.jks
AIS_UPLOAD_STORE_PASSWORD=...
AIS_UPLOAD_KEY_ALIAS=asitsounds-upload
AIS_UPLOAD_KEY_PASSWORD=...
```

### `android/app/build.gradle.kts` — release signing

Add inside the `android { ... }` block:

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

### Build an AAB

```bash
cd android
./gradlew :app:bundleRelease
# → android/app/build/outputs/bundle/release/app-release.aab
```

Upload via [Play Console](https://play.google.com/console) or the
[Publisher API](https://developers.google.com/android-publisher).

---

## Secrets matrix (CI)

Drop these into the GitHub Actions repository secrets:

### iOS

| Secret | Source |
| :-- | :-- |
| `APPLE_TEAM_ID` | Developer portal — Membership Details |
| `APPLE_SIGNING_IDENTITY` | Full common name of the dist cert |
| `APPLE_ID` | Your Apple ID email |
| `APPLE_PASSWORD` | App-specific password generated at appleid.apple.com |
| `APPLE_CERT_P12` | Base64 of `.p12` export of the signing cert |
| `APPLE_CERT_P12_PASSWORD` | Password used during `.p12` export |
| `APPLE_PROVISIONING_PROFILE` | Base64 of the App Store profile |
| `APP_STORE_CONNECT_API_KEY_ID` | App Store Connect API |
| `APP_STORE_CONNECT_ISSUER_ID` | App Store Connect API |
| `APP_STORE_CONNECT_API_KEY` | Base64 of the `.p8` private key |

### Android

| Secret | Source |
| :-- | :-- |
| `AIS_UPLOAD_KEYSTORE_BASE64` | `base64 -i upload-keystore.jks` |
| `AIS_UPLOAD_STORE_PASSWORD` | One you set during `keytool -genkey` |
| `AIS_UPLOAD_KEY_PASSWORD` | Same |
| `AIS_UPLOAD_KEY_ALIAS` | `asitsounds-upload` |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON key for Play Developer API |

The `release.yml` workflow (next section) reads these and never logs them.
