package com.lecturecaption.app.stt

import android.content.Context

/**
 * The user's own word list (names, technical terms, subject jargon, new words) that the
 * offline recognizer's fixed vocabulary does not contain. Stored locally, never uploaded.
 */
object CustomVocabulary {
    private const val PREFS = "custom_vocabulary"
    private const val KEY = "terms"

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Adds one or many words. Accepts a pasted list separated by commas, semicolons or new lines
     * (bulk import). Returns false if nothing new was added.
     */
    fun add(context: Context, term: String): Boolean {
        val incoming = DictionaryImport.parse(term)
        if (incoming.isEmpty()) return false
        val current = load(context)
        val known = current.map { it.lowercase() }.toSet()
        val fresh = incoming.filter { it.lowercase() !in known }
        if (fresh.isEmpty()) return false
        save(context, current + fresh)
        return true
    }

    /**
     * Learns words from a user's correction of a transcript (original text vs the edited text) and
     * adds them. Returns the words added. Ready for a transcript-edit screen; nothing calls it yet.
     */
    fun learnFrom(context: Context, original: String, edited: String): List<String> {
        val words = CorrectionLearner.extract(original, edited, load(context))
        words.forEach { add(context, it) }
        return words
    }

    fun remove(context: Context, term: String) {
        save(context, load(context).filterNot { it == term })
    }

    private fun save(context: Context, terms: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, terms.joinToString("\n")).apply()
    }
}
