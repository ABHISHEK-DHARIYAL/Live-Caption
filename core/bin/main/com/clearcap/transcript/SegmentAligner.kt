package com.clearcap.transcript

/**
 * Aligns second-pass (refined) pieces with the live segments by TIME, then decides per group whether the
 * refined text is safe to use. The live text is never modified.
 *
 * Live and refined boundaries rarely match exactly (the streaming engine ends a sentence on a 0.9 s pause, the
 * speech detector on 0.5 s), so pieces that overlap are grouped and compared as a whole.
 */
object SegmentAligner {
    class Piece(val startMs: Long, val endMs: Long, val text: String)

    data class Result(val segments: List<Segment>, val accepted: Int, val rejected: Int, val refinedOnly: Int)

    fun merge(live: List<Segment>, refined: List<Piece>, minOverlapMs: Long = 150): Result {
        val l = live.sortedBy { it.startMs }
        val r = refined.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        val n = l.size + r.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in l.indices) for (j in r.indices) {
            if (r[j].startMs >= l[i].endMs + minOverlapMs) break
            val ov = minOf(l[i].endMs, r[j].endMs) - maxOf(l[i].startMs, r[j].startMs)
            val shorter = minOf(l[i].endMs - l[i].startMs, r[j].endMs - r[j].startMs).coerceAtLeast(1)
            if (ov >= minOf(minOverlapMs, shorter / 2)) union(i, l.size + j)
        }

        val groups = LinkedHashMap<Int, Pair<ArrayList<Segment>, ArrayList<Piece>>>()
        for (i in l.indices) groups.getOrPut(find(i)) { ArrayList<Segment>() to ArrayList<Piece>() }.first.add(l[i])
        for (j in r.indices) groups.getOrPut(find(l.size + j)) { ArrayList<Segment>() to ArrayList<Piece>() }.second.add(r[j])

        var accepted = 0; var rejected = 0; var refinedOnly = 0
        val out = groups.values.map { (ls, rs) ->
            val start = minOf(ls.minOfOrNull { it.startMs } ?: Long.MAX_VALUE, rs.minOfOrNull { it.startMs } ?: Long.MAX_VALUE)
            val end = maxOf(ls.maxOfOrNull { it.endMs } ?: 0L, rs.maxOfOrNull { it.endMs } ?: 0L)
            val liveText = ls.joinToString(" ") { it.liveText.trim() }.trim()
            val refinedText = rs.joinToString(" ") { it.text.trim() }.trim()
            var use: String? = null
            if (rs.isNotEmpty()) {
                if (ls.isEmpty()) refinedOnly++
                val d = RefinementPolicy.evaluate(liveText, refinedText)
                if (d.accept) { use = refinedText; accepted++ } else rejected++
            }
            Segment(0, start, end, liveText, use, ls.firstOrNull()?.language, ls.mapNotNull { it.rawText }.joinToString(" ").ifBlank { null })
        }.sortedBy { it.startMs }.mapIndexed { i, s -> s.copy(index = i) }
        return Result(out, accepted, rejected, refinedOnly)
    }
}

/** Safety checks on a refined text. They only reject; they never rewrite either text. */
object RefinementPolicy {
    data class Decision(val accept: Boolean, val reason: String)

    fun evaluate(liveText: String, refinedText: String): Decision {
        val rw = TextKey.words(refinedText)
        if (rw.isEmpty()) return Decision(false, "refined text is empty")
        if (hasLoop(rw)) return Decision(false, "refined text repeats a phrase (possible hallucination)")
        val lw = TextKey.words(liveText)
        if (lw.isEmpty()) return Decision(true, "no live text to compare")
        if (lw.size >= 4 && rw.size > lw.size * 3 + 8) return Decision(false, "refined text is far longer than the live text")
        if (lw.size >= 8 && rw.size * 4 < lw.size) return Decision(false, "refined text is far shorter than the live text (speech may be missing)")
        return Decision(true, "ok")
    }

    /** The same 3-word run four or more times. */
    private fun hasLoop(w: List<String>): Boolean {
        if (w.size < 12) return false
        val counts = HashMap<String, Int>()
        for (i in 0..w.size - 3) {
            val k = w[i] + " " + w[i + 1] + " " + w[i + 2]
            val c = (counts[k] ?: 0) + 1
            if (c >= 4) return true
            counts[k] = c
        }
        return false
    }
}
