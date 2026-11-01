package com.clearcap.session

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import com.clearcap.asr.EngineLoadException
import com.clearcap.asr.LiveEvent
import com.clearcap.asr.LiveRecognizer
import com.clearcap.asr.NearLiveRecognizer
import com.clearcap.asr.OfflineDecoder
import com.clearcap.asr.SAMPLE_RATE
import com.clearcap.asr.Speech
import com.clearcap.asr.SpeechSegmenter
import com.clearcap.asr.StreamingRecognizer
import com.clearcap.capture.AudioCapture
import com.clearcap.capture.AudioChunk
import com.clearcap.capture.CaptureException
import com.clearcap.capture.CaptureKind
import com.clearcap.data.AppSettings
import com.clearcap.data.TranscriptRepository
import com.clearcap.data.db.SessionEntity
import com.clearcap.dsp.AutoGain
import com.clearcap.dsp.PcmSpool
import com.clearcap.dsp.PcmSpoolWriter
import com.clearcap.models.DeviceInfo
import com.clearcap.models.DeviceInfoReader
import com.clearcap.models.DeviceProfile
import com.clearcap.models.ModelCatalog
import com.clearcap.models.ModelInfo
import com.clearcap.models.ModelManager
import com.clearcap.pipeline.EngineRouter
import com.clearcap.pipeline.LiveMode
import com.clearcap.pipeline.RefinementPlan
import com.clearcap.pipeline.RefinementPlanner
import com.clearcap.pipeline.Route
import com.clearcap.text.SegmentTextPipeline
import com.clearcap.text.SpeechFilters
import com.clearcap.text.TranscriptText
import com.clearcap.transcript.CaptionReconciler
import com.clearcap.transcript.DiagnosticsSnapshot
import com.clearcap.transcript.LanguageMode
import com.clearcap.transcript.Segment
import com.clearcap.transcript.SegmentAligner
import com.clearcap.transcript.SessionStatus
import com.clearcap.transcript.TextVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one recording from start to a saved transcript.
 *
 * capture thread -> bounded channel (drops the OLDEST chunk when the phone cannot keep up, and counts it)
 *   -> recognition thread -> reconciler (no duplicates) -> Room (every finished segment is saved immediately).
 * Audio is written to the private spool only if the user turned that on. If anything fails the segments saved so
 * far remain, and a spooled recording can be transcribed again.
 */
