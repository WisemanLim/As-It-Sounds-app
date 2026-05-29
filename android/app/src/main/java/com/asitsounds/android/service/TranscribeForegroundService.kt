package com.asitsounds.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.asitsounds.android.MainActivity
import com.asitsounds.android.db.AppDatabase
import com.asitsounds.android.db.FileStatus
import com.asitsounds.android.whisper.Pipeline
import com.asitsounds.android.whisper.PipelineEvent
import com.asitsounds.android.whisper.PipelineSegment
import com.asitsounds.android.whisper.TranslatorChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service for long-running transcription. Mirrors the iOS
 * BGProcessingTask: holds a notification so Android doesn't kill us, runs the
 * standard [Pipeline.run] flow, and writes results to the DB.
 *
 * `foregroundServiceType="mediaProcessing"` matches the manifest declaration
 * and is the only valid type for "background ASR" on API 34+.
 *
 * State is exposed via [Holder] so the Compose UI can observe progress even
 * after the user backgrounds the app.
 */
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

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transcription",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        supervisor.cancel()
    }

    // ─── Public API ────────────────────────────────────────────────────────

    object Holder {
        internal val _state = MutableStateFlow<RunState>(RunState.Idle)
        val state: StateFlow<RunState> = _state.asStateFlow()
    }

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

    companion object {
        const val ACTION_RUN = "com.asitsounds.action.RUN"
        const val ACTION_STOP = "com.asitsounds.action.STOP"
        const val CHANNEL_ID = "transcribe"
        const val NOTIF_ID = 1001
    }
}

private fun PipelineEvent.toRunState(): TranscribeForegroundService.RunState = when (this) {
    PipelineEvent.Decoding         -> TranscribeForegroundService.RunState.Active("decoding", 0, 0)
    PipelineEvent.Transcribing     -> TranscribeForegroundService.RunState.Active("transcribing", 0, 0)
    PipelineEvent.Transliterating  -> TranscribeForegroundService.RunState.Active("translit", 0, 0)
    is PipelineEvent.Translating   -> TranscribeForegroundService.RunState.Active("translating", current, total)
    is PipelineEvent.Done          -> TranscribeForegroundService.RunState.Done(segments.size)
    is PipelineEvent.Failed        -> TranscribeForegroundService.RunState.Active("failed: ${message.take(40)}", 0, 0)
}
