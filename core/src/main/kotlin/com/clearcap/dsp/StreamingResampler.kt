package com.clearcap.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Real sample-rate conversion (windowed-sinc, Blackman window), not a metadata change.
 * Streaming: feed consecutive chunks of one signal; filter state is kept between calls, so chunk
 * boundaries do not produce clicks. Works for any rate pair (48000->16000, 44100->16000, 22050->16000, ...).
 * When downsampling, the cutoff is placed just under the new Nyquist frequency so higher frequencies
 * are filtered out instead of folding back as aliasing noise.
 *
 * The first output assumes silence before the stream started; [flush] adds trailing silence to push the tail out.
 */
class StreamingResampler(val inRate: Int, val outRate: Int, private val halfTaps: Int = 16) {
    init { require(inRate > 0 && outRate > 0) { "rates must be positive" } }

    private val passthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate               // input samples per output sample
    private val fc = min(1.0, outRate.toDouble() / inRate) * 0.96 // cutoff relative to input Nyquist
    private val half = halfTaps / fc                              // kernel half-width in input samples
    private val res = 256                                         // kernel table resolution per input sample
    private val table: FloatArray

    private var buf = FloatArray(0)   // input samples currently held
    private var base = 0L             // absolute index of buf[0]
    private var next = 0.0            // absolute input position of the next output sample

    init {
        val n = (2 * half * res).toInt() + 2
        table = FloatArray(n) { i ->
            val u = i.toDouble() / res - half
            if (kotlin.math.abs(u) >= half) 0f else {
                val x = fc * u
                val s = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val w = 0.42 + 0.5 * cos(PI * u / half) + 0.08 * cos(2 * PI * u / half) // Blackman, centred
                (fc * s * w).toFloat()
            }
        }
        // Start with enough zeros before sample 0 that the first output has full kernel support.
        val lead = kotlin.math.ceil(half).toInt() + 1
        buf = FloatArray(lead)
        base = -lead.toLong()
    }

    /** Converts [length] samples of [src]; returns the output samples that are now complete. */
    fun process(src: ShortArray, length: Int = src.size): ShortArray {
        if (passthrough) return src.copyOf(length)
        append(FloatArray(length) { src[it] / 32768f })
        return emit()
    }

    fun processFloat(src: FloatArray, length: Int = src.size): FloatArray {
        if (passthrough) return src.copyOf(length)
        append(src.copyOf(length))
        return emitFloat()
    }

    /** Pads with silence so the last real samples are output. Call once at the end of a stream. */
    fun flush(): ShortArray {
        if (passthrough) return ShortArray(0)
        append(FloatArray(kotlin.math.ceil(half).toInt() + 2))
        return emit()
    }

    private fun append(x: FloatArray) {
        val nb = FloatArray(buf.size + x.size)
        System.arraycopy(buf, 0, nb, 0, buf.size)
        System.arraycopy(x, 0, nb, buf.size, x.size)
        buf = nb
    }

    private fun emit(): ShortArray {
        val f = emitFloat()
        return ShortArray(f.size) { (f[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun emitFloat(): FloatArray {
        val end = base + buf.size // absolute index one past the last held sample
        val out = ArrayList<Float>(((buf.size / step) + 2).toInt())
        while (next + half < end - 1) {
            val lo = max(base, kotlin.math.ceil(next - half).toLong())
            val hi = min(end - 1, kotlin.math.floor(next + half).toLong())
            var acc = 0.0
            var n = lo
            while (n <= hi) {
                val u = next - n
                val t = (u + half) * res
                val i = t.toInt()
                val frac = t - i
                val k = table[i] * (1 - frac) + table[i + 1] * frac
                acc += buf[(n - base).toInt()] * k
                n++
            }
            out.add(acc.toFloat())
            next += step
        }
        // Drop input that no later output can reach.
        val keepFrom = max(base, kotlin.math.floor(next - half).toLong() - 1)
        val drop = (keepFrom - base).toInt()
        if (drop > 0) {
            buf = buf.copyOfRange(drop, buf.size)
            base += drop
        }
        return out.toFloatArray()
    }
}
