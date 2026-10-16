package com.lecturecaption.app.stt

/** Speech from one or more voice-detector segments, joined into one chunk for a single online request. */
class SpeechBatch(val startMs: Long, val endMs: Long, val samples: FloatArray)

/**
 * Online transcription is billed/rate-limited per request, and a lecture has a speech pause every few
 * seconds. Sending each tiny piece separately would hit the free-tier limit within a minute, so this
 * groups consecutive segments (until a long pause or ~45 s of audio) into one batch.
 */
internal class SegmentBatcher(
    private val sampleRate: Int = 16_000,
    private val maxGapMs: Long = 2_500,
    private val maxBatchSamples: Int = 16_000 * 45,
    /** Silence inserted between joined segments so words from different pieces don't run together. */
    private val joinSilenceMs: Int = 300
) {
    private val parts = ArrayList<FloatArray>()
    private var startMs = 0L
    private var endMs = 0L
    private var total = 0

    /** Adds a segment. Returns the previous batch if this segment doesn't belong in it. */
    fun add(segStartMs: Long, samples: FloatArray): SpeechBatch? {
        var finished: SpeechBatch? = null
        if (parts.isNotEmpty() && (segStartMs - endMs > maxGapMs || total + samples.size > maxBatchSamples)) {
            finished = flush()
        }
        if (parts.isEmpty()) startMs = segStartMs
        parts.add(samples)
        total += samples.size
        endMs = segStartMs + samples.size * 1000L / sampleRate
        return finished
    }

    /** Returns whatever is pending as a batch (or null if nothing is). */
    fun flush(): SpeechBatch? {
        if (parts.isEmpty()) return null
        val pad = sampleRate * joinSilenceMs / 1000
        val out = FloatArray(total + pad * (parts.size - 1))
        var pos = 0
        parts.forEachIndexed { i, p ->
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
            if (i < parts.size - 1) pos += pad // zeros are already there
        }
        val batch = SpeechBatch(startMs, endMs, out)
        parts.clear()
        total = 0
        return batch
    }
}
