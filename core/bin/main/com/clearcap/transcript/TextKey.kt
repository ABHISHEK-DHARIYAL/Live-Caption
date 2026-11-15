package com.clearcap.transcript

import java.text.Normalizer

/** Comparison keys for text: used to find duplicates, never shown to the user. */
object TextKey {
    /** NFC, lower case, letters/digits/marks only (Devanagari kept), single spaces. */
    fun of(text: String): String {
        val n = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        val sb = StringBuilder(n.length)
        var space = true
        for (c in n) {
            val keep = c.isLetterOrDigit() || Character.getType(c).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
            }
            if (keep) { sb.append(c); space = false }
            else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trim()
    }

    fun words(text: String): List<String> = of(text).split(' ').filter { it.isNotEmpty() }

    /** True when [small]'s words appear as one run inside [big]'s words. */
    fun containsRun(big: List<String>, small: List<String>): Boolean {
        if (small.isEmpty()) return true
        if (small.size > big.size) return false
        for (i in 0..big.size - small.size) {
            var ok = true
            for (j in small.indices) if (big[i + j] != small[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }
}
