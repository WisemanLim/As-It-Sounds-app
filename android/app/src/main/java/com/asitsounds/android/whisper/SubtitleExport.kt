package com.asitsounds.android.whisper

import android.content.Context
import android.net.Uri
import java.io.OutputStreamWriter

enum class SubtitleFormat(val ext: String, val mime: String) {
    SRT("srt", "application/x-subrip"),
    VTT("vtt", "text/vtt"),
    TXT("txt", "text/plain");

    fun render(segments: List<PipelineSegment>): String = when (this) {
        SRT -> renderSrt(segments)
        VTT -> renderVtt(segments)
        TXT -> renderTxt(segments)
    }

    private fun renderSrt(s: List<PipelineSegment>): String = buildString {
        s.forEachIndexed { i, seg ->
            appendLine(i + 1)
            appendLine("${tc(seg.startMs, true)} --> ${tc(seg.endMs, true)}")
            appendLine(body(seg))
            appendLine()
        }
    }

    private fun renderVtt(s: List<PipelineSegment>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        s.forEach { seg ->
            appendLine("${tc(seg.startMs, false)} --> ${tc(seg.endMs, false)}")
            appendLine(body(seg))
            appendLine()
        }
    }

    private fun renderTxt(s: List<PipelineSegment>): String = s.joinToString("\n\n") { seg ->
        "[${tc(seg.startMs, false)} — ${tc(seg.endMs, false)}]\n${body(seg)}"
    }

    private fun body(s: PipelineSegment): String {
        val lines = ArrayList<String>(3)
        lines += s.textEn
        if (s.textHangul.isNotEmpty()) lines += s.textHangul
        if (s.textKo.isNotEmpty())     lines += s.textKo
        return lines.joinToString("\n")
    }

    private fun tc(ms: Long, srt: Boolean): String {
        val m = maxOf(0L, ms)
        val hh = m / 3_600_000
        val mm = (m / 60_000) % 60
        val ss = (m / 1_000) % 60
        val mmm = m % 1_000
        val sep = if (srt) "," else "."
        return "%02d:%02d:%02d%s%03d".format(hh, mm, ss, sep, mmm)
    }
}

object SubtitleExport {
    fun writeTo(ctx: Context, uri: Uri, format: SubtitleFormat, segments: List<PipelineSegment>) {
        val text = format.render(segments)
        ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { it.write(text) }
        }
    }
}