class SessionController(
    private val ctx: Context,
    private val repo: TranscriptRepository,
    private val models: ModelManager,
    private val scope: CoroutineScope,
    private val onNotify: (String) -> Unit,
    private val onFinished: (savedId: String?) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "clearcap-recognition") }
    private val dispatcher = worker.asCoroutineDispatcher()

    private lateinit var cfg: AppSettings
    private lateinit var device: DeviceInfo
    private lateinit var route: Route
    private lateinit var pipeline: SegmentTextPipeline
    private lateinit var sessionId: String
    private lateinit var kind: CaptureKind
    private var startedAt = 0L

    private var recognizer: LiveRecognizer? = null
    private var capture: AudioCapture? = null
    private var spool: PcmSpoolWriter? = null
    private var collector: SpeechSegmenter? = null
    private val speechRam = ArrayList<Speech>()
    private var speechRamMs = 0L
    private val reconciler = CaptionReconciler()
    private val languages = HashMap<String, Int>()

    private val channel = Channel<AudioChunk>(100, BufferOverflow.DROP_OLDEST) { droppedMs.addAndGet(it.raw.size * 1000L / SAMPLE_RATE) }
    private val droppedMs = AtomicLong(0)
    @Volatile private var processedMs = 0L
    private var job: Job? = null
    private var ticker: Job? = null
    @Volatile private var stopping = false
    @Volatile private var skipRefine = false
    private var engineErrors = 0
    private var firstCaptionAt = -1L
    private var worstLagMs = 0L
    private var lastError = ""
    private var startNanos = 0L
    private var sessionRow: SessionEntity? = null

    val isRunning: Boolean get() = ::sessionId.isInitialized && !stopping

    /** Returns null when recording started, otherwise a message for the user. Nothing is recorded on failure. */
    suspend fun start(kind: CaptureKind, projection: MediaProjection?, settings: AppSettings): String? {
        this.kind = kind
        cfg = settings
        device = DeviceInfoReader.read(ctx)
        SessionBus.reset(Phase.STARTING)

        val installed = models.installed.value
        route = EngineRouter.route(cfg.language, installed, cfg.preferredWhisperId, device)
        if (route.mode == LiveMode.NONE) return route.explanation
        val live = route.liveModel!!
        val fit = DeviceProfile.fit(live, device)
        if (fit.level == com.clearcap.models.FitLevel.BLOCK) return fit.message

        pipeline = repo.pipeline()
        val plan = refinementPlan(audioAvailable = true)
        val threads = DeviceProfile.threads(device.cores)

        recognizer = try {
            withContext(Dispatchers.Default) { buildRecognizer(live, threads, collectSpeech = plan.run && cfg.audioMode == AudioMode.OFF) }
        } catch (e: EngineLoadException) {
            return e.message
        }

        sessionId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        val audioName = if (AudioRetention.spoolEnabled(cfg.audioMode)) "$sessionId.pcm" else null
        spool = audioName?.let { PcmSpoolWriter(File(repo.audioDir, it)) }
        val row = SessionEntity(
            id = sessionId, title = "Recording " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startedAt)),
            startedAt = startedAt, endedAt = null, durationMs = 0, status = SessionStatus.RECORDING.name, source = kind.name,
            languageMode = cfg.language.code, detectedLanguage = null, liveMode = route.mode.name, liveModelId = live.id, refineModelId = null,
            activeVersion = TextVersion.LIVE.name, editedText = null, behindMs = 0, droppedMs = 0, audioFile = audioName, notes = null,
            statusNote = null, imported = false,
        )
        sessionRow = row
        repo.upsertSession(row)

        val cap = AudioCapture(ctx, kind, projection, cfg.autoGain, ::onChunk, ::onCaptureFailure)
        try {
            cap.start()
        } catch (e: CaptureException) {
            cleanupFailedStart()
            return e.userMessage
        }
        capture = cap
        startNanos = System.nanoTime()
        SessionBus.update { it.copy(phase = Phase.RECORDING, route = route.explanation) }
        job = scope.launch(dispatcher) { for (c in channel) process(c) }
        ticker = scope.launch { tick() }
        return null
    }

    private fun refinementPlan(audioAvailable: Boolean): RefinementPlan =
        RefinementPlanner.plan(cfg.refine, cfg.language, route, models.installed.value, audioAvailable, cfg.preferredAccurateId, device)

    private fun buildRecognizer(live: ModelInfo, threads: Int, collectSpeech: Boolean): LiveRecognizer {
        val paths = models.paths(live) ?: throw EngineLoadException("${live.label} is not installed correctly. Open Models and download it again.")
        val vad = models.vadPath()
        return if (route.mode == LiveMode.STREAMING) {
            if (collectSpeech && vad != null) collector = SpeechSegmenter(vad, OfflineDecoder.MAX_SEGMENT_SEC)
            StreamingRecognizer(live, paths, minOf(2, threads))
        } else {
            val seg = SpeechSegmenter(vad ?: throw EngineLoadException("The speech detector is not installed."), NearLiveRecognizer.MAX_SEGMENT_SEC)
            val dec = try { OfflineDecoder(live, paths, cfg.language.whisperLanguage, threads) } catch (e: Exception) { seg.close(); throw e }
            NearLiveRecognizer(dec, seg, if (collectSpeech) ::keepSpeech else null)
        }
    }

    private fun keepSpeech(s: Speech) {
        if (speechRamMs + (s.endMs - s.startMs) > MAX_RAM_SPEECH_MS) return
        speechRamMs += s.endMs - s.startMs
        speechRam.add(s)
    }

    // ---- audio in (capture thread) ----

    private fun onChunk(c: AudioChunk) {
        spool?.write(c.raw, c.raw.size)
        channel.trySend(c)
    }

    private fun onCaptureFailure(e: CaptureException) {
        lastError = e.userMessage
        scope.launch { finish(failure = e.userMessage) }
    }

    // ---- recognition (recognition thread) ----

    private suspend fun process(c: AudioChunk) {
        val rec = recognizer ?: return
        try {
            val events = rec.accept(c.processed, c.processed.size, c.startMs)
            engineErrors = 0
            events.forEach { handle(it) }
            collector?.feed(c.processed, c.processed.size, c.startMs)?.forEach { keepSpeech(it) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            engineErrors++
            lastError = "Recognition error: ${t.message ?: t.javaClass.simpleName}"
            if (engineErrors == 1) recover()  // one automatic reload of the engine
            else if (engineErrors > 5) { scope.launch { finish(failure = "Speech recognition kept failing. Your captions so far are saved.") }; return }
        }
        processedMs = c.startMs + c.processed.size * 1000L / SAMPLE_RATE
        val lag = (capture?.stats()?.capturedMs ?: 0L) - processedMs
        if (lag > worstLagMs) worstLagMs = lag
    }

    /** Reloads the live engine once after a failure. Audio that arrives meanwhile waits in the bounded channel. */
    private fun recover() {
        val old = recognizer
        runCatching { old?.close() }
        recognizer = null
        try {
            recognizer = buildRecognizer(route.liveModel!!, DeviceProfile.threads(device.cores), collectSpeech = false)
        } catch (e: Exception) {
            lastError = e.message ?: "Could not reload the speech engine."
        }
    }

    private suspend fun handle(e: LiveEvent) {
        when (e) {
            is LiveEvent.Partial -> {
                if (firstCaptionAt < 0) firstCaptionAt = System.nanoTime()
                SessionBus.update { it.copy(partial = TranscriptText.casing(e.text)) }
            }
            is LiveEvent.Final -> {
                if (firstCaptionAt < 0) firstCaptionAt = System.nanoTime()
                val clean = pipeline.process(e.raw, e.endMs - e.startMs)
                if (clean.isEmpty()) { SessionBus.update { it.copy(partial = "") }; return }
                e.language?.let { languages.merge(it, 1, Int::plus) }
                val seg = reconciler.onFinal(clean, e.startMs, e.endMs, e.language, e.raw)
                if (seg != null) repo.upsertSegment(sessionId, seg)
                val lines = reconciler.segments.takeLast(MAX_LINES).map { it.liveText }
                SessionBus.update { it.copy(partial = "", lines = lines) }
            }
        }
    }

    // ---- state ticks ----

    private suspend fun tick() {
        var lastSave = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val st = capture?.stats()
            if (st != null) {
                val behind = (st.capturedMs - processedMs).coerceAtLeast(0)
                val peak = st.level
                val msg = silenceNotice(st)
                SessionBus.update {
                    it.copy(
                        level = peak, elapsedMs = st.capturedMs, behindMs = behind, droppedMs = droppedMs.get(),
                        listening = recognizer?.busy == true, status = msg ?: it.status.takeIf { s -> s?.startsWith("Paused") == true },
                        diagnostics = snapshot(st),
                    )
                }
                onNotify(if (SessionBus.state.value.phase == Phase.PAUSED) "Paused" else "Listening. ${st.capturedMs / 60_000} min ${st.capturedMs / 1000 % 60} s")
                val now = System.currentTimeMillis()
                if (now - lastSave > 5_000) {
                    lastSave = now
                    sessionRow?.let { r ->
                        sessionRow = r.copy(durationMs = st.capturedMs, behindMs = behind, droppedMs = droppedMs.get())
                        repo.upsertSession(sessionRow!!)
                    }
                    spool?.flush()
                }
            }
            delay(250)
        }
    }

    private fun silenceNotice(st: com.clearcap.capture.CaptureStats): String? = when {
        st.clientSilenced -> "Android has silenced this recording (another app, such as a call, has priority). Captions will resume when it ends."
        st.silentMs > 10_000 && kind == CaptureKind.SYSTEM ->
            "No audio heard for ${st.silentMs / 1000} s. If a video or call is playing, its app may block capture; try the microphone."
        st.silentMs > 10_000 -> "No sound from the microphone for ${st.silentMs / 1000} s."
        st.clippingWarnings > 0 && st.level > 0.98f -> "Audio is clipping (too loud); text may be less accurate."
        else -> null
    }

    private fun snapshot(st: com.clearcap.capture.CaptureStats): DiagnosticsSnapshot {
        val rec = recognizer
        val ttfc = if (firstCaptionAt > 0) (firstCaptionAt - startNanos) / 1_000_000 else -1L
        return DiagnosticsSnapshot(
            appVersion = appVersion(), androidSdk = Build.VERSION.SDK_INT, device = "${Build.MANUFACTURER} ${Build.MODEL}",
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), totalRamMb = device.totalRamMb, source = kind.name,
            captureRate = st.format?.rate ?: 0, captureChannels = st.format?.channels ?: 0, captureBufferFrames = st.bufferFrames,
            usedSoftwareResampler = st.format?.needsConversion == true, chunksRead = st.chunksRead, chunksDropped = droppedMs.get() / 100,
            readErrors = st.readErrors, clientSilenced = st.clientSilenced, clippingWarnings = st.clippingWarnings, silentMs = st.silentMs,
            liveMode = route.mode.name, liveModel = route.liveModel?.id.orEmpty(), timeToFirstCaptionMs = ttfc, worstLagMs = worstLagMs,
            segments = reconciler.segments.size, lastError = lastError,
            notes = listOfNotNull(
                rec?.let { r -> if (r.decodedAudioMs > 2_000) "Measured on this phone: ${"%.2f".format(r.decodedAudioMs / (r.decodeNs / 1_000_000.0).coerceAtLeast(1.0))}x real time (decoding speed)" else null },
                if (st.reopened > 0) "Capture reopened ${st.reopened} time(s) after the audio route changed" else null,
            ),
        )
    }

    private fun appVersion() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    // ---- pause / stop ----

    fun pause() {
        capture?.paused = true
        SessionBus.update { it.copy(phase = Phase.PAUSED, status = "Paused. Nothing is recorded or transcribed until you resume.") }
    }

    fun resume() {
        capture?.paused = false
        SessionBus.update { it.copy(phase = Phase.RECORDING, status = null) }
    }

    val paused: Boolean get() = capture?.paused == true

    fun skipRefinement() { skipRefine = true }

    /** User tapped Stop. Saves the transcript, optionally runs the second pass, and returns the session id. */
    suspend fun stop(): String? = finish(failure = null)

    /** Best effort when the service is destroyed without a normal stop. */
    suspend fun abort() { if (isRunning) finish(failure = "Recording was stopped by Android. Captions saved so far are kept.") }

    private suspend fun finish(failure: String?): String? {
        synchronized(this) { if (stopping || !::sessionId.isInitialized) return null; stopping = true }
        ticker?.cancel()
        SessionBus.update { it.copy(phase = Phase.FINALIZING, partial = "", listening = false, error = failure, status = if (failure == null) "Saving..." else null) }
        capture?.stop()
        channel.close()
        val finalMs = capture?.stats()?.capturedMs ?: 0L
        var ok = true
        try {
            job?.join()  // drain what is already captured
            withContext(dispatcher) {
                recognizer?.finish(finalMs)?.forEach { handle(it) }
                collector?.flush()?.forEach { keepSpeech(it) }
            }
        } catch (t: Throwable) { ok = false; lastError = t.message ?: t.javaClass.simpleName }
        runCatching { spool?.close() }
        runCatching { recognizer?.close() }   // free the live model before a larger second-pass model is loaded
        runCatching { collector?.close() }
        recognizer = null

        val segs = reconciler.segments
        var row = (sessionRow ?: repo.getSession(sessionId)!!).copy(
            durationMs = finalMs, endedAt = System.currentTimeMillis(), droppedMs = droppedMs.get(),
            status = SessionStatus.FINALIZING.name, detectedLanguage = languages.maxByOrNull { it.value }?.key,
            behindMs = worstLagMs, statusNote = failure,
        )
        repo.upsertSession(row)

        var refinedOk = failure == null && ok
        if (failure == null) {
            val audio = spoolFile()
            val plan = refinementPlan(audioAvailable = audio != null || speechRam.isNotEmpty())
            val pm = plan.model
            if (plan.run && pm != null && !skipRefine) {
                try {
                    val r = refine(pm, audio, segs)
                    row = row.copy(
                        refineModelId = pm.id,
                        activeVersion = if (r.accepted > 0) TextVersion.REFINED.name else TextVersion.LIVE.name,
                        statusNote = "Second pass (${pm.label}): ${r.accepted} segment(s) improved, ${r.rejected} kept the original after safety checks. The original text is kept and can be restored.",
                    )
                } catch (e: Throwable) {
                    refinedOk = false
                    row = row.copy(statusNote = "Second pass could not run (${e.message ?: e.javaClass.simpleName}). The original captions are saved.")
                }
            }
        }
        speechRam.clear()
        row = row.copy(status = if (failure != null && segs.isEmpty()) SessionStatus.FAILED.name else if (failure != null) SessionStatus.INTERRUPTED.name else SessionStatus.COMPLETE.name)
        repo.upsertSession(row)
        val success = failure == null && ok && refinedOk
        if (AudioRetention.deleteAfterFinalize(cfg.audioMode, success && row.status == SessionStatus.COMPLETE.name)) repo.deleteAudioOf(sessionId)

        val keep = segs.isNotEmpty() || failure == null
        SessionBus.update { it.copy(phase = Phase.IDLE, progress = null, savedId = if (keep) sessionId else null, status = null, level = 0f) }
        onFinished(if (keep) sessionId else null)
        worker.shutdown()
        return sessionId
    }

    private fun spoolFile(): File? = File(repo.audioDir, "$sessionId.pcm").takeIf { spool != null && it.exists() && PcmSpool.durationMs(it) >= AudioRetention.MIN_USEFUL_AUDIO_MS }

    private suspend fun refine(model: ModelInfo, audio: File?, live: List<Segment>): SegmentAligner.Result = withContext(Dispatchers.Default) {
        SessionBus.update { it.copy(phase = Phase.REFINING, status = null, progress = 0f, progressLabel = "Loading ${model.label}") }
        val paths = models.paths(model) ?: throw IllegalStateException("${model.label} is not installed correctly")
        val dec = OfflineDecoder(model, paths, if (model.multilingual) cfg.language.whisperLanguage else "", DeviceProfile.threads(device.cores))
        val pieces = ArrayList<SegmentAligner.Piece>()
        try {
            fun handleSpeech(s: Speech) {
                val f = s.floats()
                if (!SpeechFilters.isSpeechLike(f)) return
                val d = dec.decode(f)
                val t = pipeline.process(d.text, s.endMs - s.startMs)
                if (t.isNotEmpty()) pieces.add(SegmentAligner.Piece(s.startMs, s.endMs, t))
            }
            val total = maxOf(1L, if (audio != null) PcmSpool.durationMs(audio) else speechRamMs)
            if (audio != null) {
                val vad = models.vadPath() ?: throw IllegalStateException("The speech detector is not installed")
                val seg = SpeechSegmenter(vad, OfflineDecoder.MAX_SEGMENT_SEC)
                val gain = AutoGain()
                try {
                    PcmSpool.read(audio) { block, n, startSample ->
                        if (skipRefine || !isActive) return@read false
                        val b = block.copyOf(n).also { if (cfg.autoGain) gain.apply(it) }
                        seg.feed(b, n, startSample * 1000L / SAMPLE_RATE).forEach { handleSpeech(it) }
                        progress(model, (startSample * 1000L / SAMPLE_RATE).toFloat() / total)
                        true
                    }
                    if (!skipRefine) seg.flush().forEach { handleSpeech(it) }
                } finally { seg.close() }
            } else {
                var done = 0L
                for (s in speechRam) {
                    if (skipRefine || !isActive) break
                    handleSpeech(s)
                    done += s.endMs - s.startMs
                    progress(model, done.toFloat() / total)
                }
            }
        } finally { dec.close() }
        if (skipRefine) return@withContext SegmentAligner.Result(live, 0, 0, 0)
        val merged = SegmentAligner.merge(live, pieces)
        repo.replaceSegments(sessionId, merged.segments)
        merged
    }

    private fun progress(model: ModelInfo, p: Float) =
        SessionBus.update { it.copy(progress = p.coerceIn(0f, 1f), progressLabel = "Checking the recording with ${model.label}: ${(p * 100).toInt()}%") }

    private suspend fun cleanupFailedStart() {
        runCatching { recognizer?.close() }; recognizer = null
        runCatching { collector?.close() }
        runCatching { spool?.close() }
        runCatching { repo.delete(sessionId) }
        worker.shutdown()
    }

    companion object {
        const val MAX_LINES = 60
        const val MAX_RAM_SPEECH_MS = 40 * 60_000L
    }
}
