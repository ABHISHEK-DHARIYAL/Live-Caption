package com.clearcap.asr

import com.clearcap.models.ModelInfo
import com.k2fsa.sherpa.onnx.OfflineRecognizer

/** Result of decoding one speech segment. [language] is what the model reported (Whisper only), normalised like "hi". */
class Decoded(val text: String, val language: String?)

/**
 * Decodes finished speech segments with Parakeet or Whisper. A native decode call cannot be interrupted, so
 * cancellation is checked between segments (each is at most [MAX_SEGMENT_SEC] long).
 */
class OfflineDecoder(val model: ModelInfo, paths: Map<String, String>, language: String, threads: Int) : AutoCloseable {
    private val rec: OfflineRecognizer = SherpaFactory.offline(model, paths, language, threads)

    @Volatile var decodeNs = 0L; private set
    @Volatile var decodedAudioMs = 0L; private set

    fun decode(samples: FloatArray): Decoded {
        val t0 = System.nanoTime()
        val s = rec.createStream()
        try {
            s.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(s)
            val r = rec.getResult(s)
            return Decoded(r.text.trim(), normalizeLang(r.lang))
        } finally {
            s.release()
            decodeNs += System.nanoTime() - t0
            decodedAudioMs += samples.size * 1000L / SAMPLE_RATE
        }
    }

    override fun close() { runCatching { rec.release() } }

    companion object {
        const val MAX_SEGMENT_SEC = 20f
        fun normalizeLang(raw: String?): String? = raw?.trim()?.removePrefix("<|")?.removeSuffix("|>")?.lowercase()?.ifBlank { null }
    }
}
