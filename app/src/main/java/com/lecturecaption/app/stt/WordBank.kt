package com.lecturecaption.app.stt

/**
 * "Proper words" handling for the custom dictionary, ported from OpenWhispr's dictionary utilities
 * (dictionaryImport, dictionaryPromptCap, dictionaryEchoFilter, correctionLearner) and covered by
 * WordBankTest, which reuses their test cases. Pure Kotlin: no Android types.
 */

/** Bulk-import of dictionary words: comma-, semicolon- or line-separated, mixed, blanks skipped. */
object DictionaryImport {
    private const val MAX_TERMS = 900
    private const val MAX_TERM_CHARS = 60
    private val SEPARATORS = charArrayOf(',', ';', '\n', '\r', '、', '，')
    private val FORBIDDEN = Regex("[<>]")
    private val WS = Regex("\\s+")

    fun parse(text: String?): List<String> =
        (text ?: "").split(*SEPARATORS)
            .map { WS.replace(FORBIDDEN.replace(it, ""), " ").trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TERM_CHARS }
            .distinctBy { it.lowercase() }
            .take(MAX_TERMS)
}

/** The dictionary as a spelling-hint prompt, cut so that no entry is ever sent half-spelled. */
object DictionaryPrompt {
    /** Whisper-style decoders read ~223 prompt tokens; a list of rare terms costs ~2.3-2.9 chars/token. */
    const val DEFAULT_MAX_CHARS = 550

    class Trimmed(val prompt: String, val originalLength: Int, val truncated: Boolean)

    fun build(terms: List<String>, maxChars: Int = DEFAULT_MAX_CHARS): String =
        trim(terms.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString(", "), maxChars).prompt

    /** Cuts at the last comma inside the budget. */
    fun trim(prompt: String, maxChars: Int): Trimmed {
        if (prompt.length <= maxChars) return Trimmed(prompt, prompt.length, false)
        val head = prompt.substring(0, maxChars)
        val comma = head.lastIndexOf(',')
        return Trimmed(if (comma > 0) head.substring(0, comma) else head, prompt.length, true)
    }
}

/**
 * Models given a list of spelling hints sometimes RECITE it: on silence ("OpenWhispr, Parakeet"),
 * looped ("data, data, data"), or after the real speech ("... Thanks, you. OpenWhispr, n8n.").
 * These checks tell a recital from legitimate speech that merely uses dictionary words.
 */
object DictionaryEchoFilter {
    private const val LOOPED_WORD_MIN = 3
    private const val MIN_CONSECUTIVE_TERM_RUN = 3
    private const val MAX_SHORT_FRAGMENT_CHARS = 30
    private const val MIN_TRAILING_ECHO_TERMS = 2
    private const val MIN_PROMPT_END_ECHO_TERMS = 5

    private val NOT_WORD = Regex("[^\\p{L}\\p{M}\\p{N}\\s]")
    private val WS = Regex("\\s+")
    private val DELIM = Regex("[,、，]")
    private val TRAILING_DELIM = Regex("[,、，]\\s*$")
    private val SENTENCE_BREAK = Regex("[.!?]\\s+|[。！？]\\s*")
    // An abbreviation, an initial or a list marker ends in "." without ending the sentence.
    private val NON_TERMINAL_PERIOD = Regex(
        "(?iu)(?:(?:^|[\\s(])(?:etc|vs|mrs?|ms|dr|st|jr|sra?|prof|incl|esp|approx|ca|cf|al|no|nr|ex|\\p{L}|\\d{1,2})|\\p{L}\\.\\p{L})$|\\.$"
    )

    private fun normalize(s: String) = WS.replace(NOT_WORD.replace(s.lowercase(), ""), " ").trim()

    private fun promptTerms(prompt: String): List<String> =
        prompt.split(DELIM).map { normalize(it) }.filter { it.isNotEmpty() }

    /** The text is (nearly) the whole hint list read back. */
    fun matchesPrompt(text: String?, prompt: String?): Boolean {
        if (text.isNullOrEmpty() || prompt.isNullOrEmpty()) return false
        val t = normalize(text)
        val p = normalize(prompt)
        if (t.isEmpty() || p.isEmpty()) return false
        if (t == p) return true
        val dictWords = p.split(" ").toSet()
        val textWords = t.split(" ").toSet()
        val match = textWords.count { it in dictWords }
        return match.toDouble() / textWords.size >= 0.9 && match.toDouble() / dictWords.size >= 0.7
    }

    /** Short output that continues the hint list in the list's own shape (looped term, dangling comma, ordered run). */
    fun isLikelyPromptFragment(text: String?, prompt: String?): Boolean {
        if (text.isNullOrEmpty() || prompt.isNullOrEmpty()) return false
        val nt = normalize(text)
        val np = normalize(prompt)
        if (nt.isEmpty() || np.isEmpty()) return false
        val words = nt.split(" ")
        val promptWords = np.split(" ").toSet()
        val unique = words.toSet()
        val match = unique.count { it in promptWords }
        val shape = hasLoopedWord(words) ||
            (TRAILING_DELIM.containsMatchIn(text) && nt.length <= MAX_SHORT_FRAGMENT_CHARS) ||
            (DELIM.containsMatchIn(text) && consecutiveTermRun(words, prompt))
        return match.toDouble() / unique.size >= 0.9 && shape
    }

    private fun hasLoopedWord(words: List<String>): Boolean {
        val counts = HashMap<String, Int>()
        for (w in words) {
            val c = (counts[w] ?: 0) + 1
            if (c >= LOOPED_WORD_MIN) return true
            counts[w] = c
        }
        return false
    }

