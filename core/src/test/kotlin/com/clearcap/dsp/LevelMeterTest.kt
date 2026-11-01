package com.clearcap.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMeterTest {
    @Test fun silenceAccumulates() {
        val m = LevelMeter()
        repeat(10) { m.push(ShortArray(1600)) }
        val s = m.snapshot()
        assertEquals(1000L, s.silentMs)
        assertEquals(1000L, s.totalMs)
        assertTrue(s.rmsDb < -100f)
    }

    @Test fun speechResetsSilence() {
        val m = LevelMeter()
        m.push(ShortArray(1600))
        m.push(ShortArray(1600) { 8000 })
        assertEquals(0L, m.snapshot().silentMs)
    }

    @Test fun clippingIsDetectedAndWarned() {
        val m = LevelMeter()
        m.push(ShortArray(1600) { 32767 })
        val s = m.snapshot()
        assertTrue(s.clippingWarning)
        assertEquals(1f, s.clippedRatio, 0.001f)
    }

    @Test fun normalSpeechLevelDoesNotWarn() {
        val m = LevelMeter()
        m.push(ShortArray(1600) { if (it % 2 == 0) 6000 else -6000 })
        assertFalse(m.snapshot().clippingWarning)
    }

    @Test fun rmsMatchesKnownAmplitude() {
        val m = LevelMeter()
        m.push(ShortArray(1600) { 16384 }) // 0.5 full scale constant -> -6.02 dBFS
        assertEquals(-6.02f, m.snapshot().rmsDb, 0.1f)
    }
}
