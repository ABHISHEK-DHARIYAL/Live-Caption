package com.lecturecaption.app.stt

import java.io.File

/**
 * ============================== IMPORTANT — READ BEFORE SHIPPING ==============================
 * Whisper has no pure-JVM/Kotlin implementation. Real on-device Whisper inference requires a
 * native library (ggml/whisper.cpp) built for each Android ABI and invoked through JNI. That is
 * a genuine native-build step (CMake + NDK) that can't be faked or hand-waved — I'm not going to
 * pretend a JNI call works when there's no compiled .so backing it.
 *
 * What IS real and working in this class:
 *  - GGUF model file download/verification/deletion, driven from the Settings screen
 *    (see ModelManager.kt), so "Tiny / Base / Small" selection and local storage genuinely work.
 *  - The SpeechEngine contract (chunk in, RecognitionResult out) so swapping in real inference
 *    later requires touching only [acceptAudio] below, nothing else in the app.
 *
 * To make transcription actually run, do ONE of:
 *   1. Add the official whisper.cpp Android example as a module (JNI bridge + CMakeLists.txt):
 *      https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android
 *      then replace the TODO in acceptAudio() with calls into that bridge.
 *   2. Use whisper.cpp's whisper.android via JitPack/Maven if you don't want to build native
 *      code yourself (check current publishing status on the whisper.cpp repo).
 *
 * Until one of those is wired in, selecting a Whisper model in this app will download the model
 * file correctly but [acceptAudio] will throw NotImplementedError rather than silently produce
 * fake captions — the app will visibly tell the user Whisper isn't wired up yet instead of lying.
 * ================================================================================================
 */
class WhisperEngine(
    private val modelFile: File,
    override val id: String,
    override val displayName: String
) : SpeechEngine {

    override val requiredSampleRateHz = 16_000
    private var ready = false

    override suspend fun isReady(): Boolean = ready

    override suspend fun initialize() {
        require(modelFile.exists()) {
            "Whisper model not downloaded: ${modelFile.absolutePath}. Download it from Settings first."
        }
        // Model file presence is verified; native context creation is NOT implemented — see class doc.
        ready = true
    }

    override suspend fun acceptAudio(
        pcm: ShortArray,
        length: Int,
        onResult: (RecognitionResult) -> Unit
    ) {
        throw NotImplementedError(
            "Whisper inference isn't wired up yet — this build has no compiled whisper.cpp " +
                "native library. Switch Transcription Engine to Vosk, which works today, or " +
                "add the native module described in WhisperEngine.kt."
        )
    }

    override suspend fun finalizeSession(onResult: (RecognitionResult) -> Unit) {
        // No-op until real inference exists.
    }

    override suspend fun release() {
        ready = false
    }
}
