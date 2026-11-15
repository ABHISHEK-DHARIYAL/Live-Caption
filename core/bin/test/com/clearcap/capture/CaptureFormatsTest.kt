package com.clearcap.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFormatsTest {
    @Test fun microphoneTriesDirect16kFirst() {
        val f = CaptureFormats.candidates(CaptureKind.MICROPHONE).first()
        assertEquals(CaptureFormat(16_000, 1), f)
        assertFalse(f.needsConversion)
    }
    @Test fun systemAudioStartsAtMixerRateStereoAndNeedsConversion() {
        val f = CaptureFormats.candidates(CaptureKind.SYSTEM).first()
        assertEquals(CaptureFormat(48_000, 2), f)
        assertTrue(f.needsConversion)
    }
    @Test fun chunkIs100msOfInterleavedSamples() {
        assertEquals(9_600, CaptureFormat(48_000, 2).chunkSamples)
        assertEquals(1_600, CaptureFormat(16_000, 1).chunkSamples)
    }
    @Test fun everyFormatListEndsWithSomethingElseThanEmpty() {
        for (k in CaptureKind.values()) assertTrue(CaptureFormats.candidates(k).size >= 3)
    }
}
