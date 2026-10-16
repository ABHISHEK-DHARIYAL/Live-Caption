package com.lecturecaption.app.stt

import kotlinx.coroutines.flow.StateFlow

/**
 * An "accurate transcript" pass that listens to the same audio as the live captions and, when the
 * recording ends, can replace the saved transcript. Two implementations: [WhisperRefiner] (fully on
 * the phone) and [OnlineRefiner] (optional, sends speech to Google's Gemini API).
 */
interface Refiner {
    /** Short name shown to the user, e.g. "Whisper" or "online mode". */
    val label: String

    /** Pieces of speech waiting to be (or being) transcribed. */
    val remaining: StateFlow<Int>

    /** If the pass failed, a short human-readable reason (null when fine or unknown). */
    val failureReason: String?

    fun start()

    /** Called for every captured audio chunk (any thread). Must never block. */
    fun feed(samples: ShortArray, length: Int)

    /** Waits for queued work and returns the refined transcript, or null if it can't be trusted to be complete. */
    suspend fun finish(): List<RefinedSegment>?

    /** Frees resources without producing a result. */
    fun abort()
}
