package com.clearcap.engine

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

private const val RATE = 16_000

/** Live caption update from the streaming engine. */
sealed class LiveUpdate {
    abstract val text: String

    /** The sentence so far; may still change. */
    class Partial(override val text: String) : LiveUpdate()

    /** A finished sentence (the engine heard a pause). */
    class Final(override val text: String) : LiveUpdate()
}

/**
 * Streaming Zipformer (sherpa-onnx). One recognizer, 100 ms chunks, no second model running next to it:
 * this is what keeps captions real-time on a phone.
 */
class LiveEngine(files: ModelFiles, threads: Int = 2) : AutoCloseable {
    private val rec = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = files.a, decoder = files.b, joiner = files.c),
                tokens = files.tokens,
                numThreads = threads,
                provider = "cpu",
            ),
            // A sentence ends after ~0.9 s of silence following speech, or after 25 s regardless.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.0f, 0.0f),
                rule2 = EndpointRule(true, 0.9f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 25.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )
    private var stream: OnlineStream = rec.createStream()
    private var last = ""

    /** Feeds one chunk; returns what changed, or null. */
    fun accept(samples: ShortArray, length: Int): LiveUpdate? {
        val f = FloatArray(length) { samples[it] / 32768f }
        stream.acceptWaveform(f, RATE)
        while (rec.isReady(stream)) rec.decode(stream)
        val text = rec.getResult(stream).text.trim()
        if (rec.isEndpoint(stream)) {
            rec.reset(stream)
            last = ""
            return if (text.isNotEmpty()) LiveUpdate.Final(text) else null
        }
        if (text == last) return null
        last = text
        return LiveUpdate.Partial(text)
    }

    /** End of recording: pad with a little silence so the last word is decoded, and return the tail. */
    fun finish(): String {
        stream.acceptWaveform(FloatArray(RATE * 4 / 10), RATE)
        stream.inputFinished()
        while (rec.isReady(stream)) rec.decode(stream)
        val t = rec.getResult(stream).text.trim()
        rec.reset(stream)
        last = ""
        return t
    }

    override fun close() {
        runCatching { stream.release() }
        runCatching { rec.release() }
    }
}

/** Offline accurate engine: Parakeet (English) or Whisper (multilingual). */
class AccurateEngine(model: ModelSpec, files: ModelFiles, language: String, threads: Int) : AutoCloseable {
    private val rec: OfflineRecognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = if (model == Models.parakeet) OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = files.a),
                tokens = files.tokens, numThreads = threads, provider = "cpu",
            ) else OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files.a, decoder = files.b,
                    language = if (language == "auto") "" else language, task = "transcribe",
                ),
                tokens = files.tokens, numThreads = threads, provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(samples: FloatArray): String {
        val s = rec.createStream()
        try {
            s.acceptWaveform(samples, RATE)
            rec.decode(s)
            return rec.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    override fun close() = runCatching { rec.release() }.let { }
}

/** One stretch of speech found by the VAD. Times are ms from the start of the recording. */
class Speech(val startMs: Long, val endMs: Long, val samples: ShortArray) {
    /** Float form for the recognizer; made per segment so only one is in memory at a time. */
    fun floats(): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }
}

/**
 * Silero VAD (sherpa-onnx), run while recording. Only SPEECH is kept, in memory only, for the accurate
 * pass. Audio is never written to storage. Capped at [maxSpeechMs] so a very long recording can't run out
 * of memory; the rest keeps its live text (see [truncatedAtMs]).
 */
class SpeechCollector(vadModelPath: String, private val maxSpeechMs: Long = 40 * 60_000L) {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModelPath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f,
            ),
            sampleRate = RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )
    private val out = ArrayList<Speech>()
    private var keptMs = 0L
    var truncatedAtMs: Long? = null
        private set

    fun feed(samples: ShortArray, length: Int) {
        vad.acceptWaveform(FloatArray(length) { samples[it] / 32768f })
        drain()
    }

    /** Flushes the detector and returns everything collected. */
    fun finish(): List<Speech> {
        vad.flush()
        drain()
        runCatching { vad.release() }
        return out
    }

    fun release() = runCatching { vad.release() }.let { }

    private fun drain() {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val ms = seg.samples.size * 1000L / RATE
            val start = seg.start * 1000L / RATE
            if (seg.samples.size < RATE / 8) continue // under ~125 ms: not a word
            if (truncatedAtMs != null) continue
            if (keptMs + ms > maxSpeechMs) { truncatedAtMs = start; continue }
            keptMs += ms
            out.add(Speech(start, start + ms, ShortArray(seg.samples.size) { (seg.samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }))
        }
    }
}
