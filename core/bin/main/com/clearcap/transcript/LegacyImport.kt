package com.clearcap.transcript

import com.clearcap.util.MiniJson

/** A transcript saved by the earlier Clearcap (one JSON file, no segment timing, no audio). */
data class LegacyTranscript(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val live: String,
    val accurate: String?,
    val edited: String?,
    val notes: String?,
    val source: String,
    val behindMs: Long,
)

object LegacyImport {
    /** Returns null when the file is not a usable transcript (missing id, invalid JSON). */
    fun parse(json: String): LegacyTranscript? = try {
        val m = MiniJson.parseObject(json)
        fun str(k: String): String? = (m[k] as? String)
        fun long(k: String): Long = (m[k] as? Number)?.toLong() ?: 0L
        val id = str("id")?.takeIf { it.isNotBlank() }
        if (id == null) null else LegacyTranscript(
            id = id, title = str("title")?.ifBlank { null } ?: "Recording", createdAt = long("createdAt"), durationMs = long("durationMs"),
            live = str("live") ?: "", accurate = str("accurate")?.ifBlank { null }, edited = str("edited")?.ifBlank { null },
            notes = str("notes")?.ifBlank { null }, source = str("source") ?: "system", behindMs = long("behindMs"),
        )
    } catch (e: Exception) { null }

    /** The earlier app saved one text per transcript: show the most refined one it had. */
    fun initialVersion(t: LegacyTranscript): TextVersion = when {
        t.edited != null -> TextVersion.EDITED
        t.accurate != null -> TextVersion.REFINED
        else -> TextVersion.LIVE
    }

    /** One segment spanning the whole recording: the old format has no timestamps, and none are invented. */
    fun toSegment(t: LegacyTranscript): Segment =
        Segment(0, 0, maxOf(t.durationMs, 1), t.live, t.accurate, null, t.live)
}
