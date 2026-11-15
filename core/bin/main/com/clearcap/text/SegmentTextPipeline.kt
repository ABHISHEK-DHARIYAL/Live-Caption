package com.clearcap.text

/**
 * The one place where raw recognizer text becomes caption text. Order: remove invented/non-speech text, apply the
 * user's exact aliases, then casing/punctuation, the user's fuzzy vocabulary and, if given, the words currently on screen. Returns "" when nothing real was said.
 * The raw text is never changed here; callers keep it separately so every step is reversible.
 */
class SegmentTextPipeline(
    private val corrector: VocabularyCorrector,
    private val rules: VocabularyRules,
    /** Optional per-session terms read from the screen. Applied after the user's own vocabulary, which always wins. */
    private val context: ContextVocabulary? = null,
) {
    fun process(raw: String, durationMs: Long): String {
        val cleaned = SpeechFilters.cleanUp(raw, durationMs)
        if (cleaned.isEmpty()) return ""
        val text = TranscriptText.liveSentence(rules.apply(cleaned), corrector)
        return context?.corrector()?.correct(text) ?: text
    }
}
