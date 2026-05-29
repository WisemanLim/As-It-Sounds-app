# `ModelManager.kt` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../../../../INDEX.md)

**파일 요약**
- 역할: Hugging Face GGUF 모델 다운로드 + 로컬 캐시. `.part` 임시 → atomic rename.
- LOC: 106
- exports: `WhisperModel` (enum), `ModelStatus` (sealed), `ModelManager` (class)
- 의존: `HttpURLConnection`, `FileOutputStream`, `Flow`
- 부수효과: 네트워크 I/O, `filesDir/models/` 디스크 쓰기
- 동시성: `flowOn(Dispatchers.IO)`

---

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/ModelManager.kt lines=13-32
/**
 * Whisper GGUF model variants hosted at
 * https://huggingface.co/ggerganov/whisper.cpp. PRD defaults to `base`.
 */
enum class WhisperModel(val id: String, val approxBytes: Long) {
    TINY ("tiny",   77L * 1024 * 1024),
    BASE ("base",  148L * 1024 * 1024),
    SMALL("small", 488L * 1024 * 1024);

    val fileName: String get() = "ggml-$id.bin"
    val huggingFaceURL: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus
    data class Downloading(val received: Long, val total: Long) : ModelStatus
    data class Ready(val path: String) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}
```

**분석 (Level 2)**
- `WhisperModel` enum 3 case: tiny / base / small. medium / large 제외 — Phase 3 RAM 가드 (PRD §3.5) 와 일치.
- `approxBytes`: HTTP `contentLengthLong` 가 없을 때 progress 표시용 fallback. Hugging Face 가 보통 정확한 값 반환하므로 우회 케이스.
- `fileName` / `huggingFaceURL` computed property — 직렬화·캐싱 무관, 매 호출 String concat. 호출 빈도 낮아 무관.
- `sealed interface ModelStatus` 4 case — UI 가 exhaustive when 으로 모든 상태 처리 보장.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/ModelManager.kt lines=34-43
class ModelManager(private val context: Context) {

    private val modelsDir: File by lazy {
        File(context.filesDir, "models").also { it.mkdirs() }
    }

    fun localPathIfExists(model: WhisperModel): String? {
        val f = File(modelsDir, model.fileName)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }
```

