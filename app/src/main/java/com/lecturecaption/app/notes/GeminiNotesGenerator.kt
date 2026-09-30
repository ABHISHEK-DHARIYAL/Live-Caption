package com.lecturecaption.app.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

open class GeminiApiException(message: String) : Exception(message)

/**
 * Calls Google's Gemini API (free tier available via Google AI Studio) with the lecture
 * transcript and asks for structured study notes back as JSON. Requires the user's own API
 * key (see ApiKeyStore) — this app ships with no key of its own and makes no request until
 * the user explicitly taps "Generate Notes" with a key configured.
 *
 * Sends: the transcript text and title, to Google's servers, over HTTPS.
 * Does NOT send: anything else — no device ID, no other lectures, no account info.
 */
class GeminiNotesGenerator(private val apiKey: String) : NotesGenerator {

    companion object {
        // "flash" = Gemini's fast/cheap tier. Google retires old models (gemini-2.0-flash now
        // returns "no longer available"), so try the current one first and fall back to the
        // previous flash versions if a model is ever rejected as unavailable (HTTP 404).
        private val MODELS = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash")
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(transcriptText: String, lectureTitle: String): LectureNotes =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw GeminiApiException("No Gemini API key is configured.")
            val trimmed = transcriptText.take(24_000) // stay well under the model's input limit

            val prompt = buildString {
                append("You are generating study notes from a lecture transcript. ")
                append("Respond with ONLY a single JSON object, no markdown fences, no extra text, ")
                append("matching exactly this shape: ")
                append(
                    "{\"key_concepts\":[string], \"definitions\":[{\"term\":string,\"definition\":string}], " +
                        "\"examples\":[string], \"important_points\":[string], \"exam_questions\":[string], " +
                        "\"quick_revision\":string}. "
                )
                append("Lecture title: ").append(lectureTitle).append(". ")
                append("Transcript:\n").append(trimmed)
            }

            val requestBody = buildJsonRequest(prompt)
            var lastError: GeminiApiException? = null
            for (model in MODELS) {
                try {
                    return@withContext parseNotes(postToGemini(model, requestBody))
                } catch (e: ModelUnavailableException) {
                    lastError = e // try the next model
                }
            }
            throw lastError ?: GeminiApiException("No Gemini model is available for this API key.")
        }

    private fun buildJsonRequest(prompt: String): String {
        // Built with kotlinx.serialization so EVERY special character in the transcript (tabs,
        // carriage returns, Devanagari, quotes...) is escaped correctly. The old hand-built string
        // only escaped \\, " and newline and could produce invalid JSON -> HTTP 400.
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
            }
        }
        return body.toString()
    }

    private class ModelUnavailableException(message: String) : GeminiApiException(message)

    private fun postToGemini(model: String, body: String): String {
        val url = URL("$BASE/$model:generateContent")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey) // header, not in the URL/logs
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val message = try {
                    json.parseToJsonElement(text).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.content
                } catch (_: Exception) { null }
                val unavailable = code == 404 ||
                    (message?.contains("no longer available", ignoreCase = true) == true)
                if (unavailable) throw ModelUnavailableException(message ?: "Model $model is unavailable.")
                throw GeminiApiException(
                    when (code) {
                        400 -> message ?: "Gemini rejected the request (invalid API key or request)."
                        403 -> "This API key doesn't have access to the Gemini API. Check it in Google AI Studio."
                        429 -> "Gemini's free-tier rate limit was hit. Wait a bit and try again."
                        else -> message ?: "Gemini request failed (HTTP $code)."
                    }
                )
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseNotes(raw: String): LectureNotes {
        val root = json.parseToJsonElement(raw).jsonObject
        val text = root["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw GeminiApiException("Gemini returned an empty response.")

        // The model is asked for raw JSON but may still wrap it in ```json fences; strip those.
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return try {
            val notes = json.parseToJsonElement(cleaned).jsonObject
            LectureNotes(
                keyConcepts = notes["key_concepts"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                definitions = notes["definitions"]?.jsonArray?.map {
                    val o = it.jsonObject
                    (o["term"]?.jsonPrimitive?.content ?: "") to (o["definition"]?.jsonPrimitive?.content ?: "")
                } ?: emptyList(),
                examples = notes["examples"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                importantPoints = notes["important_points"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                examQuestions = notes["exam_questions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                quickRevision = notes["quick_revision"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            // The model didn't return valid JSON this time — surface the raw text rather than crash.
            LectureNotes(
                keyConcepts = emptyList(), definitions = emptyList(), examples = emptyList(),
                importantPoints = emptyList(), examQuestions = emptyList(),
                quickRevision = cleaned
            )
        }
    }
}
