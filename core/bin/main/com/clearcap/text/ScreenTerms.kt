package com.clearcap.text

/**
 * Picks out the words on a screen (slide titles, code, product and people names) that a speech model is likely to get wrong,
 * so they can be used as a temporary, per-session vocabulary. Pure Kotlin: no Android types.
 *
 * Deliberately selective. Ordinary lowercase words are never returned, because adding them would only give the corrector
 * more chances to rewrite words that were already right. What qualifies:
 *  - CamelCase or mixed-case words ("TensorFlow", "macOS")
 *  - words that mix letters and digits ("EC2", "Python3")
 *  - snake_case words, and hyphenated words that carry a capital or a digit ("Wi-Fi6")
 *  - ALL-CAPS acronyms of 3-6 letters that appear at least twice ("GPU", "CIDR")
 *  - Capitalised words in the middle of a sentence ("Kubernetes"), unless the same word also shows up in lowercase
 */
object ScreenTerms {
    const val MIN_LEN = 4
    const val MAX_LEN = 40
    private val TOKEN = Regex("[\\p{L}\\p{N}]+(?:[_\\-][\\p{L}\\p{N}]+)*")
    private val SENTENCE_END = setOf('.', '!', '?', ':', ';', '•', '\n', '\r', '|', '>')
    private val COMMON_ACRONYMS = setOf(
        "THE", "AND", "FOR", "NOT", "YOU", "ALL", "NEW", "NOW", "OFF", "OUT", "ANY", "ARE", "BUT", "CAN", "HAS", "HOW", "ITS",
        "MAY", "OUR", "ONE", "TWO", "WHO", "WHY", "YES", "NEXT", "BACK", "HOME", "MENU", "OPEN", "SAVE", "EDIT", "VIEW", "HELP",
        "DONE", "SKIP", "STOP", "PLAY", "PAUSE", "CLOSE", "SHARE", "SEND", "MORE", "LESS", "TRUE", "FALSE", "NULL", "NONE",
    )

    /** Distinct candidate terms in [text], most frequent first (ties keep screen order). */
    fun extract(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        class Seen(val display: String, var count: Int, var midSentence: Boolean, val order: Int)
        val seen = LinkedHashMap<String, Seen>()   // keyed by exact spelling
        val lowerForms = HashSet<String>()          // every token that appeared fully lowercase
        var order = 0
        for (m in TOKEN.findAll(text)) {
            val tok = m.value
            if (!isLatin(tok)) continue
            if (tok == tok.lowercase()) lowerForms.add(tok)
            val mid = !startsSentence(text, m.range.first)
            val s = seen[tok]
            if (s == null) seen[tok] = Seen(tok, 1, mid, order++) else { s.count++; if (mid) s.midSentence = true }
        }
        return seen.values
            .filter { accept(it.display, it.count, it.midSentence, lowerForms) }
            .sortedWith(compareByDescending<Seen> { it.count }.thenBy { it.order })
            .map { it.display }
    }

    private fun accept(tok: String, count: Int, mid: Boolean, lowerForms: Set<String>): Boolean {
        if (tok.length < MIN_LEN && !(tok.length == 3 && (isAcronym(tok) || (tok.any { it.isDigit() } && tok.count { it.isLetter() } >= 2)))) return false
        if (tok.length > MAX_LEN) return false
        val letters = tok.count { it.isLetter() }
        if (letters < 2) return false
        val hasDigit = tok.any { it.isDigit() }
        val hasUpper = tok.any { it.isUpperCase() }
        val hasLower = tok.any { it.isLowerCase() }
        if (hasDigit && letters >= 2 && tok.length >= 3) return true
        if (tok.contains('_')) return tok.length >= 5
        if (tok.contains('-')) return hasDigit || (hasUpper && tok.length >= 6)
        if (isAcronym(tok)) return count >= 2 && tok !in COMMON_ACRONYMS && tok.length in 3..6
        if (hasUpper && hasLower && tok.drop(1).any { it.isUpperCase() }) return tok.length >= 5 // TensorFlow, macOS, iPhone
        if (tok[0].isUpperCase() && tok.drop(1).all { it.isLowerCase() }) {
            return tok.length >= 6 && mid && tok.lowercase() !in lowerForms
        }
        return false
    }

    private fun isAcronym(tok: String) = tok.length >= 3 && tok.all { it.isUpperCase() }

    private fun isLatin(tok: String) = tok.all { !it.isLetter() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }

    /** True when the token begins a sentence, a line or a list item (so its capital letter says nothing). */
    private fun startsSentence(text: String, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] == '"' || text[i] == '(' || text[i] == '\'')) i--
        return i < 0 || text[i] in SENTENCE_END
    }
}

/**
 * A small, temporary vocabulary learned from what is on screen while a recording runs. Newest terms win when it is full.
 * Held in memory only; the owner clears it when the recording ends. Thread-safe: the screen reader adds terms while the
 * recognition thread asks for the corrector.
 */
class ContextVocabulary(private val maxTerms: Int = 150) {
    private val terms = LinkedHashMap<String, String>() // lowercase -> spelling as seen; insertion order = least recent first
    private var cached: VocabularyCorrector? = null

    @Synchronized fun add(found: Collection<String>) {
        var changed = false
        for (t in found) {
            val key = t.lowercase()
            val old = terms.remove(key)          // re-insert so a term seen again becomes the most recent
            terms[key] = t
            if (old != t) changed = true
        }
        while (terms.size > maxTerms) { terms.remove(terms.keys.first()); changed = true }
        if (changed) cached = null
    }

    @Synchronized fun clear() { if (terms.isNotEmpty()) { terms.clear(); cached = null } }

    @Synchronized fun size(): Int = terms.size

    @Synchronized fun snapshot(): List<String> = terms.values.toList()

    @Synchronized fun corrector(): VocabularyCorrector =
        cached ?: VocabularyCorrector(terms.values.toList()).also { cached = it }
}
