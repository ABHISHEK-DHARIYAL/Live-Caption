package com.clearcap.asr

/** One stretch of speech found by the detector. Times are ms on the recording timeline. */
class Speech(val startMs: Long, val endMs: Long, val samples: ShortArray) {
    fun floats(): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }
}

/** Wraps the Silero detector: feed 16 kHz audio, get finished speech segments back. */
class SpeechSegmenter(vadPath: String, maxSpeechSec: Float) : AutoCloseable {
    private val vad = SherpaFactory.vad(vadPath, maxSpeechSec)
    private var offsetMs = 0L
    private var fedSamples = 0L
    private var nextExpectedMs = 0L

    /** True while the detector currently hears speech that has not been returned as a segment yet. */
    val speechActive: Boolean get() = runCatching { vad.isSpeechDetected() }.getOrDefault(false)

    /**
     * [startMs] is the position of this block on the recording timeline. If blocks were dropped upstream
     * (a gap), later segments are shifted by the gap so their timestamps still match the real recording.
     */
    fun feed(samples: ShortArray, length: Int, startMs: Long): List<Speech> {
        if (startMs > nextExpectedMs + 50) offsetMs += startMs - nextExpectedMs
        nextExpectedMs = startMs + length * 1000L / SAMPLE_RATE
        fedSamples += length
        vad.acceptWaveform(FloatArray(length) { samples[it] / 32768f })
        return drain()
    }

    fun flush(): List<Speech> {
        vad.flush()
        return drain()
    }

    private fun drain(): List<Speech> {
        val out = ArrayList<Speech>(2)
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            if (seg.samples.size < SAMPLE_RATE / 8) continue // under about 125 ms: not a word
            val start = offsetMs + seg.start * 1000L / SAMPLE_RATE
            val ms = seg.samples.size * 1000L / SAMPLE_RATE
            out.add(Speech(start, start + ms, ShortArray(seg.samples.size) { (seg.samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }))
        }
        return out
    }

    override fun close() { runCatching { vad.release() } }
}
