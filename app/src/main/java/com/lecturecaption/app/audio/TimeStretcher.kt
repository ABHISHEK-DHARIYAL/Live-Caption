package com.lecturecaption.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Streaming WSOLA time-stretcher (16 kHz mono).
 *
 * When a video plays at 1.5x / 2x, speech reaches the recognizer too fast and words get missed.
 * This slows the audio back down by [factor] (e.g. 2.0 = twice as long) WITHOUT changing pitch,
 * so the recognizer hears roughly normal-speed speech. Input is consumed chunk by chunk and
 * whatever output is ready is returned, so latency stays tiny.
 */
class TimeStretcher(private val factor: Float) {

    private val frame = 480                       // 30 ms window
    private val hop = frame / 2                   // synthesis hop (50% overlap)
    private val analysisHop = hop / factor.toDouble()
    private val search = (hop * 0.75).toInt().coerceAtLeast(120) // wider search = better alignment, esp. at 2x
    private val window = FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * PI * it / frame)).toFloat() }

    private var buf = FloatArray(32_768)
    private var bufLen = 0
    private var bufStart = 0L                     // absolute index of buf[0]

    private var analysisPos = 0.0                 // absolute position of the next analysis frame
    private var prevSel = -1L                     // absolute start of previously chosen frame
    private val tail = FloatArray(hop)            // second half of previous frame, waiting to overlap

    fun process(pcm: ShortArray, length: Int): ShortArray {
        if (length <= 0) return ShortArray(0)

        if (bufLen + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + length))
        for (i in 0 until length) buf[bufLen + i] = pcm[i] / 32768f
        bufLen += length

        val out = ShortArray(((length + frame) * factor).toInt() + 3 * hop)
        var outCount = 0

        while (true) {
            val totalIn = bufStart + bufLen
            val target = analysisPos.roundToLong()
            val need = maxOf(target + search + frame, prevSel + hop + frame)
            if (need > totalIn) break

            val sel: Long
            if (prevSel < 0) {
                sel = maxOf(bufStart, target)
            } else {
                val tmpl = (prevSel + hop - bufStart).toInt()
                val lo = maxOf(bufStart, target - search)
                val hi = target + search
                var best = Float.NEGATIVE_INFINITY
                var bestPos = target.coerceAtLeast(bufStart)
                var c = lo
                while (c <= hi) {
                    val ci = (c - bufStart).toInt()
                    var corr = 0f
                    var energy = 0f
                    for (k in 0 until hop) {
                        val v = buf[ci + k]
                        corr += v * buf[tmpl + k]
                        energy += v * v
                    }
                    val norm = corr / sqrt(energy + 1e-6f)
                    if (norm > best) {
                        best = norm
                        bestPos = c
                    }
                    c++
                }
                sel = bestPos
            }

            val si = (sel - bufStart).toInt()
            for (i in 0 until hop) {
                val v = tail[i] + buf[si + i] * window[i]
                out[outCount++] = (v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
            for (i in 0 until hop) tail[i] = buf[si + hop + i] * window[hop + i]

            prevSel = sel
            analysisPos += analysisHop
        }

        // Drop input we will never look at again.
        val nextTarget = analysisPos.roundToLong() - search
        val keepFrom = maxOf(bufStart, if (prevSel >= 0) minOf(nextTarget, prevSel + hop) else nextTarget)
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 8_192) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }

        return out.copyOf(outCount)
    }
}
