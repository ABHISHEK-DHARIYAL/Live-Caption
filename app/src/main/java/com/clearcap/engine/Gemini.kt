package com.clearcap.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(message: String) : Exception(message)

/** AI Notes: study notes from a transcript via Gemini, with the user's own key, only when they tap the button. */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val MAX_CHARS = 600_000

    fun notes(transcript: String, key: String, model: String): String {
        val text = transcript.trim()
        if (text.isEmpty()) throw GeminiException("The transcript is empty")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt(text.take(MAX_CHARS)))))))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        val c = URL("$BASE${model.trim()}:generateContent").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) throw GeminiException("Gemini answered HTTP ${c.responseCode}")
            val resp = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val cands = resp.optJSONArray("candidates")
            val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw GeminiException("Gemini returned no answer")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            return sb.toString().trim().ifEmpty { throw GeminiException("Gemini returned an empty answer") }
        } catch (e: GeminiException) {
            throw e
        } catch (e: IOException) {
            throw GeminiException("Network error (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            throw GeminiException("Unexpected response (${e.javaClass.simpleName})")
        } finally {
            c.disconnect()
        }
    }

    private fun prompt(t: String) = """
        You are a study assistant. Turn the transcript below into structured study notes in Markdown with these
        sections (skip one only if the transcript has nothing for it): ## Summary (3-5 sentences), ## Key concepts
        (bullets), ## Definitions, ## Formulas and examples, ## Likely exam questions (5, no answers),
        ## Unclear or worth re-checking. Use only what is in the transcript; do not invent facts.

        TRANSCRIPT:
        $t
    """.trimIndent()
}
