package com.lecturecaption.app.stt

/**
 * A recognized chunk of speech. `isFinal = false` results are partial/live hypotheses
 * (used to update the live caption smoothly); `isFinal = true` results are what gets
 * persisted as a TranscriptSegment.
 */
data class RecognitionResult(
    val text: String,
    val isFinal: Boolean,
    val startTimeMs: Long,
    val endTimeMs: Long
)

/**
 * Common contract for all offline speech-to-text backends. Implementations must accept
 * streaming 16-bit PCM mono audio and emit results incrementally — never buffer an entire
 * multi-hour lecture in memory before producing output.
 */
interface SpeechEngine {
    val id: String
    val displayName: String
    val requiredSampleRateHz: Int

    /** True once the model is loaded and ready to accept audio. */
    suspend fun isReady(): Boolean

    /** Loads the model from local storage. Must not touch the network. */
    suspend fun initialize()

    /**
     * Feed one chunk of 16-bit PCM mono audio at [requiredSampleRateHz].
     * Implementations should call [onResult] zero or more times per chunk
     * (partial results as recognition progresses, then a final result).
     */
    suspend fun acceptAudio(pcm: ShortArray, length: Int, onResult: (RecognitionResult) -> Unit)

    /** Flush any pending partial recognition into a final result before a session ends. */
    suspend fun finalizeSession(onResult: (RecognitionResult) -> Unit)

    suspend fun release()
}

enum class EngineType(val id: String, val displayName: String) {
    VOSK("VOSK", "Vosk (offline)"),
    VOSK_EN_IN("VOSK_EN_IN", "Vosk - English (India)"),
    VOSK_HI("VOSK_HI", "Vosk - Hindi"),
    BILINGUAL("BILINGUAL", "Hindi + English (auto-detect)"),
    WHISPER_TINY("WHISPER_TINY", "Whisper - Tiny"),
    WHISPER_BASE("WHISPER_BASE", "Whisper - Base"),
    WHISPER_SMALL("WHISPER_SMALL", "Whisper - Small")
}
