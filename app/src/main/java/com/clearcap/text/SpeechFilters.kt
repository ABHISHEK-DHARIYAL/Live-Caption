package com.clearcap.text

import kotlin.math.abs
import kotlin.math.sqrt

/** One cleaned-up piece of the lecture produced by an accurate pass. Times are ms from the start of audio. */
class RefinedSegment(val startMs: Long, val endMs: Long, val text: String)

/**
 * Pure (no Android / native code) guards shared by the Whisper and Online passes: reject noise
 * before it reaches a recognizer, and remove the stock phrases Whisper-style models invent on
 * silence, music and noise. Unit-tested in SpeechFiltersTest.
 */
object SpeechFilters {

    private const val SAMPLE_RATE = 16_000
    private const val FRAME = 320                // 20 ms
    private const val MIN_RMS = 0.01f            // about -40 dBFS
    private const val MIN_PEAK = 0.04f           // about -28 dBFS
    private const val ACTIVE_LEVEL = 0.015f
    private const val MIN_ACTIVE_FRACTION = 0.12f
    const val SHORT_CLIP_MS = 3_000L

    fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var sum = 0.0
        for (v in x) sum += v * v
        return sqrt(sum / x.size).toFloat()
    }

    private fun peak(x: FloatArray): Float {
        var p = 0f
        for (v in x) p = maxOf(p, abs(v))
        return p
    }

    /** Share of 20 ms frames whose RMS is at least [level]. */
    internal fun activeFraction(x: FloatArray, level: Float): Float {
        val frames = x.size / FRAME
        if (frames == 0) return 0f
        var active = 0
        for (f in 0 until frames) {
            var sum = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) sum += x[i] * x[i]
            if (sqrt(sum / FRAME) >= level) active++
        }
        return active.toFloat() / frames
    }

    /**
     * Energy gate: true only if the segment looks like speech (loud enough overall, has real peaks,
     * and is active in more than a click or two). Hiss, hum and single clicks are rejected.
     */
    fun isSpeechLike(x: FloatArray): Boolean {
        if (x.size < FRAME) return false
        if (rms(x) < MIN_RMS || peak(x) < MIN_PEAK) return false
        return activeFraction(x, ACTIVE_LEVEL) >= MIN_ACTIVE_FRACTION
    }

    /** Non-speech tags Whisper writes for music/noise, e.g. [MUSIC], (applause), [BLANK_AUDIO], and note symbols. */
    private val NON_SPEECH_TAG = Regex(
        "(?i)[\\[(*]\\s*(blank[_ ]audio|no[_ ]speech|music|upbeat music|soft music|applause|laughter|laughs?|silence|noise|inaudible|no audio|sound)\\s*[\\])*]|[♪♫♬♩]+"
    )

    /** Whisper's stock "caption" lines it learned from subtitle files. Never real lecture speech. */
    // A "." followed by a non-space is part of a web address (Amara.org), not the end of the sentence.
    private const val CRED = "(?:[^.!?\\n]|\\.(?=\\S))*"
    private val SUBTITLE_JUNK = Regex(
        "(?i)((subtitles?|subtitled|captions?|captioned|transcribed|translated) (by|from) $CRED[.!?]?|" +
            "amara\\.org$CRED[.!?]?|please subscribe$CRED[.!?]?|like and subscribe$CRED[.!?]?|" +
            "subscribe to (my|our|the) channel$CRED[.!?]?|सब्सक्राइब[^।.!?\\n]*[।.!?]?)"
    )

    /** Polite sign-offs Whisper invents on noise. Only wrong on SHORT clips; fine inside real speech. */
    private val SIGN_OFFS = setOf(
        "you", "thank you", "thanks", "thank you very much", "thanks for watching", "thank you for watching",
        "thanks for listening", "thank you for listening", "see you next time", "see you", "bye", "goodbye", "bye bye",
        "धन्यवाद", "शुक्रिया", "देखने के लिए धन्यवाद",
    )

    private val WS = Regex("\\s+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{M}\\p{N} ]")

    /** Removes invented phrases, tags and loops from one recognized piece. "" means "nothing real was said". */
    fun cleanUp(raw: String, durationMs: Long): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        text = NON_SPEECH_TAG.replace(text, " ")
        text = SUBTITLE_JUNK.replace(text, " ")
        text = WS.replace(text, " ").trim()
        if (text.isEmpty()) return ""
        val core = WS.replace(NOT_WORD.replace(text.lowercase(), ""), " ").trim()
        if (core.isEmpty()) return ""
        if (durationMs < SHORT_CLIP_MS && core in SIGN_OFFS) return ""
        return collapseLoops(text)
    }

    /** A word repeated more than 4 times in a row keeps 4; a 2-4 word phrase repeated 4+ times keeps 2. */
    internal fun collapseLoops(text: String): String {
        var words = text.split(' ').filter { it.isNotEmpty() }
        words = capWordRuns(words)
        for (n in 2..4) words = capPhraseRuns(words, n)
        return words.joinToString(" ")
    }

    private fun capWordRuns(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var run = 0
        for (w in words) {
            if (out.isNotEmpty() && out.last().equals(w, ignoreCase = true)) {
                run++
                if (run >= 4) continue
            } else run = 0
            out.add(w)
        }
        return out
    }

    private fun capPhraseRuns(w: List<String>, n: Int): List<String> {
        val out = ArrayList<String>(w.size)
        var i = 0
        while (i < w.size) {
            var reps = 1
            while (i + (reps + 1) * n <= w.size && samePhrase(w, i, i + reps * n, n)) reps++
            if (reps >= 4) {
                for (j in 0 until 2 * n) out.add(w[i + j])
                i += reps * n
            } else {
                out.add(w[i])
                i++
            }
        }
        return out
    }

    private fun samePhrase(w: List<String>, a: Int, b: Int, n: Int): Boolean {
        for (j in 0 until n) if (!w[a + j].equals(w[b + j], ignoreCase = true)) return false
        return true
    }

    /** Joins pieces separated by a short pause so the saved transcript isn't hundreds of fragments. */
    fun mergeIntoParagraphs(parts: List<RefinedSegment>): List<RefinedSegment> {
        val merged = ArrayList<RefinedSegment>()
        var cur: RefinedSegment? = null
        for (p in parts) {
            val c = cur
            if (c != null && p.startMs - c.endMs < 2_500 && c.text.length < 280) {
                cur = RefinedSegment(c.startMs, p.endMs, c.text + " " + p.text)
            } else {
                if (c != null) merged.add(c)
                cur = p
            }
        }
        cur?.let { merged.add(it) }
        return merged
    }
}
