package com.clearcap.asr

import com.clearcap.models.ModelInfo
import com.clearcap.pipeline.LiveMode
import com.clearcap.text.SpeechFilters
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream

/** What a live recognizer reports. Text in [Final.raw] is the exact engine output; filtering happens later and the raw text is kept. */
sealed class LiveEvent {
    data class Partial(val text: String) : LiveEvent()
    data class Final(val raw: String, val startMs: Long, val endMs: Long, val language: String?) : LiveEvent()
}

/** One live engine. Replaceable: the UI and session code only see this interface. */
interface LiveRecognizer : AutoCloseable {
    val mode: LiveMode
    val model: ModelInfo
    /** True while speech is being heard but its text is not ready yet (near-live only). Used for a "listening" indicator, never as text. */
    val busy: Boolean
    val decodeNs: Long
    val decodedAudioMs: Long
    fun accept(chunk: ShortArray, length: Int, startMs: Long): List<LiveEvent>
    fun finish(endMs: Long): List<LiveEvent>
}

/** Streaming Zipformer: text appears word by word. English only. */
class StreamingRecognizer(override val model: ModelInfo, paths: Map<String, String>, threads: Int) : LiveRecognizer {
    private val rec: OnlineRecognizer = SherpaFactory.streaming(model, paths, threads)
    private var stream: OnlineStream = rec.createStream()
    private var last = ""
    private var segStart = -1L
    @Volatile override var decodeNs = 0L; private set
    @Volatile override var decodedAudioMs = 0L; private set

    override val mode = LiveMode.STREAMING
    override val busy = false

    override fun accept(chunk: ShortArray, length: Int, startMs: Long): List<LiveEvent> {
        val t0 = System.nanoTime()
        stream.acceptWaveform(FloatArray(length) { chunk[it] / 32768f }, SAMPLE_RATE)
        while (rec.isReady(stream)) rec.decode(stream)
        val text = rec.getResult(stream).text.trim()
        val endMs = startMs + length * 1000L / SAMPLE_RATE
        decodeNs += System.nanoTime() - t0
        decodedAudioMs += length * 1000L / SAMPLE_RATE
        if (text.isNotEmpty() && segStart < 0) segStart = startMs
        if (rec.isEndpoint(stream)) {
            rec.reset(stream)
            last = ""
            val s = segStart.coerceAtLeast(0)
            segStart = -1
            return if (text.isNotEmpty()) listOf(LiveEvent.Final(text, s, maxOf(endMs, s + 1), "en")) else emptyList()
        }
        if (text == last) return emptyList()
        last = text
        return listOf(LiveEvent.Partial(text))
    }

    override fun finish(endMs: Long): List<LiveEvent> {
        stream.acceptWaveform(FloatArray(SAMPLE_RATE * 4 / 10), SAMPLE_RATE) // a little silence so the last word is decoded
        stream.inputFinished()
        while (rec.isReady(stream)) rec.decode(stream)
        val t = rec.getResult(stream).text.trim()
        rec.reset(stream)
        val s = if (segStart >= 0) segStart else maxOf(0, endMs - 500)
        segStart = -1; last = ""
        return if (t.isNotEmpty()) listOf(LiveEvent.Final(t, s, maxOf(endMs, s + 1), "en")) else emptyList()
    }

    override fun close() {
        runCatching { stream.release() }
        runCatching { rec.release() }
    }
}

/**
 * Near-live captions for Hindi, Hinglish and auto-detect: the speech detector cuts the audio at pauses
 * (at most [MAX_SEGMENT_SEC] s) and Whisper decodes each piece, so text appears after each phrase, seconds late.
 * No partial text is shown while waiting: only [busy] tells the UI that speech is being heard.
 */
class NearLiveRecognizer(
    private val decoder: OfflineDecoder,
    private val segmenter: SpeechSegmenter,
    private val onSpeech: ((Speech) -> Unit)? = null,
) : LiveRecognizer {
    override val model: ModelInfo get() = decoder.model
    override val mode = LiveMode.NEAR_LIVE
    override val busy: Boolean get() = segmenter.speechActive
    override val decodeNs: Long get() = decoder.decodeNs
    override val decodedAudioMs: Long get() = decoder.decodedAudioMs

    override fun accept(chunk: ShortArray, length: Int, startMs: Long): List<LiveEvent> = decode(segmenter.feed(chunk, length, startMs))

    override fun finish(endMs: Long): List<LiveEvent> = decode(segmenter.flush())

    private fun decode(segments: List<Speech>): List<LiveEvent> {
        if (segments.isEmpty()) return emptyList()
        val out = ArrayList<LiveEvent>(segments.size)
        for (s in segments) {
            onSpeech?.invoke(s)
            val f = s.floats()
            if (!SpeechFilters.isSpeechLike(f)) continue // noise or hum: not sent to the model, which would invent words
            val d = decoder.decode(f)
            if (d.text.isNotEmpty()) out.add(LiveEvent.Final(d.text, s.startMs, s.endMs, d.language))
        }
        return out
    }

    override fun close() {
        segmenter.close()
        decoder.close()
    }

    companion object { const val MAX_SEGMENT_SEC = 12f }
}
