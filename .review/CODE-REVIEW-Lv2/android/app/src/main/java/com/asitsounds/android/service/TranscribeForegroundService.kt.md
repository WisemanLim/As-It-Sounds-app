# `TranscribeForegroundService.kt` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../../../../INDEX.md)

**파일 요약**
- 역할: Pipeline 백그라운드 실행 + 알림 채널 + StateFlow 노출 + DB 업데이트.
- LOC: 175
- 진입점: AndroidManifest 의 `<service android:foregroundServiceType="mediaProcessing">` + Intent `ACTION_RUN`
- 의존: `Pipeline`, `AppDatabase`, `TranslatorChoice`, `NotificationCompat`, `MainActivity` (PendingIntent)
- 부수효과: Foreground notification, DB update, coroutine scope
- 동시성: `SupervisorJob() + Dispatchers.IO` 안에서 단일 run job

> 본 파일은 175 LOC. **책임 과다** 가 INDEX §4.3 hotspot #3. 블록 단위 분석.

---

```kotlin path=android/app/src/main/java/com/asitsounds/android/service/TranscribeForegroundService.kt lines=44-68
class TranscribeForegroundService : Service() {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
    private var runJob: Job? = null

    inner class LocalBinder : Binder() {
        val service: TranscribeForegroundService get() = this@TranscribeForegroundService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat(buildNotification("Preparing…"))
        when (intent?.action) {
            ACTION_RUN -> {
                val args = RunArgs.fromIntent(intent) ?: run { stopSelf(); return START_NOT_STICKY }
                startRun(args)
            }
            ACTION_STOP -> { runJob?.cancel(); stopSelfAndCleanup() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }
```

**분석 (Level 2)**
- `SupervisorJob()`: 자식 coroutine 의 실패가 부모 scope 를 cancel 안 함. 한 run 실패해도 service 자체 살아있음.
- `runJob: Job?`: 진행 중 작업 핸들. 다음 `ACTION_RUN` 들어오면 기존 cancel + 새로 시작.
- `LocalBinder`: bound service 패턴. UI 에서 service instance 직접 접근 가능 (StateFlow 구독). 현재 UI 측 사용 흔적은 `Holder._state` static 으로 우회 — Binder 가 사실상 사용 안됨.
- `onStartCommand`:
  - `ensureChannel()` + `startForegroundCompat` 가 **항상** 호출 — `ACTION_STOP` 도 일단 foreground 진입 후 stop. Android 14+ 는 service 시작 후 5초 내 foreground 호출 의무 — 안전 패턴.
  - `START_NOT_STICKY`: 시스템 kill 후 재시작 안 함. Intent 가 명시적이라 재시작 무의미.
  - `RunArgs.fromIntent(intent) ?: ...`: parse 실패 시 stopSelf — fail-fast.

```kotlin path=android/app/src/main/java/com/asitsounds/android/service/TranscribeForegroundService.kt lines=70-103
    private fun startRun(args: RunArgs) {
        runJob?.cancel()
        runJob = scope.launch {
            Holder._state.value = RunState.Active("starting", 0, 0)
            val tx = args.translatorChoice.make()
            try {
                Pipeline.run(
                    context = applicationContext,
                    modelPath = args.modelPath,
                    audioUri = Uri.parse(args.audioUri),
                    translator = tx,
                    persistFileId = args.persistFileId,
                ).collect { e ->
                    Holder._state.value = e.toRunState()
                    updateNotification(e.toRunState().headline)
                    if (e is PipelineEvent.Done) {
                        AppDatabase.get(applicationContext).mediaFileDao().let { dao ->
                            dao.get(args.persistFileId)?.let { dao.update(it.copy(status = FileStatus.done)) }
                        }
                    }
                    if (e is PipelineEvent.Failed) {
                        AppDatabase.get(applicationContext).mediaFileDao().let { dao ->
                            dao.get(args.persistFileId)?.let {
                                dao.update(it.copy(status = FileStatus.error, error = e.message))
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Holder._state.value = RunState.Done(0)
            } finally {
                stopSelfAndCleanup()
            }
        }
    }
```

**분석 (Level 2)**
- `runJob?.cancel()` 후 새 launch — concurrent run 방지 (단일 큐).
- `Holder._state.value = ...` static StateFlow 갱신 — UI 가 어디서든 구독.
- `Pipeline.run(...).collect { e -> ... }`: cold flow 가 service scope 안에서 hot.
- **DB 업데이트 책임**: Done/Failed 시점에 mediaFileDao.update — Pipeline 이 segments 만 저장, status 업데이트는 service 가 담당. Pipeline 의 caller 별로 status 의미 다름 (UI 직접 호출 vs FG service) — 의도된 분리이지만 SRP 측면에서 service 가 너무 많은 책임.
- `catch (t: Throwable) { Holder._state.value = RunState.Done(0) }`: 모든 예외 → Done(0). **CancellationException 도 Done 으로 보고됨** → cancel 시 UI 가 정상 완료로 오해. `if (t is CancellationException) throw t` 권고.
- `finally { stopSelfAndCleanup() }`: 정상/실패/cancel 모두 cleanup — 자원 정리.

