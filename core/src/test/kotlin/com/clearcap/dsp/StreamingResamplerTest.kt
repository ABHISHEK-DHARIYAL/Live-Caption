package com.clearcap.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class StreamingResamplerTest {
    private fun tone(rate: Int, hz: Double, seconds: Double, amp: Double = 0.5) =
        ShortArray((rate * seconds).toInt()) { (amp * 32767 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun rms(x: ShortArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += (x[i] / 32768.0) * (x[i] / 32768.0)
        return sqrt(s / (to - from))
    }

    private fun run(rin: Int, rout: Int, x: ShortArray, chunk: Int): ShortArray {
        val r = StreamingResampler(rin, rout)
        val out = ArrayList<Short>()
        var i = 0
        while (i < x.size) {
            val n = minOf(chunk, x.size - i)
            r.process(x.copyOfRange(i, i + n)).forEach { out.add(it) }
            i += n
        }
        r.flush().forEach { out.add(it) }
        return out.toShortArray()
    }

    @Test fun lengthIsRightFor48kTo16k() {
        val out = run(48_000, 16_000, tone(48_000, 1000.0, 2.0), 4800)
        assertTrue("got ${out.size}", abs(out.size - 32_000) < 120)
    }

    @Test fun lengthIsRightFor44100To16k() {
        val out = run(44_100, 16_000, tone(44_100, 1000.0, 2.0), 4410)
        assertTrue("got ${out.size}", abs(out.size - 32_000) < 120)
    }

    @Test fun passbandToneKeepsItsLevel() {
        val out = run(48_000, 16_000, tone(48_000, 1000.0, 2.0), 4800)
        val expected = 0.5 / sqrt(2.0)
        val got = rms(out, 2000, out.size - 2000)
        assertTrue("rms $got vs $expected", abs(got - expected) / expected < 0.03)
    }

    @Test fun toneAboveNewNyquistIsRejectedNotAliased() {
        // 12 kHz would fold to 4 kHz if resampled without a filter.
        val out = run(48_000, 16_000, tone(48_000, 12_000.0, 2.0), 4800)
        val inRms = 0.5 / sqrt(2.0)
        val db = 20 * log10(rms(out, 2000, out.size - 2000) / inRms)
        assertTrue("attenuation only ${-db} dB", db < -40)
    }

    @Test fun chunkSizeDoesNotChangeTheResult() {
        val x = tone(48_000, 700.0, 1.0)
        val a = run(48_000, 16_000, x, x.size)
        val b = run(48_000, 16_000, x, 997)
        assertEquals(a.size, b.size)
        var maxDiff = 0
        for (i in a.indices) maxDiff = maxOf(maxDiff, abs(a[i] - b[i]))
        assertTrue("max diff $maxDiff", maxDiff <= 2)
    }

    @Test fun sameRateIsPassthrough() {
        val x = tone(16_000, 440.0, 0.5)
        val out = StreamingResampler(16_000, 16_000).process(x)
        assertTrue(x.contentEquals(out))
    }

    @Test fun upsamplingKeepsTheTone() {
        val out = run(8_000, 16_000, tone(8_000, 500.0, 1.0), 800)
        val expected = 0.5 / sqrt(2.0)
        val got = rms(out, 1000, out.size - 1000)
        assertTrue("rms $got", abs(got - expected) / expected < 0.04)
        assertTrue(abs(out.size - 16_000) < 100)
    }
}
