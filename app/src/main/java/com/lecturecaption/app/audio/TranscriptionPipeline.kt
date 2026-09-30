package com.lecturecaption.app.audio

import com.lecturecaption.app.data.repository.LectureRepository
import com.lecturecaption.app.stt.RecognitionResult
import com.lecturecaption.app.stt.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * System Audio -> AudioCapture -> PCM chunk queue -> Speech Recognition (ONE consumer, in order)
 * -> Transcript Segment -> Room -> Live Caption.
 *
 * Raw PCM is never written to disk; each chunk is handed to the recognizer and then discarded.
 * A single consumer coroutine feeds the recognizer, because the native Vosk recognizer is not
 * thread-safe and chunks must be processed in order.
 */
class TranscriptionPipeline(
    private val engine: SpeechEngine,
    private val repository: LectureRepository,
    private val scope: CoroutineScope,
    /** Playback speed of the video (1.0, 1.25, 1.5, 2.0). >1 => audio is slowed back before recognition. */
    private val playbackSpeed: Float = 1f
) {
    private val stretcher: TimeStretcher? =
        if (playbackSpeed > 1.05f) TimeStretcher(playbackSpeed) else null

    private var lectureId: Long = -1L
    private var consumerJob: Job? = null
    // ~5 minutes of audio (3000 x 100ms). Dropping audio in the middle of speech is what turns a
    // transcript into word salad, so the queue is deep (it's only ~10 MB) and dropping is a last resort.
    private val queueCapacity = 3000
    private val queue = Channel<PcmChunk>(capacity = queueCapacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Dedicated high-priority thread for speech recognition so it is not starved by the shared pool.
    private val sttExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SttThread").apply { priority = Thread.MAX_PRIORITY }
    }
    private val sttDispatcher = sttExecutor.asCoroutineDispatcher()

    // Timestamps are REAL elapsed time since the session started (not "amount of audio the engine
    // has chewed through", which stalls whenever recognition falls behind and made every line 00:00).
    private var sessionStartMs = 0L
    private var lastSegmentEndMs = 0L

    // Real-time headroom tracking: how much captured audio is waiting to be recognized, and how
    // many ~100ms chunks have been silently dropped because recognition couldn't keep up. Both
    // the Channel's own DROP_OLDEST and this counter drop the SAME chunk, so droppedChunks is an
    // honest count of audio that was actually lost, not just delayed.
    private val queueOccupancy = AtomicInteger(0)

    private val _backlogMs = MutableStateFlow(0L)
    val backlogMs: StateFlow<Long> = _backlogMs

    private val _droppedChunks = MutableStateFlow(0)
    val droppedChunks: StateFlow<Int> = _droppedChunks

    private val _liveCaptionText = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val liveCaptionText: SharedFlow<String> = _liveCaptionText

    private val _segmentCount = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
    val segmentCount: SharedFlow<Int> = _segmentCount
    private var segmentCounter = 0

    /** Loads the speech model (call off the main thread). Throws if the model is missing. */
    suspend fun prepareEngine() {
        if (!engine.isReady()) engine.initialize()
    }

    suspend fun attachLecture(lectureId: Long) {
        this.lectureId = lectureId
        prepareEngine()
        sessionStartMs = SystemClock.elapsedRealtime()
        lastSegmentEndMs = 0L
        consumerJob = scope.launch(sttDispatcher) {
            for (chunk in queue) {
                queueOccupancy.decrementAndGet()
                _backlogMs.value = maxOf(0, queueOccupancy.get()) * 100L
                val st = stretcher
                if (st == null) {
                    engine.acceptAudio(chunk.samples, chunk.length) { result -> handleResult(result) }
                } else {
                    val slowed = st.process(chunk.samples, chunk.length)
                    if (slowed.isNotEmpty()) {
                        engine.acceptAudio(slowed, slowed.size) { result -> handleResult(result) }
                    }
                }
            }
        }
    }

    /** Called for every captured chunk; never blocks. */
    fun onPcmChunk(chunk: PcmChunk) {
        val occ = queueOccupancy.incrementAndGet()
        if (occ > queueCapacity) {
            // The Channel is about to drop its oldest entry to make room for this one — that is
            // real, permanent audio loss, not just a delay. Count it honestly.
            _droppedChunks.value = _droppedChunks.value + 1
            queueOccupancy.decrementAndGet()
        }
        _backlogMs.value = minOf(occ, queueCapacity) * 100L // each chunk is ~100ms of audio
        queue.trySend(chunk)
    }

    suspend fun finish() {
        queue.close()
        consumerJob?.join()
        engine.finalizeSession { result -> handleResult(result) }
        engine.release()
        sttExecutor.shutdown()
    }

    /**
     * Real time of the audio the engine is working on right now = wall-clock since start minus the
     * audio still waiting in the queue. Called on the recognition thread, right when a result appears.
     */
    private fun currentAudioTimeMs(): Long {
        val elapsed = SystemClock.elapsedRealtime() - sessionStartMs
        val backlog = maxOf(0, queueOccupancy.get()) * 100L
        return maxOf(0L, elapsed - backlog)
    }

    private fun handleResult(result: RecognitionResult) {
        val start = lastSegmentEndMs
        val end = maxOf(start, currentAudioTimeMs())
        if (result.isFinal) lastSegmentEndMs = end
        scope.launch {
            if (result.isFinal) {
                repository.appendSegment(
                    lectureId = lectureId,
                    startTimeMs = start,
                    endTimeMs = end,
                    text = result.text
                )
                segmentCounter++
                _segmentCount.tryEmit(segmentCounter)
                _liveCaptionText.tryEmit(result.text)
            } else {
                _liveCaptionText.tryEmit(result.text)
            }
        }
    }
}
