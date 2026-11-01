package com.clearcap.transcript

/**
 * Turns the stream of live engine events into a clean list of segments.
 *
 *  - [onPartial] sets the provisional (still changing) text; it never enters the segment list.
 *  - [onFinal] adds a finished segment unless it is a duplicate of a recent one.
 *
 * Duplicate rules (checked against the last few segments that overlap in time or follow closely):
 *  1. same words as a recent segment -> dropped
 *  2. new text is a run of words already inside a recent, overlapping segment -> dropped
 *  3. a recent overlapping segment is a run inside the new text -> that segment is extended (replaced)
 */
class CaptionReconciler(private val lookBack: Int = 4, private val closeMs: Long = 6_000) {
    private val list = ArrayList<Segment>()

    var provisional: String = ""
        private set

    val segments: List<Segment> get() = list.toList()

    fun onPartial(text: String) { provisional = text.trim() }

    /** Returns the segment that now represents this text, or null when it was dropped as a duplicate / empty. */
    fun onFinal(text: String, startMs: Long, endMs: Long, language: String? = null, rawText: String? = null): Segment? {
        provisional = ""
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val words = TextKey.words(clean)
        if (words.isEmpty()) return null
        val end = maxOf(endMs, startMs)

        val from = maxOf(0, list.size - lookBack)
        for (i in list.size - 1 downTo from) {
            val prev = list[i]
            val near = overlaps(prev, startMs, end) || startMs - prev.endMs in 0..closeMs
            if (!near) continue
            val pw = TextKey.words(prev.liveText)
            if (pw == words) return null                                    // rule 1
            val overlapping = overlaps(prev, startMs, end)
            if (overlapping && TextKey.containsRun(pw, words)) return null // rule 2
            if (overlapping && TextKey.containsRun(words, pw)) {           // rule 3
                val merged = prev.copy(startMs = minOf(prev.startMs, startMs), endMs = maxOf(prev.endMs, end), liveText = clean, rawText = rawText ?: prev.rawText)
                list[i] = merged
                return merged
            }
        }
        val seg = Segment(list.size, startMs, end, clean, null, language, rawText)
        list.add(seg)
        return seg
    }

    fun clear() { list.clear(); provisional = "" }

    private fun overlaps(s: Segment, start: Long, end: Long): Boolean = start < s.endMs && end > s.startMs
}
