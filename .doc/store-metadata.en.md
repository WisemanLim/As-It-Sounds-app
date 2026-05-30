# Store metadata — As It Sounds

> 🌐 [한국어](store-metadata.md) · **English**

Copy/paste ready for App Store Connect + Google Play Console.

## App Store Connect

### Identity

- **Bundle ID** — `com.asitsounds.ios`
- **SKU** — `as-it-sounds-ios`
- **Primary category** — Education
- **Secondary category** — Productivity
- **Content rating** — 4+ (no objectionable content)
- **Price** — Free + non-consumable IAP (Phase 2: model unlock)

### Localized listings

#### en — App name

`As It Sounds`

#### en — Subtitle (≤30 chars)

`Hear it. Read it in Hangul.`

#### en — Promotional text (≤170 chars)

```
Transcribe English, Japanese, French, Spanish, German or Russian audio
to Hangul exactly as it sounds, plus Korean translation — all on device.
```

#### en — Description

```
As It Sounds turns any audio or video into Korean Hangul that mirrors
the actual sound, not the dictionary spelling. Pair it with the Korean
translation to grasp meaning while you learn the rhythm of the real
language.

WHY HANGUL PHONETICS
"As it sounds" becomes 애즈 잇 사운즈, not 그것이 들리는 대로. We use
context-aware phonetic rules (1,700+ lines of them) — single letters,
contractions, French nasals, Japanese kana, Spanish jota — so you can
say it the way a native speaker actually says it.

100% ON-DEVICE
Whisper.cpp + Core ML acceleration runs locally. Your audio never
leaves your iPhone. Translation optionally calls a free service
(MyMemory) or a self-hosted LibreTranslate; you choose.

WHAT'S INSIDE
• 6 supported languages auto-detected
• 3 Whisper model sizes (75 MB → 466 MB)
• Audio + video formats: MP3, M4A, WAV, MP4, MOV, MKV, …
• Player with auto-highlight of the current segment
• Export to SRT, VTT, or TXT
• Library that persists your projects locally
• Korean + English UI

PRIVACY
We collect zero data. No analytics, no tracking, no account.
```

#### en — Keywords (≤100 chars, comma-separated)

`hangul,korean,whisper,transcribe,phonetic,translit,subtitle,srt,english,japanese`

#### en — Support URL / Marketing URL

Replace with the project's actual URLs before submission.

#### ko — 앱 이름

`As It Sounds`

#### ko — 부제 (≤30자)

`들리는 발음 그대로 한글로.`

#### ko — 프로모션 텍스트 (≤170자)

```
영어·일본어·프랑스어·스페인어·독일어·러시아어 음성을 한글 발음과
한국어 번역으로 보여줍니다. 디바이스 안에서 전부 처리.
```

#### ko — 설명

```
As It Sounds 는 음성/영상의 실제 소리를 한글로 옮겨줍니다. 사전식
발음이 아닌, 원어민이 말하는 그대로의 한글 음차와 한국어 번역을
나란히 보여주어 의미와 리듬을 동시에 익힐 수 있습니다.

왜 한글 음차인가
"As it sounds" 를 "그것이 들리는 대로" 가 아닌 애즈 잇 사운즈 로.
1,700 줄이 넘는 문맥 인식 음운 규칙 — 단일 알파벳, 축약형, 프랑스어
비음, 일본어 가나, 스페인어 jota — 으로 원어민의 실제 발음에 가깝게.

100% 온디바이스
Whisper.cpp + Core ML 가속이 디바이스에서 실행. 음성이 외부로
전송되지 않습니다. 번역은 무료(MyMemory) 또는 셀프호스트
LibreTranslate 를 선택적으로 사용할 수 있습니다.

주요 기능
• 6개 언어 자동 감지
• Whisper 모델 3종 (75 MB ~ 466 MB)
• 음성/영상 포맷: MP3, M4A, WAV, MP4, MOV, MKV, …
• 재생 중 현재 세그먼트 자동 하이라이트
• SRT, VTT, TXT 내보내기
• 로컬 보관 라이브러리
• 한국어 + 영어 UI

프라이버시
수집하는 데이터 0. 분석·트래킹·계정 없음.
```

