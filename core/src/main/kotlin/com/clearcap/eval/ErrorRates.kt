package com.clearcap.eval

import java.text.Normalizer

/** Edit counts between a reference (human-verified) transcript and a hypothesis (recognizer output). */
data class EditStats(val substitutions: Int, val deletions: Int, val insertions: Int, val refLength: Int) {
    val errors: Int get() = substitutions + deletions + insertions
    /** Error rate; can exceed 1.0 when there are many insertions. Null when the reference is empty. */
    val rate: Double? get() = if (refLength == 0) null else errors.toDouble() / refLength
}

/**
 * Word and character error rates. Text is normalised first (Unicode NFC, lower case, punctuation and the Hindi
 * danda removed) so that capitalisation and punctuation choices are not counted as recognition errors.
 * Script is NOT folded: a Roman "namaste" against a Devanagari reference counts as wrong, which is correct for
 * measuring Hinglish output and is reported as such.
 */
object ErrorRates {
    fun normalize(text: String): String {
        val n = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        val sb = StringBuilder(n.length)
        var space = true
        for (c in n) {
            val keep = c.isLetterOrDigit() || Character.getType(c).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
            }
            if (keep) { sb.append(c); space = false } else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trim()
    }

    fun wer(reference: String, hypothesis: String): EditStats =
        edit(words(reference), words(hypothesis))

    fun cer(reference: String, hypothesis: String): EditStats =
        edit(chars(reference), chars(hypothesis))

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Characters with spaces removed, so word-spacing differences in Hindi do not dominate the result. */
    fun chars(text: String): List<String> = normalize(text).filter { it != ' ' }.map { it.toString() }

    /** Fraction of characters in the Devanagari block among letters; useful to report which script was produced. */
    fun devanagariShare(text: String): Double {
        val letters = text.filter { it.isLetter() || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() } }
        if (letters.isEmpty()) return 0.0
        return letters.count { it.code in 0x0900..0x097F }.toDouble() / letters.length
    }

    /** Levenshtein distance with a split into substitutions, deletions and insertions. */
    fun <T> edit(ref: List<T>, hyp: List<T>): EditStats {
        val n = ref.size; val m = hyp.size
        // cost[i][j] over two rolling rows, plus the operation counts along the best path
        var prev = IntArray(m + 1) { it }
        var prevS = IntArray(m + 1); var prevD = IntArray(m + 1); var prevI = IntArray(m + 1) { it }
        for (i in 1..n) {
            val cur = IntArray(m + 1); val curS = IntArray(m + 1); val curD = IntArray(m + 1); val curI = IntArray(m + 1)
            cur[0] = i; curD[0] = i
            for (j in 1..m) {
                val sub = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                val del = prev[j] + 1
                val ins = cur[j - 1] + 1
                when {
                    sub <= del && sub <= ins -> {
                        cur[j] = sub
                        val isSub = ref[i - 1] != hyp[j - 1]
                        curS[j] = prevS[j - 1] + if (isSub) 1 else 0; curD[j] = prevD[j - 1]; curI[j] = prevI[j - 1]
                    }
                    del <= ins -> { cur[j] = del; curS[j] = prevS[j]; curD[j] = prevD[j] + 1; curI[j] = prevI[j] }
                    else -> { cur[j] = ins; curS[j] = curS[j - 1]; curD[j] = curD[j - 1]; curI[j] = curI[j - 1] + 1 }
                }
            }
            prev = cur; prevS = curS; prevD = curD; prevI = curI
        }
        return EditStats(prevS[m], prevD[m], prevI[m], n)
    }
}