**분석 (Level 2)**
- `class ModelManager(context: Context)` — non-singleton, caller 가 lifecycle 관리. PipelineScreen 의 `remember { ModelManager(ctx) }` 가 Composition lifetime 에 묶음.
- `modelsDir: File by lazy { ... }`: 첫 접근 시 mkdirs. `also` 가 동일 객체 반환 (`apply` 와 동일 효과지만 `also` 가 `it` 사용 가능 — `mkdirs()` 가 receiver 무관해 `also` 가 더 적합).
- `localPathIfExists`: `f.length() > 0` 가드 — 0 byte file (중간 실패 잔재) 도 미존재로 처리. defensive.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/ModelManager.kt lines=45-75
    /**
     * Streams [ModelStatus] events while downloading. Cancellation is honoured
     * by closing the connection on coroutine cancel.
     */
    fun download(model: WhisperModel): Flow<ModelStatus> = flow {
        val dest = File(modelsDir, model.fileName)
        if (dest.exists() && dest.length() > 0) {
            emit(ModelStatus.Ready(dest.absolutePath))
            return@flow
        }

        val tmp = File(modelsDir, "${model.fileName}.part")
        var connection: HttpURLConnection? = null
        try {
            val url = URL(model.huggingFaceURL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            connection.connect()

            if (connection.responseCode !in 200..299) {
                emit(ModelStatus.Failed("HTTP ${connection.responseCode}"))
                return@flow
            }

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: model.approxBytes
            var received = 0L
            emit(ModelStatus.Downloading(0, total))
```

**분석 (Level 2)**
- `if (dest.exists() && dest.length() > 0) { ... return@flow }`: 이미 다운로드된 경우 즉시 Ready 반환. caller 가 매번 download 호출해도 안전 (idempotent).
- `instanceFollowRedirects = true` — Hugging Face 가 CDN 으로 30x redirect. 기본 follow.
- 15/30 sec timeouts — Hugging Face 의 50 MB+ 파일 다운로드 중간 stall 에는 30 sec read timeout 이 짧을 수 있음. Wi-Fi 환경 가정.
- `responseCode !in 200..299`: 3xx 는 follow 후 최종 코드 검사 — 200 만 success. 206 (Partial Content) 도 200..299 안에 들어가는데 resume 지원 미구현 → 200 만 의도. 안전.
- `contentLengthLong.takeIf { it > 0 } ?: approxBytes`: 음수 (-1, unknown) 또는 0 일 때 fallback. progress UI 가 발산 안 함.

```kotlin path=android/app/src/main/java/com/asitsounds/android/whisper/ModelManager.kt lines=76-105
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastEmitted = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        received += n
                        if (received - lastEmitted > 256 * 1024) {
                            emit(ModelStatus.Downloading(received, total))
                            lastEmitted = received
                        }
                    }
                }
            }

            if (!tmp.renameTo(dest)) {
                emit(ModelStatus.Failed("rename failed: ${tmp.absolutePath} -> ${dest.absolutePath}"))
                tmp.delete()
                return@flow
            }
            emit(ModelStatus.Ready(dest.absolutePath))
        } catch (t: Throwable) {
            tmp.delete()
            emit(ModelStatus.Failed(t.message ?: t::class.simpleName ?: "unknown"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
```

**분석 (Level 2)**
- `inputStream.use` + `FileOutputStream(tmp).use` 2중 use — 둘 다 close 보장.
- `ByteArray(64 * 1024)`: 64 KB buffer. 4 KB 보다 4× 빠른 throughput.
- `lastEmitted` + 256 KB threshold: UI emit throttle. 142 MB / 256 KB ≈ 580 emit → 초당 ~10 emit. UI 의 LinearProgressIndicator 가 부드럽게 갱신.
- `tmp.renameTo(dest)`: 같은 파일시스템 (filesDir) 이라 atomic. 실패 시 emit Failed + cleanup.
- `catch (t: Throwable)`: 모든 예외 catch — IOException, SocketTimeoutException, OOM, CancellationException 등 모두 동일하게 처리. **`CancellationException` 까지 catch** → coroutine cancel 시 그 예외가 Failed 로 변형되어 emit, 그 후 flow 종료. 의도는 graceful cancel 이지만 catch 가 결과 flow 의 `cancellation` 시그널을 먹어버림. 권고: `if (t is CancellationException) throw t` 추가.
- `tmp.delete()` in catch + finally 의 `connection?.disconnect()` — 자원 정리.

**짚어볼 점 / Watch-outs**
- **CancellationException catch**: coroutine cancel 이 `Failed` 로 보고됨 → caller 가 cancel 과 fail 구분 불가.
- **resume 미지원**: 50% 다운로드 후 네트워크 끊김 시 처음부터 재시작. HTTP `Range:` 헤더 + `received` 이어쓰기 미구현.
- **SHA256 검증 부재**: 다운로드 완료 후 hash 비교 안 함. MitM / 손상 파일 그대로 사용 — Whisper 가 invalid GGUF 검출하여 init 실패하긴 함 (graceful).
- timeout 짧음 — 모바일 LTE 환경에서 50 MB+ 파일 read timeout 30s 너무 빠듯. 권고: progressive backoff 또는 readTimeout 60s.
- `tmp.renameTo` 가 fail 시 reason 미상 — `Files.move(..., REPLACE_EXISTING)` (Java NIO) 가 더 자세한 IOException 던짐.

**파일 수준 평가**
- 강점:
  - `.part` 임시 + atomic rename — partial download 손상 방지.
  - cold flow + throttled emit — UI 부드러움.
  - try/catch/finally 짝맞춤.
- 위험:
  - SHA256 미검증.
  - CancellationException 변형.
  - resume 미지원.
- 개선 권고:
  1. SHA256 등록 + 검증 (e.g. `WhisperModel.BASE.sha256 = "..."`).
  2. catch 안에서 `if (t is CancellationException) throw t` 우선 분기.
  3. `HttpURLConnection.setRequestProperty("Range", "bytes=$received-")` resume 지원.
- 재읽기 권고 레벨: 2

**Cross-references**
- 호출처: `android/.../ui/PipelineScreen.kt`, `SettingsScreen.kt`
- iOS counterpart: `ios/.../Core/ModelManager.swift` — `URLSession.background` + `URLSessionDownloadDelegate` 패턴. iOS 가 백그라운드 다운로드 우월하나 본 Kotlin 은 단순.
