package com.clearcap.transcript

/** Lifecycle of a recording session. Persisted so a killed process can be recognised at the next launch. */
enum class SessionStatus { RECORDING, FINALIZING, COMPLETE, INTERRUPTED, FAILED }

/** Which text the app shows. LIVE is always the untouched original recognizer output. */
enum class TextVersion { LIVE, REFINED, EDITED }

/**
 * One stretch of speech on the recording timeline (ms from the start of the audio).
 * [liveText] is the original live result and is never overwritten; [refinedText] is the second-pass result.
 */
data class Segment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val liveText: String,
    val refinedText: String? = null,
    val language: String? = null,
    /** Exact recognizer output before any filtering, casing or vocabulary change. Never edited. */
    val rawText: String? = null,
) {
    /** Text for a version. A segment that was not refined falls back to its live text. */
    fun textFor(version: TextVersion): String = when (version) {
        TextVersion.LIVE -> liveText
        TextVersion.REFINED, TextVersion.EDITED -> refinedText?.takeIf { it.isNotBlank() } ?: liveText
    }
}

/** Language the user asked for. Transcription never translates: the spoken language is written as spoken. */
enum class LanguageMode(val code: String, val label: String) {
    AUTO("auto", "Auto-detect"),
    ENGLISH("en", "English"),
    HINDI("hi", "Hindi"),
    HINGLISH("hinglish", "Hinglish (Hindi + English)");

    /** Value for Whisper's language field. Empty means "detect". Hinglish is decoded as Hindi (see docs). */
    val whisperLanguage: String
        get() = when (this) {
            AUTO -> ""
            ENGLISH -> "en"
            HINDI, HINGLISH -> "hi"
        }

    companion object {
        fun fromCode(code: String?): LanguageMode = values().firstOrNull { it.code == code } ?: AUTO
    }
}

/** Combines segments into the text shown, copied and exported. */
object TranscriptAssembler {
    fun text(segments: List<Segment>, version: TextVersion, editedText: String? = null, paragraphGapMs: Long = 4_000): String {
        if (version == TextVersion.EDITED && !editedText.isNullOrBlank()) return editedText
        return com.clearcap.text.TranscriptText.join(
            segments.sortedBy { it.startMs }.map { com.clearcap.text.Timed(it.startMs, it.endMs, it.textFor(version)) },
            paragraphGapMs,
        )
    }
}
