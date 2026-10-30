package com.clearcap.session

import android.os.SystemClock
import com.clearcap.App
import com.clearcap.capture.AudioCaptureManager
import com.clearcap.capture.AutoGain
import com.clearcap.data.AppSettings
import com.clearcap.data.LiveMode
import com.clearcap.data.Source
import com.clearcap.data.Transcript
import com.clearcap.engine.AccurateEngine
import com.clearcap.engine.LiveEngine
import com.clearcap.engine.LiveUpdate
import com.clearcap.engine.Models
import com.clearcap.engine.Speech
import com.clearcap.engine.SpeechCollector
import com.clearcap.text.RefinedSegment
import com.clearcap.text.SpeechFilters
import com.clearcap.text.Timed
import com.clearcap.text.TranscriptText
import com.clearcap.text.VocabularyCorrector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One recording. While it runs, ONLY the light streaming engine (and the speech detector) work, so captions
 * stay real time. The heavier accurate model runs after you stop, never at the same time.
 * Audio exists only in memory and is dropped when the session ends.
 */
class RecordingSession(
    private val app: App,
    private val capture: AudioCaptureManager,
    private val cfg: AppSettings,
    private val notify: (String) -> Unit,
) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "clearcap-live") }
    private val worker = exec.asCoroutineDispatcher()
    private val corrector = VocabularyCorrector(app.dictionary.words.value)
    private val lines = ArrayList<Timed>()
    private val texts = ArrayList<String>()

    // ACCURATE live mode: the speech detector cuts sentences, a strong offline model decodes each one on its own
    // thread a few seconds later. FAST mode: one streaming model, instant but weaker.
    private val accurateLive = cfg.liveMode == LiveMode.ACCURATE
    private val decodeExec = Executors.newSingleThreadExecutor { r -> Thread(r, "clearcap-decode") }
    private val queue = LinkedBlockingQueue<Speech>()
    private val pendingMs = AtomicLong(0)
    @Volatile private var decoding = true
    private var strong: AccurateEngine? = null
    private var live: LiveEngine? = null
    private var collector: SpeechCollector? = null
    private var job: Job? = null
    private var processed = 0L
    private var segStart = -1L
    private var t0 = 0L
    private var worstLag = 0L
    @Volatile private var cancelAccurate = false

    /** Returns an error message, or null when the engines are ready and capture was started. */
    fun start(scope: CoroutineScope, includeSystem: Boolean, includeMic: Boolean): String? {
        val err = if (accurateLive) loadAccurateLive() else loadFast()
        if (err != null) return err

        t0 = SystemClock.elapsedRealtime()
        // Subscribe BEFORE capture starts so the first chunk is not lost.
        job = scope.launch(worker) {
            capture.chunks.collect { c -> onChunk(c.samples, c.length) }
        }
        if (accurateLive) decodeExec.execute(::decodeLoop)
        val ok = when {
            includeSystem && includeMic -> capture.startSystemAndMicrophoneCapture()
            includeSystem -> capture.startSystemAudioCapture()
            else -> capture.startMicrophoneCapture()
        }
        if (!ok) {
            job?.cancel()
            decoding = false
            release()
            return "" // the capture manager reports the reason through its error flow
        }
        return null
    }

    private fun loadFast(): String? {
        val files = app.models.liveFiles()?.takeIf { app.models.isInstalled(Models.live) }
            ?: return "Download the live model in Settings first (or choose Accurate live captions)."
        live = try {
            LiveEngine(files, threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 3))
        } catch (e: Throwable) {
            return "Could not load the live model: ${e.message}"
        }
        collector = makeCollector()
        return null
    }

    private fun loadAccurateLive(): String? {
        val spec = Models.accurate.firstOrNull { it.id == cfg.accurateModelId } ?: Models.parakeet600
        val m = app.models
        val files = m.accurateFiles(spec)?.takeIf { m.isInstalled(spec) }
        if (files == null || !m.isInstalled(Models.vad)) {
            return "Download ${spec.label} and the speech detector in Settings first, or switch Live captions to Fast."
        }
        strong = try {
            AccurateEngine(spec, files, cfg.language, Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        } catch (e: Throwable) {
            return "Could not load ${spec.label}: ${e.message}"
        }
        collector = try {
            SpeechCollector(m.vadPath(), keep = false)
        } catch (e: Throwable) {
            return "Could not load the speech detector: ${e.message}"
        }
        return null
    }

    private fun makeCollector(): SpeechCollector? {
        if (!cfg.accurate) return null
        val m = app.models
        val spec = Models.accurate.firstOrNull { it.id == cfg.accurateModelId } ?: Models.parakeet
        if (!m.isInstalled(spec) || !m.isInstalled(Models.vad)) {
            SessionBus.update { it.copy(status = "Accurate transcript is on, but its model or the speech detector is not downloaded. Only the live transcript will be saved.") }
            return null
        }
        return runCatching { SpeechCollector(m.vadPath()) }.getOrNull()
    }

    // ---- runs on the worker thread ----

    private fun onChunk(samples: ShortArray, length: Int) {
        try {
            if (accurateLive) {
                collector?.feed(samples, length)
                collector?.takeNew()?.forEach(::enqueue)
            } else {
                val lv = live ?: return
                lv.accept(samples, length)?.let(::onLive)
                collector?.feed(samples, length)
            }
        } catch (e: Throwable) {
            SessionBus.update { it.copy(error = "Recognizer error: ${e.message}") }
        }
        processed += length
        val now = SystemClock.elapsedRealtime()
        val audioMs = processed * 1000L / 16_000
        val lag = if (accurateLive) pendingMs.get() else (now - t0 - audioMs).coerceAtLeast(0)
        worstLag = maxOf(worstLag, lag)
        val level = (AutoGain.rms(samples, length) * 5f).coerceIn(0f, 1f)
        SessionBus.update { it.copy(level = level, elapsedMs = now - t0, behindMs = lag) }
    }

    private fun onLive(u: LiveUpdate) {
        when (u) {
            is LiveUpdate.Partial -> {
                if (segStart < 0 && u.text.isNotEmpty()) segStart = posMs()
                SessionBus.update { it.copy(partial = TranscriptText.casing(u.text)) }
            }
            is LiveUpdate.Final -> addFinal(u.text)
        }
    }

    private fun addFinal(raw: String) {
        val s = TranscriptText.liveSentence(raw, corrector)
        val start = if (segStart >= 0) segStart else posMs()
        segStart = -1
        if (s.isNotEmpty()) addTimed(Timed(start, posMs(), s), s)
        SessionBus.update { it.copy(partial = "") }
    }

    private fun posMs() = processed * 1000L / 16_000

    // ---- ACCURATE live mode ----

    private fun enqueue(sp: Speech) {
        pendingMs.addAndGet(sp.endMs - sp.startMs)
        queue.put(sp)
    }

    /** Decodes finished sentences one at a time with the strong model; runs until stopped and drained. */
    private fun decodeLoop() {
        val engine = strong ?: return
        while (true) {
            val sp = queue.poll(200, TimeUnit.MILLISECONDS)
            if (sp == null) {
                if (!decoding) break
                continue
            }
            try {
                val dur = sp.endMs - sp.startMs
                val floats = sp.floats()
                if (SpeechFilters.isSpeechLike(floats)) {
                    val clean = SpeechFilters.cleanUp(engine.transcribe(floats), dur)
                    val text = TranscriptText.sentenceCase(corrector.correct(clean)).trim()
                    if (text.isNotEmpty()) addTimed(Timed(sp.startMs, sp.endMs, text), text)
                }
            } catch (e: Throwable) {
                SessionBus.update { it.copy(error = "Recognizer error: ${e.message}") }
            } finally {
                pendingMs.addAndGet(-(sp.endMs - sp.startMs))
            }
        }
    }

    private fun addTimed(t: Timed, text: String) {
        val snap = synchronized(lines) { lines.add(t); texts.add(text); texts.toList() }
        SessionBus.update { it.copy(lines = snap) }
    }

    // ---- stop ----

    fun cancelAccurate() { cancelAccurate = true }

    /** Ends capture, saves the live transcript, then (optionally) makes the accurate one. */
    suspend fun stop() {
        capture.stopCapture()
        delay(350) // let the last chunks reach the worker
        job?.cancelAndJoin()
        withContext(worker) {
            live?.finish()?.takeIf { it.isNotBlank() }?.let { addFinal(it) }
        }
        SessionBus.update { it.copy(running = false, partial = "", level = 0f) }
        if (accurateLive) finishAccurateLive()
        val speech = if (accurateLive) emptyList() else collector?.finish().orEmpty()
        val cut = if (accurateLive) null else collector?.truncatedAtMs
        val liveText = synchronized(lines) { TranscriptText.join(lines.toList()) }
        if (liveText.isBlank() && speech.isEmpty()) {
            SessionBus.update { it.copy(status = "No speech was captured, so nothing was saved.") }
            release()
            return
        }
        val t = Transcript(
            id = UUID.randomUUID().toString(),
            title = "Recording " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()),
            createdAt = System.currentTimeMillis(),
            durationMs = SystemClock.elapsedRealtime() - t0,
            live = liveText, accurate = null, edited = null, notes = null,
            source = cfg.source.name.lowercase(), behindMs = worstLag,
        )
        app.store.save(t)
        SessionBus.update { it.copy(savedId = t.id) }
        if (speech.isNotEmpty()) accurate(t, speech, cut)
        release()
    }

    /** Flush the speech detector, then wait for the strong model to finish the sentences still queued. */
    private suspend fun finishAccurateLive() {
        withContext(worker) {
            collector?.finish()
            collector?.takeNew()?.forEach(::enqueue)
        }
        SessionBus.update { it.copy(progress = 0f, progressLabel = "Finishing the last sentences") }
        val total = pendingMs.get().coerceAtLeast(1)
        while (pendingMs.get() > 0 && !cancelAccurate) {
            val p = 1f - pendingMs.get().toFloat() / total
            SessionBus.update { it.copy(progress = p.coerceIn(0f, 1f)) }
            delay(200)
        }
        decoding = false
        if (cancelAccurate) queue.clear()
        decodeExec.shutdown()
        decodeExec.awaitTermination(30, TimeUnit.SECONDS)
        SessionBus.update { it.copy(progress = null) }
    }

    private suspend fun accurate(t: Transcript, speech: List<Speech>, cut: Long?) {
        val spec = Models.accurate.firstOrNull { it.id == cfg.accurateModelId } ?: Models.parakeet
        val files = app.models.accurateFiles(spec)
        if (files == null) {
            SessionBus.update { it.copy(status = "Accurate model files are missing; kept the live transcript.") }
            return
        }
        val out = withContext(worker) { runAccurate(spec, files, speech) }
        if (out == null) {
            SessionBus.update { it.copy(progress = null, status = "Could not finish the accurate transcript; kept the live one.") }
            return
        }
        val tail = if (cut != null) lines.filter { it.startMs >= cut } else emptyList()
        val text = TranscriptText.join(out.map { Timed(it.startMs, it.endMs, it.text) } + tail)
        if (text.isBlank()) {
            SessionBus.update { it.copy(progress = null, status = "The accurate pass found no clear speech; kept the live transcript.") }
            return
        }
        app.store.save(t.copy(accurate = text))
        SessionBus.update { it.copy(progress = null, status = "Saved the accurate transcript.") }
    }

    private fun runAccurate(spec: com.clearcap.engine.ModelSpec, files: com.clearcap.engine.ModelFiles, speech: List<Speech>): List<RefinedSegment>? {
        val engine = try {
            AccurateEngine(spec, files, cfg.language, Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        } catch (e: Throwable) {
            return null
        }
        try {
            val pieces = ArrayList<RefinedSegment>()
            for ((i, sp) in speech.withIndex()) {
                if (cancelAccurate) return null
                val raw = engine.transcribe(sp.floats())
                val clean = SpeechFilters.cleanUp(raw, sp.endMs - sp.startMs)
                val fixed = corrector.correct(clean).trim()
                if (fixed.isNotEmpty()) pieces.add(RefinedSegment(sp.startMs, sp.endMs, fixed))
                val p = (i + 1f) / speech.size
                SessionBus.update { it.copy(progress = p, progressLabel = "${i + 1} of ${speech.size}") }
                notify("Making the accurate transcript ${(p * 100).toInt()}%")
            }
            return SpeechFilters.mergeIntoParagraphs(pieces)
        } catch (e: Throwable) {
            return null
        } finally {
            engine.close()
        }
    }

    private fun release() {
        runCatching { live?.close() }
        runCatching { strong?.close() }
        runCatching { collector?.release() }
        live = null
        strong = null
        collector = null
        decodeExec.shutdown()
        exec.shutdown()
    }

    /** Hard stop without saving (service destroyed). */
    fun abort() {
        decoding = false
        queue.clear()
        capture.stopCapture()
        job?.cancel()
        release()
    }
}
