package com.lecturecaption.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AutoGainTest {
    private fun sine(amp: Double, n: Int = 1600) =
        ShortArray(n) { (amp * 32767 * sin(2 * PI * 220.0 * it / 16000)).toInt().toShort() }

    @Test fun silenceStaysSilent() {
        val g = AutoGain()
        repeat(50) { val z = ShortArray(1600); g.apply(z); assertTrue(z.all { it.toInt() == 0 }) }
        assertEquals(1f, g.gain, 0.001f)
    }

    @Test fun backgroundNoiseIsNeverBoosted() {
        val g = AutoGain()
        val rnd = java.util.Random(1)
        repeat(80) {
            val noise = ShortArray(1600) { (rnd.nextGaussian() * 130).toInt().toShort() }
            val copy = noise.copyOf()
            g.apply(noise)
            assertTrue(noise.contentEquals(copy))
        }
        assertEquals(1f, g.gain, 0.001f)
    }

    @Test fun quietSpeechIsBoostedButCappedAtFour() {
        val g = AutoGain()
        val quiet = sine(0.03 * 1.414)
        repeat(40) { g.apply(sine(0.03 * 1.414)) }
        assertTrue("gain ${g.gain}", g.gain > 3f && g.gain <= 4.0001f)
        val x = quiet.copyOf(); g.apply(x)
        assertTrue(AutoGain.rms(x) > AutoGain.rms(quiet) * 3f)
    }

    @Test fun loudAudioIsLeftAlone() {
        val g = AutoGain()
        val loud = sine(0.5)
        repeat(20) { val x = loud.copyOf(); g.apply(x); assertTrue(x.contentEquals(loud)) }
    }

    @Test fun neverAttenuatesAndNeverOverflows() {
        val g = AutoGain()
        repeat(40) { g.apply(sine(0.05)) }
        val x = ShortArray(1600) { 12000 }
        g.apply(x)
        assertTrue(x.all { it in -32768..32767 && it >= 12000 })
    }
}
