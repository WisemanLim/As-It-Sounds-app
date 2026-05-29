package com.asitsounds.android.whisper

import android.content.Context
import android.net.Uri
import com.asitsounds.android.db.AppDatabase
import com.asitsounds.android.db.SegmentEntity
import com.asitsounds.core.transliterate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class PipelineSegment(
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val textEn: String,
    val textHangul: String,
    val textKo: String = "",
)

sealed interface PipelineEvent {
    data object Decoding : PipelineEvent
    data object Transcribing : PipelineEvent
    data object Transliterating : PipelineEvent
    data class Translating(val current: Int, val total: Int) : PipelineEvent
    data class Done(val segments: List<PipelineSegment>) : PipelineEvent
    data class Failed(val message: String) : PipelineEvent
}

/**
 * Foreground-only pipeline runner mirroring the iOS counterpart. The whole
 * decode + transcribe + translit chain runs on [Dispatchers.IO] in one shot;
 * Phase 1B keeps it simple — Foreground Service + WorkManager for long
 * recordings is a later step.
 */
object Pipeline {

    fun run(
        context: Context,
        modelPath: String,
        audioUri: Uri,
        language: String? = null,
        translator: Translator = DisabledTranslator(),
        persistFileId: String? = null,
    ): Flow<PipelineEvent> = flow {
        emit(PipelineEvent.Decoding)
        val pcm = try {
            AudioDecoder.decodeToPCM16k(context, audioUri)
        } catch (t: Throwable) {
            emit(PipelineEvent.Failed("decode: ${t.message ?: t::class.simpleName}"))
            return@flow
        }

        emit(PipelineEvent.Transcribing)
        val handle = WhisperNative.nativeInitFromFile(modelPath, useGpu = true)
        if (handle == 0L) {
            emit(PipelineEvent.Failed("whisper_init_from_file failed"))
            return@flow
        }

        val raw = try {
            WhisperNative.transcribe(handle, pcm, language, nThreads = 4)
        } catch (t: Throwable) {
            WhisperNative.nativeFree(handle)
            emit(PipelineEvent.Failed("transcribe: ${t.message ?: t::class.simpleName}"))
            return@flow
        } finally {
            WhisperNative.nativeFree(handle)
        }

        emit(PipelineEvent.Transliterating)
        val transliterated = raw.mapIndexed { i, seg ->
            val trimmed = seg.text.trim()
            PipelineSegment(
                idx = i,
                startMs = seg.startMs,
                endMs = seg.endMs,
                textEn = trimmed,
                textHangul = transliterate(trimmed),
            )
        }

        val out: List<PipelineSegment> = if (translator is DisabledTranslator) {
            transliterated
        } else {
            val acc = transliterated.toMutableList()
            for (i in acc.indices) {
                emit(PipelineEvent.Translating(current = i + 1, total = acc.size))
                val ko = try {
                    translator.translate(acc[i].textEn, "en", "ko")
                } catch (t: Throwable) {
                    "(translate failed)"
                }
                acc[i] = acc[i].copy(textKo = ko)
            }
            acc
        }

        if (persistFileId != null) {
            val dao = AppDatabase.get(context).segmentDao()
            dao.clearForFile(persistFileId)
            dao.insertAll(out.map {
                SegmentEntity(
                    id = UUID.randomUUID().toString(),
                    fileId = persistFileId,
                    idx = it.idx,
                    startMs = it.startMs,
                    endMs = it.endMs,
                    textEn = it.textEn,
                    textHangul = it.textHangul,
                    textKo = it.textKo.ifEmpty { null },
                )
            })
        }

        emit(PipelineEvent.Done(out))
    }.flowOn(Dispatchers.IO)
}
