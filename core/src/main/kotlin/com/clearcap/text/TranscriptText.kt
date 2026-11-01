package com.clearcap.text

/** A piece of transcript with its place on the recording timeline (ms from the start). */
class Timed(val startMs: Long, val endMs: Long, val text: String)

/** Turns engine output into readable text. Pure Kotlin, unit-tested. */
object TranscriptText {

    private val WS = Regex("\\s+")
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([,.;:!?।])")
    private val LONE_I = Regex("\\bi\\b(?=[\\s',.!?]|$)")

    /**
     * The streaming engine writes ALL CAPS without punctuation ("GOD AS A DIRECT CONSEQUENCE...").
     * Convert that to normal sentence case; text that already has mixed case (Parakeet, Whisper) is left alone.
     */
    fun casing(raw: String): String {
        val t = WS.replace(raw.trim(), " ")
        if (t.isEmpty()) return ""
        val letters = t.count { it.isLetter() }
        val upper = t.count { it.isUpperCase() }
        val shouting = letters >= 3 && upper.toFloat() / letters >= 0.7f
        return sentenceCase(if (shouting) t.lowercase() else t)
    }

    /** Capital after . ! ? and for the pronoun "I". */
    fun sentenceCase(s: String): String {
        val sb = StringBuilder(s.length)
        var up = true
        for (c in s) {
            if (up && c.isLetter()) { sb.append(c.uppercaseChar()); up = false }
            else {
                sb.append(c)
                if (c == '.' || c == '!' || c == '?' || c == '।') up = true
                else if (c.isLetterOrDigit()) up = false
            }
        }
        return LONE_I.replace(sb.toString(), "I")
    }

    private fun terminate(s: String): String {
        if (s.isEmpty() || s.last() in ".!?।…") return s
        return s + if (s.any { it.code in 0x0900..0x097F }) "।" else "."
    }

    /** One live sentence: casing, terminal punctuation, then the user's dictionary spelling. */
    fun liveSentence(raw: String, corrector: VocabularyCorrector): String {
        val t = casing(raw)
        if (t.isEmpty()) return ""
        return corrector.correct(terminate(SPACE_BEFORE_PUNCT.replace(t, "$1")))
    }

    /** Joins timed pieces in order; a pause longer than [paragraphGapMs] starts a new paragraph. */
    fun join(parts: List<Timed>, paragraphGapMs: Long = 4_000): String {
        val sb = StringBuilder()
        var prev = ""
        var prevEnd = Long.MIN_VALUE
        for (p in parts.sortedBy { it.startMs }) {
            val t = p.text.trim()
            if (t.isEmpty() || t.equals(prev, ignoreCase = true)) continue
            if (sb.isNotEmpty()) sb.append(if (p.startMs - prevEnd > paragraphGapMs) "\n\n" else " ")
            sb.append(t)
            prev = t
            prevEnd = p.endMs
        }
        return sb.toString()
    }

    fun wordCount(text: String): Int = text.split(WS).count { it.isNotBlank() }
}
