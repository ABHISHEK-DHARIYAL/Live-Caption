package com.clearcap.text

/**
 * The one place where raw recognizer text becomes caption text. Order: remove invented/non-speech text, apply the
 * user's exact aliases, then casing/punctuation and the user's fuzzy vocabulary. Returns "" when nothing real was said.
 * The raw text is never changed here; callers keep it separately so every step is reversible.
 */
class SegmentTextPipeline(
    private val corrector: VocabularyCorrector,
    private val rules: VocabularyRules,
) {
    fun process(raw: String, durationMs: Long): String {
        val cleaned = SpeechFilters.cleanUp(raw, durationMs)
        if (cleaned.isEmpty()) return ""
        return TranscriptText.liveSentence(rules.apply(cleaned), corrector)
    }
}
