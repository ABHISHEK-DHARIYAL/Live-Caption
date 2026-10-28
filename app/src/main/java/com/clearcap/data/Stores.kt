package com.clearcap.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.clearcap.text.CorrectionLearner
import com.clearcap.text.DictionaryImport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

data class Transcript(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val live: String,
    val accurate: String?,       // set by the accurate pass
    val edited: String?,         // set when the user edits the text
    val notes: String?,
    val source: String,          // system | mic | both
    val behindMs: Long = 0,      // audio the phone could not keep up with
) {
    /** What the app shows, copies and shares: the user's edit, else the accurate text, else the live text. */
    val text: String get() = edited?.takeIf { it.isNotBlank() } ?: accurate?.takeIf { it.isNotBlank() } ?: live
    val words: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/** One JSON file per transcript in app-private storage. Audio is never stored. */
class TranscriptStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "transcripts").apply { mkdirs() }
    private val _all = MutableStateFlow(readAll())
    val all: StateFlow<List<Transcript>> = _all.asStateFlow()

    fun get(id: String): Transcript? = _all.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(t: Transcript) {
        val j = JSONObject().put("id", t.id).put("title", t.title).put("createdAt", t.createdAt)
            .put("durationMs", t.durationMs).put("live", t.live).put("source", t.source).put("behindMs", t.behindMs)
            .put("accurate", t.accurate ?: JSONObject.NULL).put("edited", t.edited ?: JSONObject.NULL)
            .put("notes", t.notes ?: JSONObject.NULL)
        val tmp = File(dir, "${t.id}.json.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(File(dir, "${t.id}.json"))
        _all.value = readAll()
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.json").delete()
        _all.value = readAll()
    }

    private fun readAll(): List<Transcript> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { runCatching { parse(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    private fun opt(j: JSONObject, k: String): String? = if (j.isNull(k)) null else j.optString(k)

    private fun parse(j: JSONObject) = Transcript(
        id = j.getString("id"), title = j.optString("title", "Recording"), createdAt = j.optLong("createdAt"),
        durationMs = j.optLong("durationMs"), live = j.optString("live"), accurate = opt(j, "accurate"),
        edited = opt(j, "edited"), notes = opt(j, "notes"), source = j.optString("source", "system"),
        behindMs = j.optLong("behindMs"),
    )
}

/** The user's dictionary of proper words (names, jargon). */
class DictionaryStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("dictionary", Context.MODE_PRIVATE)
    private val _words = MutableStateFlow(load())
    val words: StateFlow<List<String>> = _words.asStateFlow()

    /** Adds one or many words (comma / semicolon / new-line separated). Returns how many were new. */
    fun add(text: String): Int {
        val known = _words.value.map { it.lowercase() }.toSet()
        val fresh = DictionaryImport.parse(text).filter { it.lowercase() !in known }
        if (fresh.isNotEmpty()) save(_words.value + fresh)
        return fresh.size
    }

    fun remove(word: String) = save(_words.value - word)

    fun replace(old: String, new: String) {
        val n = DictionaryImport.parse(new).firstOrNull() ?: return
        save(_words.value.map { if (it == old) n else it }.distinctBy { it.lowercase() })
    }

    fun clear() = save(emptyList())

    /** Learns the words the user fixed when editing a transcript. Returns the words added. */
    fun learnFrom(original: String, edited: String): List<String> {
        val learned = CorrectionLearner.extract(original, edited, _words.value)
        if (learned.isNotEmpty()) add(learned.joinToString(","))
        return learned
    }

    private fun save(list: List<String>) {
        _words.value = list
        p.edit().putString("words", list.joinToString("\n")).apply()
    }

    private fun load(): List<String> =
        (p.getString("words", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Gemini API key (AI Notes only), stored with EncryptedSharedPreferences. Never logged; removable. */
class ApiKeyStore(private val ctx: Context) {
    private val prefs by lazy {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "secure_keys", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val _has = MutableStateFlow(read() != null)
    val hasKey: StateFlow<Boolean> = _has.asStateFlow()

    fun get(): String? = read()

    fun set(key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        runCatching { prefs.edit().putString(KEY, k).apply() }
        _has.value = read() != null
    }

    fun clear() {
        runCatching { prefs.edit().remove(KEY).apply() }
        _has.value = false
    }

    private fun read(): String? = runCatching { prefs.getString(KEY, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object { const val KEY = "gemini_api_key" }
}