### Privacy

App privacy form answers:

- **Contact info** — Not collected
- **Health & fitness** — Not collected
- **Financial info** — Not collected
- **Location** — Not collected
- **Sensitive info** — Not collected
- **Contacts** — Not collected
- **User content (photos, audio)** — Not collected. (Audio is read into memory and
  written to the app's container; never leaves the device unless the user
  explicitly enables LibreTranslate/DeepL.)
- **Browsing history** — Not collected
- **Search history** — Not collected
- **Identifiers** — Not collected
- **Purchases** — N/A until IAP ships
- **Usage data** — Not collected
- **Diagnostics** — Optional (Crash logs only, not linked, not for tracking)
- **Other data** — Not collected

### Required disclosures

- **Export compliance** — Uses standard encryption (HTTPS to translation API
  only). Eligible for the App Store "exempt" path.
- **Encryption** — Yes (TLS only).
- **Third-party data use** — None.

---

## Google Play Console

### Identity

- **Application ID** — `com.asitsounds.android`
- **Primary category** — Education
- **Tags** — Productivity, Languages, Subtitles
- **Content rating** — Everyone (IARC)

### Store listing (en-US)

#### App title (≤30 chars)

`As It Sounds`

#### Short description (≤80 chars)

`Hear it. Read it in Hangul. On-device Whisper + Korean translation.`

#### Full description (≤4000 chars)

```
As It Sounds turns any audio or video into Korean Hangul that mirrors
the actual sound, not the dictionary spelling. Pair it with the Korean
translation to grasp meaning while learning the rhythm of the real
language.

Why Hangul phonetics
"As it sounds" becomes 애즈 잇 사운즈, not 그것이 들리는 대로. We use
context-aware phonetic rules (1,700+ lines of them) so single letters,
contractions, French nasals, Japanese kana, and Spanish jota all come
through the way a native speaker actually says them.

100% on-device transcription
Whisper.cpp runs locally with hardware acceleration on modern SoCs
(Vulkan/OpenCL where supported). Audio never leaves your device.
Translation optionally calls a free service (MyMemory) or a
self-hosted LibreTranslate — your choice, your data.

What's inside
• 6 supported languages, auto-detected
• 3 Whisper model sizes (75 MB → 466 MB)
• Audio + video formats: MP3, M4A, WAV, MP4, MOV, MKV, …
• Player with auto-highlight of the current segment
• Export to SRT, VTT, or TXT
• Library that persists your projects locally
• Korean + English UI

Privacy
We collect zero data. No analytics, no tracking, no account.
```

### Localized — ko-KR

#### 앱 제목

`As It Sounds`

#### 짧은 설명 (≤80자)

`들리는 발음 그대로 한글로 — 온디바이스 Whisper + 한국어 번역.`

#### 자세한 설명

(Use ko description from App Store Connect above.)

### Data safety (Play Console form)

- **Does your app collect or share any of the required user data types?** — No
- **Is all of the user data collected by your app encrypted in transit?** — Yes
- **Do you provide a way for users to request that their data be deleted?** — N/A
  (no data collected)

### Permissions justification

| Permission | Why we need it |
| :-- | :-- |
| `RECORD_AUDIO` | Live recording (Phase 3 feature) — declared in advance |
| `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` | User-picked file import via PhotoPicker |
| `POST_NOTIFICATIONS` | Foreground service progress notification |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` | Required type for background transcription on API 34+ |
| `INTERNET` | Optional translator API + first-run Whisper model download |

### Content rating questionnaire (IARC)

- Violence — None
- Sex / nudity — None
- Profanity — None
- Drugs / alcohol / tobacco — None
- Simulated gambling — None
- User-generated content / chat — None
- Location sharing — None
- Personal info sharing — None

Result: **Everyone**.
