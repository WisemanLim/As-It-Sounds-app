package com.asitsounds.android.whisper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

class ModelManager(private val context: Context) {

    private val modelsDir: File by lazy {
        File(context.filesDir, "models").also { it.mkdirs() }
    }

    fun localPathIfExists(model: WhisperModel): String? {
        val f = File(modelsDir, model.fileName)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

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