```kotlin path=android/app/src/main/java/com/asitsounds/android/service/TranscribeForegroundService.kt lines=105-160
    private fun stopSelfAndCleanup() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Holder._state.value = RunState.Idle
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transcribing…")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }
```

**분석 (Level 2)**
- `startForegroundCompat`: Android 14+ (API 34) 가 `foregroundServiceType` 인자 의무화 — `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` 명시. Manifest 의 `mediaProcessing` 과 일치.
- `PendingIntent.FLAG_IMMUTABLE`: API 31+ 의무. 다른 앱이 PendingIntent 의 인자 수정 불가.
- `setOngoing(true)` + `PRIORITY_LOW`: 사용자가 swipe-away 못함, 알림 영역에서 조용히. 표준 transcoding/background 패턴.
- `android.R.drawable.stat_notify_sync` — 시스템 기본 아이콘. 자체 아이콘 권고.

```kotlin path=android/app/src/main/java/com/asitsounds/android/service/TranscribeForegroundService.kt lines=170-215
    sealed interface RunState {
        val headline: String
        data object Idle : RunState { override val headline = "Idle" }
        data class Active(val phase: String, val cur: Int, val total: Int) : RunState {
            override val headline: String = if (total > 0) "$phase $cur/$total" else phase
        }
        data class Done(val segmentCount: Int) : RunState { override val headline = "Done" }
    }

    data class RunArgs(
        val modelPath: String,
        val audioUri: String,
        val persistFileId: String,
        val translatorChoice: TranslatorChoice,
    ) {
        fun toIntent(ctx: Context): Intent = Intent(ctx, TranscribeForegroundService::class.java).apply {
            action = ACTION_RUN
            putExtra("modelPath", modelPath)
            putExtra("audioUri", audioUri)
            putExtra("persistFileId", persistFileId)
            putExtra("translator", translatorChoice.name)
        }
        companion object {
            fun fromIntent(i: Intent): RunArgs? {
                val m = i.getStringExtra("modelPath") ?: return null
                val u = i.getStringExtra("audioUri") ?: return null
                val f = i.getStringExtra("persistFileId") ?: return null
                val t = i.getStringExtra("translator") ?: TranslatorChoice.MYMEMORY.name
                return RunArgs(m, u, f, TranslatorChoice.valueOf(t))
            }
        }
    }
```

**분석 (Level 2)**
- `RunState` sealed interface + `headline` computed property — UI 의 알림 텍스트 일원화.
- `RunArgs` data class + Intent encode/decode — `Parcelable` 대신 String 4개로 단순. enum 은 `.name` 으로 serialise.
- `TranslatorChoice.valueOf(t)` — invalid string 입력 시 `IllegalArgumentException` throw. fromIntent 에서 catch 안 함 — Intent 외부 조작 시 service crash 가능. defensive coding 약함.

**짚어볼 점 / Watch-outs**
- **책임 과다 (SRP 위반)**: Service 한 클래스가 (a) Foreground notification (b) Pipeline 실행 (c) StateFlow Holder (d) DB status update (e) RunArgs marshalling — 5 책임. WorkManager + Repository 패턴으로 분리 가치.
- **CancellationException 변형**: catch (Throwable) → Done(0). 정상 cancel 과 실제 fail 구분 불가.
- **`runJob?.cancel()` 후 동일 job 변수 reassign**: 새 launch 가 즉시 실행. 이전 job 의 finally 가 새 job 의 시작과 race — `Holder._state.value` 가 새 Active 로 갱신 후 이전 finally 의 stopSelfAndCleanup 가 Idle 로 덮어쓸 가능성. 다행히 cleanup 도 `runJob?.cancel()` 호출 등 다른 액션 동반 안 함, idle reset 만 — race 영향 작음.
- Notification text hard-code English (`"Transcribing…"`, `"Done"`). i18n 누락.

**파일 수준 평가**
- 강점:
  - Android 14+ `mediaProcessing` type 정확 명시.
  - sealed interface + StateFlow + Intent marshalling 깔끔.
  - finally cleanup 견고.
- 위험:
  - SRP 위반.
  - cancellation 변형.
  - notification text i18n 누락.
- 개선 권고:
  1. `Holder` 를 별도 `TranscribeStateRepository` 로 분리.
  2. DB status update 를 Pipeline 직후 `emit(PipelineEvent.Done)` 안에서 처리 → service 는 알림만.
  3. `catch (t: Throwable)` 에서 cancellation 분리.
  4. notification text → `getString(R.string.notif_transcribing)` 등 i18n.
- 재읽기 권고 레벨: 3 (Level 3 면 WorkManager + Hilt service inject 검토 가치)

**Cross-references**
- 호출처: `<service android:name=".service.TranscribeForegroundService">` (AndroidManifest)
- 도메인: `whisper/Pipeline.kt`
- DB: `db/AppDatabase.kt`
- iOS counterpart: `ios/.../Core/BackgroundTask.swift` (`BGProcessingTask`) — Android FGS 가 iOS BG 보다 훨씬 강력 (잠금화면도 진행). iOS 는 30 sec 한정.
