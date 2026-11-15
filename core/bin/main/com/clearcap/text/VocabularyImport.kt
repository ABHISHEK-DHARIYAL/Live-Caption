package com.clearcap.text

/**
 * Bulk vocabulary input. One entry per line, or several plain terms separated by commas/semicolons:
 *   Kubernetes
 *   कुबेरनेट्स, cuber nets => Kubernetes      (aliases on the left, preferred spelling on the right)
 */
object VocabularyImport {
    fun parse(text: String?): List<VocabEntry> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<VocabEntry>()
        val seen = HashSet<String>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val arrow = l.indexOf("=>")
            if (arrow >= 0) {
                val term = l.substring(arrow + 2).trim()
                val aliases = l.substring(0, arrow).split(',', '|', ';').map { it.trim() }.filter { it.isNotEmpty() }
                if (term.isNotEmpty() && seen.add(term.lowercase())) out.add(VocabEntry(term, aliases))
            } else {
                for (t in DictionaryImport.parse(l)) if (seen.add(t.lowercase())) out.add(VocabEntry(t))
            }
        }
        return out
    }
}
