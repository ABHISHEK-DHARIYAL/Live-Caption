package com.lecturecaption.app.stt

/**
 * Fixes words the speech model could not know. Vosk models have a fixed vocabulary, so a new
 * word ("Kubernetes", a professor's name, a product name) comes out as the closest-sounding
 * known words ("cuber nets"). Given the user's word list, this finds 1-3 recognized words that
 * sound/spell like a listed term and swaps in the exact term (with the user's spelling/casing).
 *
 * Pure Kotlin (no Android deps) so it is unit-testable. Deliberately conservative: very short
 * terms only get a casing fix, never a fuzzy swap, so ordinary words are not rewritten.
 */
class VocabularyCorrector(terms: List<String>) {

    private class Entry(val original: String) {
        val compact: String = compactOf(original)
        val skeleton: String = skeletonOf(compact)
    }

    private val entries: List<Entry> =
        terms.map { it.trim() }.filter { it.isNotEmpty() }.map { Entry(it) }.filter { it.compact.isNotEmpty() }

    val isEmpty: Boolean get() = entries.isEmpty()

    /** A word plus the exact whitespace that follows it, so line breaks survive a correction. */
    private class Tok(val word: String, val gap: String)

    private fun tokenize(text: String): Pair<String, List<Tok>> {
        val lead = text.takeWhile { it.isWhitespace() }
        val toks = WORD.findAll(text.substring(lead.length)).map { Tok(it.groupValues[1], it.groupValues[2]) }.toList()
        return lead to toks
    }

    fun correct(text: String): String {
        if (entries.isEmpty() || text.isBlank()) return text
        val (lead0, tokens) = tokenize(text)
        val out = StringBuilder(lead0)
        var i = 0
        while (i < tokens.size) {
            var replaced = false
            for (n in 1..MAX_SPAN) { // shortest span first, so neighbouring words are not swallowed
                if (i + n > tokens.size) continue
                val span = tokens.subList(i, i + n)
                // Punctuation inside a multi-word span ("navia, stokes") means it is not one term.
                if (n > 1 && span.dropLast(1).any { t -> t.word.last().let { !it.isLetterOrDigit() } || t.gap.contains('\n') }) continue
                val cores = span.map { coreOf(it.word) }
                if (cores.any { it.isEmpty() }) continue
                val compact = cores.joinToString("").lowercase()
                val best = bestMatch(compact, n) ?: continue
                val lead = span.first().word.takeWhile { !it.isLetterOrDigit() }
                val trail = span.last().word.takeLastWhile { !it.isLetterOrDigit() }
                out.append(lead).append(best.original).append(trail).append(span.last().gap)
                i += n
                replaced = true
                break
            }
            if (!replaced) { out.append(tokens[i].word).append(tokens[i].gap); i++ }
        }
        return out.toString()
    }

    private fun bestMatch(compact: String, spanWords: Int): Entry? {
        var best: Entry? = null
        var bestDist = Int.MAX_VALUE
        for (e in entries) {
            val len = e.compact.length
            if (kotlin.math.abs(compact.length - len) > 3) continue
            if (compact == e.compact) return e // exact match: just fixes spelling/casing
            if (len < MIN_FUZZY_LEN) continue
            val dist = levenshtein(compact, e.compact, 4)
            val maxDist = when { len <= 5 -> 1; len <= 8 -> 2; else -> 3 }
            val skel = skeletonOf(compact)
            val exactSound = skel == e.skeleton
            // Multi-word speech errors often differ by one consonant ("navia stokes" / "navier stokes"),
            // so a one-letter skeleton difference also counts as "sounds alike" - but only for longer
            // terms, and only with a tighter spelling limit (see below).
            val closeSound = !exactSound && spanWords > 1 && e.skeleton.length >= 6 &&
                levenshtein(skel, e.skeleton, 1) <= 1
            val sameSound = exactSound || closeSound
            // Gluing 2-3 spoken words into one term is how "cuber nets" -> Kubernetes works, but it is
            // also how correct words get swallowed. For multi-word spans the sound pattern MUST match;
            // closeness of spelling alone is not enough.
            val ok = if (spanWords > 1) dist <= (if (exactSound) maxDist else minOf(maxDist, 2)) && sameSound && len >= 5
                     else dist <= maxDist && (sameSound && len >= 5 || compact.first() == e.compact.first())
            if (ok && dist < bestDist) { best = e; bestDist = dist }
        }
        return best
    }

    private fun coreOf(token: String): String = token.filter { it.isLetterOrDigit() }

    private fun levenshtein(a: String, b: String, cutoff: Int): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > cutoff) return cutoff + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    companion object {
        private val WORD = Regex("(\\S+)(\\s*)")
        private const val MAX_SPAN = 3
        private const val MIN_FUZZY_LEN = 4

        internal fun compactOf(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

        /** Rough consonant skeleton: "kubernetes", "cuber nets" and "cuber nettis" all collapse alike. */
        internal fun skeletonOf(compact: String): String {
            if (compact.isEmpty()) return ""
            var s = compact
                .replace("ph", "f").replace("ck", "k").replace("sch", "sk")
                .replace("qu", "k").replace("x", "ks").replace("q", "k").replace("z", "s")
            if (s.startsWith("kn") || s.startsWith("gn") || s.startsWith("pn")) s = s.substring(1)
            if (s.startsWith("wr")) s = s.substring(1)
            val sb = StringBuilder()
            for ((idx, ch) in s.withIndex()) {
                val c = when (ch) {
                    'c' -> if (idx + 1 < s.length && s[idx + 1] in "eiy") 's' else 'k'
                    else -> ch
                }
                val isVowelLike = c in "aeiouyhw"
                if (isVowelLike && idx != 0) continue
                if (sb.isNotEmpty() && sb.last() == c) continue
                sb.append(c)
            }
            return sb.toString()
        }
    }
}
