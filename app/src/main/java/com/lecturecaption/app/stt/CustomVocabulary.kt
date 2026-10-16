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

    /** Returns false if the term was blank or already present. */
    fun add(context: Context, term: String): Boolean {
        val clean = term.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return false
        val current = load(context)
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        save(context, current + clean)
        return true
    }

    fun remove(context: Context, term: String) {
        save(context, load(context).filterNot { it == term })
    }

    private fun save(context: Context, terms: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, terms.joinToString("\n")).apply()
    }
}
