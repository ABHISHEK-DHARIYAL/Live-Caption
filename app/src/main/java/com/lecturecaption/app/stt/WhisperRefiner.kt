package com.lecturecaption.app.stt

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The "accurate" pass. Vosk keeps producing the instant live caption; in parallel this class listens
 * to the SAME audio, cuts it into speech segments with a voice-activity detector (Silero), and
 * transcribes each segment with Whisper (via sherpa-onnx, fully on the phone).
 *
 * Why Whisper: Vosk can only output words from a fixed list, so a new word becomes similar-sounding
 * known words. Whisper builds words from sub-word pieces, so it can spell names and technical terms
 * it has never seen, and it handles Hindi/English and punctuation far better.
 *
 * Two worker threads: one runs the (cheap) voice detector in real time so no audio piles up; the other
 * runs the (heavy) Whisper decoder and may lag behind. When recording ends, [finish] waits for the
 * decoder to catch up and returns the refined segments - or null if anything was lost, in which case
 * the caller keeps the Vosk transcript (never a transcript with holes).
 *
 * Raw audio is only held in memory while it waits to be decoded; nothing is written to disk.
 */
class WhisperRefiner(
    private val modelDir: File,
    private val vadModel: File,
    /** "" = auto-detect per segment (Hindi + English); "en" = English only. */
    private val language: String,
    private val textProcessor: (String) -> String = { it }
) : Refiner {
    override val label = "Whisper"
    override val failureReason: String? get() = if (lost) "Whisper could not keep up or failed." else null

    private class DecodeJob(val startMs: Long, val endMs: Long, val samples: FloatArray)

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    private val sampleRate = 16_000
    private val inbox = Channel<ShortArray>(Channel.UNLIMITED)
    private val decodeQueue = Channel<DecodeJob>(Channel.UNLIMITED)

    private val vadExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "WhisperVad") }
    // Lower priority than the Vosk thread so the live caption stays smooth while Whisper works.
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WhisperDecode").apply { priority = Thread.NORM_PRIORITY - 2 }
    }
    private val scope = CoroutineScope(Dispatchers.Default)
    private var decodeJob: kotlinx.coroutines.Job? = null
    private var vadLoopJob: kotlinx.coroutines.Job? = null

    private val closed = AtomicBoolean(false)
    private val pendingSamples = AtomicLong(0)
    @Volatile private var lost = false          // audio dropped (backlog too large) or a decode failed
    private val results = ArrayList<RefinedSegment>()

    private val _remaining = MutableStateFlow(0)
    /** Speech segments waiting to be (or being) transcribed. */
    override val remaining: StateFlow<Int> = _remaining

    /** Loads the models (a few seconds). Call off the main thread. Throws if a file is missing/invalid. */
    fun load() {
        val enc = File(modelDir, ModelManager.WHISPER_ENCODER)
        val dec = File(modelDir, ModelManager.WHISPER_DECODER)
        val tok = File(modelDir, ModelManager.WHISPER_TOKENS)
        require(enc.exists() && dec.exists() && tok.exists()) { "Whisper model files are missing. Download the model in Settings." }
        require(vadModel.exists()) { "Speech detector model is missing. Download it in Settings." }

        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = enc.absolutePath,
                    decoder = dec.absolutePath,
                    language = language,
                    task = "transcribe"
                ),
                tokens = tok.absolutePath,
                numThreads = 2,
                provider = "cpu",
                debug = false
            ),
            decodingMethod = "greedy_search"
        )
        recognizer = OfflineRecognizer(config = config)

        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.6f,   // a real pause ends a segment
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 20f      // Whisper takes up to 30 s; long talk is cut here
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )
    }

    /** Starts the two worker threads. [load] must have succeeded. */
    override fun start() {
        val v = vad ?: return
        val r = recognizer ?: return

        decodeJob = scope.launch(decodeExecutor.asCoroutineDispatcher()) {
            for (job in decodeQueue) {
                try {
                    val stream = r.createStream()
                    try {
                        stream.acceptWaveform(job.samples, sampleRate)
                        r.decode(stream)
                        val raw = r.getResult(stream).text
                        val text = textProcessor(cleanUp(raw, job.endMs - job.startMs)).trim()
                        if (text.isNotEmpty()) synchronized(results) { results.add(RefinedSegment(job.startMs, job.endMs, text)) }
                    } finally {
                        stream.release()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    lost = true
                } finally {
                    pendingSamples.addAndGet(-job.samples.size.toLong())
                    _remaining.value = maxOf(0, _remaining.value - 1)
                }
            }
        }

        vadLoopJob = scope.launch(vadExecutor.asCoroutineDispatcher()) {
            for (chunk in inbox) {
                val f = FloatArray(chunk.size) { chunk[it] / 32768f }
                v.acceptWaveform(f)
                drainVad(v)
            }
            v.flush()          // end of recording: emit the last (possibly unfinished) speech segment
            drainVad(v)
            decodeQueue.close()
        }
    }

    private fun drainVad(v: Vad) {
        while (!v.empty()) {
            val seg = v.front()
            v.pop()
            val size = seg.samples.size
            if (size < sampleRate / 8) continue // under ~125 ms: not a word
            // The detector can fire on hiss, music or a click. Whisper then "transcribes" it into
            // made-up words, so segments that are simply too quiet to be speech are never decoded.
            if (!SpeechFilters.isSpeechLike(seg.samples)) continue
            if (pendingSamples.get() + size > MAX_PENDING_SAMPLES) { lost = true; continue }
            pendingSamples.addAndGet(size.toLong())
            val startMs = seg.start * 1000L / sampleRate
            _remaining.value = _remaining.value + 1
            decodeQueue.trySend(DecodeJob(startMs, startMs + size * 1000L / sampleRate, seg.samples))
        }
    }

    /** Called for every captured chunk (any thread). Never blocks. */
    override fun feed(samples: ShortArray, length: Int) {
        if (closed.get() || length <= 0) return
        inbox.trySend(samples.copyOf(length))
    }

    /**
     * Stops accepting audio, waits for Whisper to finish what is queued, and returns the refined
     * transcript (nearby pieces merged into readable paragraphs). Returns null when the result
     * can't be trusted to be complete - the caller must then keep the Vosk transcript.
     */
    override suspend fun finish(): List<RefinedSegment>? = finishWithin(20 * 60_000L)

    suspend fun finishWithin(timeoutMs: Long): List<RefinedSegment>? {
        closed.set(true)
        inbox.close()
        val done = withTimeoutOrNull(timeoutMs) {
            vadLoopJob?.join()
            decodeJob?.join()
            true
        }
        if (done == null) {
            lost = true
            decodeJob?.cancel()
            decodeJob?.join()   // lets the segment being decoded finish before native objects are freed
        }
        try { vad?.release() } catch (_: Throwable) {}
        try { recognizer?.release() } catch (_: Throwable) {}
        vad = null
        recognizer = null
        vadExecutor.shutdown()
        decodeExecutor.shutdown()
        if (lost) return null
        val sorted = synchronized(results) { results.sortedBy { it.startMs } }
        return if (sorted.isEmpty()) null else mergeIntoParagraphs(sorted)
    }

    /** Frees native memory without producing a result (recording failed/cancelled). */
    override fun abort() {
        closed.set(true)
        inbox.close()
        decodeQueue.close()
        vadLoopJob?.cancel()
        decodeJob?.cancel()
        try { vad?.release() } catch (_: Throwable) {}
        try { recognizer?.release() } catch (_: Throwable) {}
        vad = null
        recognizer = null
        vadExecutor.shutdown()
        decodeExecutor.shutdown()
    }

    companion object {
        /** ~8 minutes of audio waiting for the decoder (~31 MB). Beyond that Whisper can't keep up. */
        private const val MAX_PENDING_SAMPLES = 16_000L * 60 * 8

        // The guards live in SpeechFilters (pure Kotlin, unit-tested); these keep the old entry points.
        internal fun rms(x: FloatArray): Float = SpeechFilters.rms(x)

        internal fun cleanUp(raw: String, durationMs: Long): String = SpeechFilters.cleanUp(raw, durationMs)

        internal fun mergeIntoParagraphs(parts: List<RefinedSegment>): List<RefinedSegment> =
            SpeechFilters.mergeIntoParagraphs(parts)
    }
}
