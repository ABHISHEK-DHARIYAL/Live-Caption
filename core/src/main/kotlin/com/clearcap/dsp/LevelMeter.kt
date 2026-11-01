package com.clearcap.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Measures audio level and clipping per chunk. Never modifies audio. Numbers only: no content is kept.
 */
class LevelMeter(private val sampleRate: Int = 16_000, private val silenceDb: Float = -55f) {
    data class Snapshot(
        val peak: Float,            // 0..1 of the latest chunk
        val rmsDb: Float,           // dBFS of the latest chunk (very low when silent)
        val clippedRatio: Float,    // share of clipped samples in the last ~1 s
        val silentMs: Long,         // continuous silence up to now
        val totalMs: Long,
        val clippingWarning: Boolean,
    )

    private var total = 0L
    private var silentRun = 0L
    private var lastPeak = 0f
    private var lastDb = -120f
    private val window = ArrayDeque<Pair<Int, Int>>() // (samples, clipped) for ~1 s
    private var winSamples = 0
    private var winClipped = 0

    fun push(samples: ShortArray, length: Int = samples.size) {
        if (length <= 0) return
        var peak = 0
        var clipped = 0
        var sumSq = 0.0
        for (i in 0 until length) {
            val v = abs(samples[i].toInt())
            if (v > peak) peak = v
            if (v >= CLIP_LEVEL) clipped++
            sumSq += v.toDouble() * v
        }
        val rms = sqrt(sumSq / length) / 32768.0
        lastDb = if (rms <= 1e-6) -120f else (20 * log10(rms)).toFloat()
        lastPeak = peak / 32768f
        val ms = length * 1000L / sampleRate
        total += ms
        silentRun = if (lastDb < silenceDb) silentRun + ms else 0L
        window.addLast(length to clipped)
        winSamples += length
        winClipped += clipped
        while (winSamples > sampleRate && window.size > 1) {
            val (n, c) = window.removeFirst()
            winSamples -= n
            winClipped -= c
        }
    }

    fun snapshot(): Snapshot {
        val ratio = if (winSamples == 0) 0f else winClipped.toFloat() / winSamples
        return Snapshot(lastPeak, lastDb, ratio, silentRun, total, ratio > CLIP_WARN_RATIO)
    }

    companion object {
        const val CLIP_LEVEL = 32_700
        const val CLIP_WARN_RATIO = 0.01f
    }
}
