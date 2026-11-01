package com.clearcap.transcript

import com.clearcap.util.MiniJson
import java.util.Locale

/** Everything an exporter needs. [segments] carry the text already chosen for the active version. */
data class ExportDoc(
    val title: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val language: String,
    val source: String,
    val modelInfo: String,
    val version: TextVersion,
    val fullText: String,
    val segments: List<ExportSegment>,
)

data class ExportSegment(val startMs: Long, val endMs: Long, val text: String)

enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    TXT("txt", "text/plain", "Plain text"),
    TXT_TIMESTAMPS("txt", "text/plain", "Text with timestamps"),
    MARKDOWN("md", "text/markdown", "Markdown"),
    SRT("srt", "application/x-subrip", "SubRip subtitles (.srt)"),
    VTT("vtt", "text/vtt", "WebVTT subtitles"),
    JSON("json", "application/json", "JSON with timestamps"),
}

object TranscriptExporter {
    fun export(doc: ExportDoc, format: ExportFormat, isoDate: (Long) -> String = ::defaultIso): String = when (format) {
        ExportFormat.TXT -> doc.fullText.trim() + "\n"
        ExportFormat.TXT_TIMESTAMPS -> doc.segments.joinToString("\n") { "[${clock(it.startMs)}] ${it.text.trim()}" } + "\n"
        ExportFormat.MARKDOWN -> markdown(doc, isoDate)
        ExportFormat.SRT -> subtitles(doc.segments, srt = true)
        ExportFormat.VTT -> "WEBVTT\n\n" + subtitles(doc.segments, srt = false)
        ExportFormat.JSON -> json(doc, isoDate)
    }

    fun fileName(title: String, format: ExportFormat): String {
        val base = title.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").replace(Regex("_+"), "_").trim('_').take(60).ifEmpty { "transcript" }
        return "$base.${format.extension}"
    }

    private fun markdown(d: ExportDoc, iso: (Long) -> String): String = buildString {
        append("# ").append(d.title).append("\n\n")
        append("- Recorded: ").append(iso(d.startedAtMs)).append('\n')
        append("- Duration: ").append(clock(d.durationMs)).append('\n')
        append("- Language setting: ").append(d.language).append('\n')
        append("- Source: ").append(d.source).append('\n')
        append("- Text version: ").append(d.version.name.lowercase(Locale.ROOT)).append("\n\n")
        append(d.fullText.trim()).append('\n')
    }

    private fun json(d: ExportDoc, iso: (Long) -> String): String = MiniJson.writePretty(
        linkedMapOf(
            "title" to d.title,
            "recordedAt" to iso(d.startedAtMs),
            "durationMs" to d.durationMs,
            "languageSetting" to d.language,
            "source" to d.source,
            "models" to d.modelInfo,
            "textVersion" to d.version.name,
            "text" to d.fullText,
            "segments" to d.segments.map { linkedMapOf("startMs" to it.startMs, "endMs" to it.endMs, "text" to it.text) },
        ),
    ) + "\n"

    /** SRT/VTT: cues are in time order and never overlap; long lines are wrapped at word boundaries. */
    private fun subtitles(segs: List<ExportSegment>, srt: Boolean): String {
        val sb = StringBuilder()
        var prevEnd = 0L
        var n = 0
        for (s in segs.sortedBy { it.startMs }) {
            val text = s.text.trim()
            if (text.isEmpty()) continue
            val start = maxOf(s.startMs, prevEnd)
            val end = maxOf(s.endMs, start + 500)
            n++
            if (srt) sb.append(n).append('\n')
            sb.append(stamp(start, srt)).append(" --> ").append(stamp(end, srt)).append('\n')
            sb.append(wrap(text, 42)).append("\n\n")
            prevEnd = end
        }
        return sb.toString()
    }

    fun wrap(text: String, width: Int): String {
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in text.split(Regex("\\s+"))) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > width) { lines.add(cur.toString()); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines.joinToString("\n")
    }

    fun clock(ms: Long): String {
        val t = ms / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }

    private fun stamp(ms: Long, srt: Boolean): String {
        val h = ms / 3_600_000; val m = (ms / 60_000) % 60; val s = (ms / 1000) % 60; val milli = ms % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", h, m, s, if (srt) "," else ".", milli)
    }

    private fun defaultIso(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT)
        return f.format(java.util.Date(ms))
    }
}
