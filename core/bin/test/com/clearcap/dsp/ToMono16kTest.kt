package com.clearcap.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToMono16kTest {
    @Test fun oppositeChannelsCancelWhenAveraged() {
        val c = ToMono16k(16_000, 2)
        val stereo = ShortArray(3200) { if (it % 2 == 0) 1000 else -1000 }
        val out = c.convert(stereo)
        assertEquals(1600, out.size)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test fun identicalChannelsKeepTheirValue() {
        val c = ToMono16k(16_000, 2)
        val stereo = ShortArray(3200) { 2000 }
        assertTrue(c.convert(stereo).all { it.toInt() == 2000 })
    }

    @Test fun partialFramesAreCarriedToTheNextCall() {
        val c = ToMono16k(16_000, 2)
        val a = c.convert(shortArrayOf(100, 100, 100)) // 1 frame + 1 stray sample
        val b = c.convert(shortArrayOf(100, 100, 100)) // stray + 3 -> 2 frames
        assertEquals(1, a.size)
        assertEquals(2, b.size)
        assertTrue((a + b).all { it.toInt() == 100 })
    }

    @Test fun resamplesFrom48kStereo() {
        val c = ToMono16k(48_000, 2)
        var total = 0
        repeat(10) { total += c.convert(ShortArray(9600)).size } // 10 x 100 ms
        total += c.flush().size
        assertTrue("got $total", kotlin.math.abs(total - 16_000) < 100)
    }
}
