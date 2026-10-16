package com.lecturecaption.app.stt

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real, working offline engine using the Vosk Android library (com.alphacephei:vosk-android).
 * Vosk ships a prebuilt native recognizer, so this needs no NDK/C++ build from us — the model
 * is just a folder of files unzipped into local storage, matching the "download models
 * separately and store them locally" requirement.
 *
 * Models: download a small model (e.g. "vosk-model-small-en-us-0.15", ~40MB) from
 * https://alphacephei.com/vosk/models, unzip it, and place the folder under
 * context.filesDir/vosk-models/<modelName>/ — see ModelManager.kt for the download/unzip flow
 * driven from the Settings screen.
 */
class VoskEngine(
    private val context: Context,
    private val modelDir: File
) : SpeechEngine {

    override val id = EngineType.VOSK.id
    override val displayName = EngineType.VOSK.displayName
    override val requiredSampleRateHz = 16_000

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private val json = Json { ignoreUnknownKeys = true }

    private var sessionStartMs = 0L
    private var lastEmittedEndMs = 0L

    override suspend fun isReady(): Boolean = model != null && recognizer != null

    override suspend fun initialize() {
        if (isReady()) return
        require(modelDir.exists() && modelDir.isDirectory) {
            "Vosk model not found at ${modelDir.absolutePath}. Download it from Settings first."
        }
        model = suspendCancellableCoroutine { cont ->
            // Vosk's Model constructor blocks on disk I/O; StorageService.unpack is only needed
            // if the model ships inside assets. Since we already have a plain unzipped directory,
            // we can construct Model directly off the path.
            try {
                val m = Model(modelDir.absolutePath)
                cont.resume(m)
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
        recognizer = Recognizer(model, requiredSampleRateHz.toFloat())
        sessionStartMs = 0L
        lastEmittedEndMs = 0L
    }

    override suspend fun acceptAudio(
        pcm: ShortArray,
        length: Int,
        onResult: (RecognitionResult) -> Unit
    ) {
        val rec = recognizer ?: return
        val bytes = shortsToLittleEndianBytes(pcm, length)
        val gotFinal = rec.acceptWaveForm(bytes, bytes.size)
        val rawJson = if (gotFinal) rec.result else rec.partialResult
        val text = extractText(rawJson, gotFinal)
        if (text.isNotBlank()) {
            val nowMs = lastEmittedEndMs + approximateChunkDurationMs(length)
            onResult(
                RecognitionResult(
                    text = text,
                    isFinal = gotFinal,
                    startTimeMs = lastEmittedEndMs,
                    endTimeMs = nowMs
                )
            )
            if (gotFinal) lastEmittedEndMs = nowMs
        }
    }

    override suspend fun finalizeSession(onResult: (RecognitionResult) -> Unit) {
        val rec = recognizer ?: return
        val text = extractText(rec.finalResult, isFinal = true)
        if (text.isNotBlank()) {
            onResult(
                RecognitionResult(
                    text = text,
                    isFinal = true,
                    startTimeMs = lastEmittedEndMs,
                    endTimeMs = lastEmittedEndMs
                )
            )
        }
    }

    override suspend fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
    }

    private fun approximateChunkDurationMs(sampleCount: Int): Long =
        (sampleCount * 1000L) / requiredSampleRateHz

    private fun extractText(rawJson: String, isFinal: Boolean): String {
        return try {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val key = if (isFinal) "text" else "partial"
            obj[key]?.jsonPrimitive?.content.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun shortsToLittleEndianBytes(pcm: ShortArray, length: Int): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = pcm[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
