package com.lecturecaption.app.stt

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * OPTIONAL online "accurate" pass. Same idea as [WhisperRefiner] - the live Vosk caption is untouched,
 * and when recording ends the saved transcript can be replaced by a better one - but the speech is
 * transcribed by Google's Gemini API instead of a model on the phone. Much better with jargon,
 * names, accents and Hindi + English, and it uses almost no battery or CPU.
 *
 * PRIVACY: this is the one place lecture AUDIO leaves the device. It only runs when the user turned
 * Online mode on, accepted the warning, and saved their own Gemini key. Only detected speech is sent
 * (silence and noise are not), over HTTPS, and nothing is written to disk.
 *
 * If anything goes wrong (no internet, bad key, rate limit, timeout) [finish] returns null and the
 * live transcript is kept - never a transcript with holes.
 */
class OnlineRefiner(
    private val vadModel: File,
    private val transcriber: GeminiTranscriber,
    /** "" = English + Hindi mix; "en" = English only. */
    private val language: String,
    private val hints: List<String>,
    private val textProcessor: (String) -> String = { it }
) : Refiner {

    override val label = "online mode"
    @Volatile override var failureReason: String? = null
        private set

    private val sampleRate = 16_000
    private val inbox = Channel<ShortArray>(Channel.UNLIMITED)
    private val batchQueue = Channel<SpeechBatch>(Channel.UNLIMITED)
    private val batcher = SegmentBatcher(sampleRate)          // only touched on the VAD thread

    private val vadExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "OnlineVad") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vad: Vad? = null
    private var vadLoopJob: Job? = null
    private var workerJob: Job? = null

    private val closed = AtomicBoolean(false)
    private val pendingSamples = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)
    @Volatile private var lost = false     // a batch failed or was dropped
    @Volatile private var fatal = false    // stop sending (bad key, repeated failures)
    private val results = ArrayList<RefinedSegment>()

    private val _remaining = MutableStateFlow(0)
    override val remaining: StateFlow<Int> = _remaining

    /** Loads the small speech detector. Call off the main thread. */
    fun load() {
        require(vadModel.exists()) { "Speech detector model is missing. Download it in Settings." }
        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.6f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 30f
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )
    }

    override fun start() {
        val v = vad ?: return

        workerJob = scope.launch {
            val gate = Semaphore(MAX_PARALLEL_REQUESTS)
            coroutineScope {
                for (batch in batchQueue) {
                    gate.acquire()
                    launch {
                        try { process(batch) } finally { gate.release() }
                    }
                }
            }
        }

        vadLoopJob = scope.launch(vadExecutor.asCoroutineDispatcher()) {
            for (chunk in inbox) {
                v.acceptWaveform(FloatArray(chunk.size) { chunk[it] / 32768f })
                drainVad(v)
            }
            v.flush()
            drainVad(v)
            batcher.flush()?.let { enqueue(it) }
            batchQueue.close()
        }
    }

    private fun drainVad(v: Vad) {
        while (!v.empty()) {
            val seg = v.front()
            v.pop()
            val size = seg.samples.size
            if (size < sampleRate / 8) continue
            if (!SpeechFilters.isSpeechLike(seg.samples)) continue // noise, not speech
            val done = batcher.add(seg.start * 1000L / sampleRate, seg.samples)
            if (done != null) enqueue(done)
        }
    }

    private fun enqueue(batch: SpeechBatch) {
        if (fatal || pendingSamples.get() + batch.samples.size > MAX_PENDING_SAMPLES) {
            lost = true // can't send (offline / key problem) and can't keep growing memory
            return
        }
        pendingSamples.addAndGet(batch.samples.size.toLong())
        _remaining.update { it + 1 }
        batchQueue.trySend(batch)
    }

    private suspend fun process(batch: SpeechBatch) {
        try {
            if (fatal) { lost = true; return }
            val raw = withContext(Dispatchers.IO) { transcribeWithRetry(batch.samples) }
            // The model sometimes recites the spelling hints on noise or after the speech: drop that.
            val prompt = DictionaryPrompt.build(hints)
            val speech = if (DictionaryEchoFilter.matchesPrompt(raw, prompt)) ""
            else DictionaryEchoFilter.stripTrailingEcho(raw, prompt, hints)
            val text = textProcessor(SpeechFilters.cleanUp(speech, batch.endMs - batch.startMs)).trim()
            if (text.isNotEmpty()) synchronized(results) { results.add(RefinedSegment(batch.startMs, batch.endMs, text)) }
            consecutiveFailures.set(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiTranscriber.TranscribeException) {
            lost = true
            if (failureReason == null) failureReason = e.message
            if (!e.retryable || consecutiveFailures.incrementAndGet() >= 3) fatal = true
        } catch (e: Throwable) {
            lost = true
            if (failureReason == null) failureReason = e.message ?: "Unexpected error"
            if (consecutiveFailures.incrementAndGet() >= 3) fatal = true
        } finally {
            pendingSamples.addAndGet(-batch.samples.size.toLong())
            _remaining.update { maxOf(0, it - 1) }
        }
    }

    private suspend fun transcribeWithRetry(samples: FloatArray): String {
        var attempt = 0
        while (true) {
            try {
                return transcriber.transcribe(samples, language, hints)
            } catch (e: GeminiTranscriber.TranscribeException) {
                attempt++
                if (!e.retryable || attempt >= MAX_ATTEMPTS) throw e
                delay(if (e.rateLimited) 20_000L else 3_000L * attempt)
            }
        }
    }

    override fun feed(samples: ShortArray, length: Int) {
        if (closed.get() || length <= 0) return
        inbox.trySend(samples.copyOf(length))
    }

    override suspend fun finish(): List<RefinedSegment>? {
        closed.set(true)
        inbox.close()
        val done = withTimeoutOrNull(FINISH_TIMEOUT_MS) {
            vadLoopJob?.join()
            workerJob?.join()
            true
        }
        if (done == null) {
            lost = true
            if (failureReason == null) failureReason = "Timed out waiting for Google."
            vadLoopJob?.cancel()
            workerJob?.cancel()
            vadLoopJob?.join()
            workerJob?.join()
        }
        release()
        if (lost) return null
        val sorted = synchronized(results) { results.sortedBy { it.startMs } }
        return if (sorted.isEmpty()) null else SpeechFilters.mergeIntoParagraphs(sorted)
    }

    override fun abort() {
        closed.set(true)
        inbox.close()
        batchQueue.close()
        vadLoopJob?.cancel()
        workerJob?.cancel()
        release()
    }

    private fun release() {
        try { vad?.release() } catch (_: Throwable) {}
        vad = null
        vadExecutor.shutdown()
        scope.cancel()
    }

    companion object {
        private const val MAX_PARALLEL_REQUESTS = 2
        private const val MAX_ATTEMPTS = 3
        /** ~10 minutes of unsent audio (~38 MB). Beyond that we're clearly offline/blocked. */
        private const val MAX_PENDING_SAMPLES = 16_000L * 60 * 10
        private const val FINISH_TIMEOUT_MS = 10 * 60_000L

        /** True when the phone currently has a working internet connection. */
        fun isNetworkAvailable(context: Context): Boolean = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) { false }
    }
}