    private fun consecutiveTermRun(words: List<String>, prompt: String): Boolean {
        val seq = ArrayList<Pair<String, Int>>()
        promptTerms(prompt).forEachIndexed { ti, term -> term.split(" ").forEach { seq.add(it to ti) } }
        var start = 0
        while (start + words.size <= seq.size) {
            var k = 0
            while (k < words.size && seq[start + k].first == words[k]) k++
            if (k == words.size && seq[start + words.size - 1].second - seq[start].second + 1 >= MIN_CONSECUTIVE_TERM_RUN) return true
            start++
        }
        return false
    }

    /**
     * Removes a hint list recited as its own final sentence after real speech. It must copy the sent
     * prompt in order, start on a dictionary entry and have 2+ entries; it must open the prompt, or
     * (5+ entries) close it. Anything else is real speech and is kept.
     */
    fun stripTrailingEcho(text: String, sentPrompt: String, dictionary: List<String>): String {
        val dictTerms = dictionary.flatMap { promptTerms(it) }.toSet()
        val sent = promptTerms(sentPrompt)
        fun countDict(terms: List<String>) = terms.count { it in dictTerms }
        if (countDict(sent) < MIN_TRAILING_ECHO_TERMS) return text
        val idx = HashMap<String, Int>()
        sent.forEachIndexed { i, t -> if (t !in idx) idx[t] = i }

        val body = text.trimEnd()
        var tailStart = -1
        for (m in SENTENCE_BREAK.findAll(body)) {
            val end = m.range.last + 1
            if (end >= body.length) continue
            if (m.value[0] == '.' && NON_TERMINAL_PERIOD.containsMatchIn(body.substring(0, m.range.first))) continue
            tailStart = end
        }
        if (tailStart <= 0) return text

        val tail = promptTerms(body.substring(tailStart))
        if (tail.isEmpty() || tail[0] !in dictTerms || countDict(tail) < MIN_TRAILING_ECHO_TERMS) return text
        val indexes = tail.map { idx[it] }
        if (indexes.any { it == null }) return text
        for (i in 1 until indexes.size) if (indexes[i]!! != indexes[i - 1]!! + 1) return text
        val opens = indexes.first() == 0
        val closes = indexes.size >= MIN_PROMPT_END_ECHO_TERMS && indexes.last() == sent.size - 1
        if (!opens && !closes) return text
        return body.substring(0, tailStart).trimEnd()
    }
}

/**
 * Learns new dictionary words from a user's corrections: diffs the text the app produced against the
 * user's edited version and returns the words they fixed (e.g. "Shunade" -> "Sinead").
 * Rewrites, everyday-word swaps and unrelated replacements are ignored.
 */
object CorrectionLearner {
    private val COMMON = (
        "the and for not with you this but his from they say her she will one all would there their what out about " +
            "who get which when make can like time just him know take into year your good some could them see other " +
            "than then now look only come over think also back after use two how our work first well way even new want " +
            "because any these give day most are was were been has had did does said went made got came took saw knew " +
            "thought where why here very much many still too again off down never every own same another both each few " +
            "more less last next while before through under between should might must being have that its yes okay"
        ).split(" ").toSet()

    private val EDGE = Regex("^[^\\p{L}\\p{N}_]+|[^\\p{L}\\p{N}_]+$")
    private val WS = Regex("\\s+")

    private fun tokenize(text: String): List<String> =
        text.split(WS).map { EDGE.replace(it, "") }.filter { it.isNotEmpty() }

    private fun editRegion(original: String, field: String): String {
        if (field.length <= original.length * 1.5) return field
        if (field.contains(original)) return original
        val ow = tokenize(original)
        val fw = tokenize(field)
        val size = ow.size
        if (fw.size <= size) return field
        var bestStart = 0
        var best = -1
        for (i in 0..fw.size - size) {
            var m = 0
            for (j in 0 until size) if (fw[i + j].equals(ow[j], ignoreCase = true)) m++
            if (m > best) { best = m; bestStart = i }
        }
        if (best < size * 0.3) return field
        return fw.subList(bestStart, bestStart + size).joinToString(" ")
    }

    /** Word-level LCS; a removed word followed by an added word is a substitution. */
    private fun substitutions(a: List<String>, b: List<String>): List<Pair<String, String>> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i - 1].equals(b[j - 1], true)) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        val aligned = ArrayList<Pair<String?, String?>>()
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i - 1].equals(b[j - 1], true)) { aligned.add(0, a[i - 1] to b[j - 1]); i--; j-- }
            else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) { aligned.add(0, null to b[j - 1]); j-- }
            else { aligned.add(0, a[i - 1] to null); i-- }
        }
        val subs = ArrayList<Pair<String, String>>()
        for (k in 0 until aligned.size - 1) {
            val (o, e) = aligned[k]
            val (no, ne) = aligned[k + 1]
            if (o != null && e == null && no == null && ne != null) subs.add(o to ne)
        }
        return subs
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun extract(original: String?, edited: String?, existing: List<String>): List<String> {
        if (original.isNullOrEmpty() || edited.isNullOrEmpty() || original == edited) return emptyList()
        val region = editRegion(original, edited)
        if (region == original) return emptyList()
        val ow = tokenize(original)
        val ew = tokenize(region)
        if (ow.isEmpty() || ew.isEmpty()) return emptyList()
        val subs = substitutions(ow, ew)
        if (subs.size > ow.size * 0.5) return emptyList() // a rewrite, not corrections
        val dict = existing.map { it.lowercase() }.toSet()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for ((o, c) in subs) {
            val lc = c.lowercase()
            if (lc in dict || lc in seen || o.lowercase() == lc || c.length < 3 || lc in COMMON) continue
            val dist = editDistance(o.lowercase(), lc)
            if (dist.toDouble() / maxOf(o.length, c.length) > 0.65) continue
            out.add(c)
            seen.add(lc)
        }
        return out
    }
}
