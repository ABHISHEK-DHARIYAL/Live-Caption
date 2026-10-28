package com.clearcap.session

import android.os.SystemClock
import com.clearcap.App
import com.clearcap.capture.AudioCaptureManager
import com.clearcap.capture.AutoGain
import com.clearcap.data.AppSettings
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
        val files = app.models.liveFiles()?.takeIf { app.models.isInstalled(Models.live) }
            ?: return "Download the live model in Settings first."
        live = try {
            LiveEngine(files, threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 3))
        } catch (e: Throwable) {
            return "Could not load the live model: ${e.message}"
        }
        collector = makeCollector()

        t0 = SystemClock.elapsedRealtime()
        // Subscribe BEFORE capture starts so the first chunk is not lost.
        job = scope.launch(worker) {
            capture.chunks.collect { c -> onChunk(c.samples, c.length) }
        }
        val ok = when {
            includeSystem && includeMic -> capture.startSystemAndMicrophoneCapture()
            includeSystem -> capture.startSystemAudioCapture()
            else -> capture.startMicrophoneCapture()
        }
        if (!ok) {
            job?.cancel()
            release()
            return "" // the capture manager reports the reason through its error flow
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
        val lv = live ?: return
        try {
            lv.accept(samples, length)?.let(::onLive)
            collector?.feed(samples, length)
        } catch (e: Throwable) {
            SessionBus.update { it.copy(error = "Recognizer error: ${e.message}") }
        }
        processed += length
        val now = SystemClock.elapsedRealtime()
        val audioMs = processed * 1000L / 16_000
        val lag = (now - t0 - audioMs).coerceAtLeast(0)
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
        if (s.isNotEmpty()) {
            lines.add(Timed(start, posMs(), s))
            texts.add(s)
        }
        val snap = texts.toList()
        SessionBus.update { it.copy(partial = "", lines = snap) }
    }

    private fun posMs() = processed * 1000L / 16_000

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
        val speech = collector?.finish().orEmpty()
        val cut = collector?.truncatedAtMs
        val liveText = TranscriptText.join(lines)
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
        runCatching { collector?.release() }
        live = null
        collector = null
        exec.shutdown()
    }

    /** Hard stop without saving (service destroyed). */
    fun abort() {
        capture.stopCapture()
        job?.cancel()
        release()
    }
}
