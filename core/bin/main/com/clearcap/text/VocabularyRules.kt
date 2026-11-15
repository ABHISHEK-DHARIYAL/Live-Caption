package com.clearcap.text

import java.text.Normalizer

/**
 * One user vocabulary entry. [term] is the preferred spelling. [spokenAs] lists other spellings the recognizer
 * is known to produce for it (for example a Roman or Devanagari spelling), which are replaced by [term].
 */
data class VocabEntry(val term: String, val spokenAs: List<String> = emptyList(), val language: String? = null)

/**
 * Exact, whole-word replacements from the user's own list. Unlike [VocabularyCorrector] it never guesses:
 * a word is replaced only if the user wrote it down as an alias. Works with Devanagari and Latin text.
 * Matching is case-insensitive and respects word boundaries (letters, combining marks and digits are word characters).
 */
class VocabularyRules(entries: List<VocabEntry>) {
    private val map: Map<String, String>
    private val regex: Regex?

    init {
        val m = LinkedHashMap<String, String>()
        for (e in entries) {
            val term = e.term.trim()
            if (term.isEmpty()) continue
            for (a in e.spokenAs) {
                val k = norm(a)
                if (k.isNotEmpty() && k != norm(term)) m.putIfAbsent(k, term)
            }
        }
        map = m
        regex = if (m.isEmpty()) null else {
            val alts = m.keys.sortedByDescending { it.length }.joinToString("|") { key ->
                key.split(' ').joinToString("\\s+") { Regex.escape(it) }
            }
            Regex("(?<![\\p{L}\\p{M}\\p{N}])(?:$alts)(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
        }
    }

    val isEmpty: Boolean get() = map.isEmpty()

    fun apply(text: String): String {
        val r = regex ?: return text
        if (text.isBlank()) return text
        return r.replace(text) { map[norm(it.value)] ?: it.value }
    }

    private fun norm(s: String): String =
        Normalizer.normalize(s.trim(), Normalizer.Form.NFC).lowercase().replace(Regex("\\s+"), " ")
}
