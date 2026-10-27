package com.lecturecaption.app.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAndVocabExtraTest {
    @Test fun onlineBatchesNeverExceed45SecondsIncludingJoinSilence() {
        val b = SegmentBatcher()
        var t = 0L
        val batches = ArrayList<SpeechBatch>()
        repeat(60) {
            b.add(t, FloatArray(16 * 1500) { 0.1f })?.let { batches.add(it) }
            t += 1800
        }
        b.flush()?.let { batches.add(it) }
        assertTrue(batches.size >= 2)
        assertTrue(batches.all { it.samples.size <= 16_000 * 45 })
    }

    private val v = VocabularyCorrector(listOf("Kubernetes", "Navier-Stokes", "Eigenvalue", "Thermal Load"))

    @Test fun oneConsonantSoundSlipInAMultiWordTermIsFixed() =
        assertEquals("the Navier-Stokes equation", v.correct("the navia stokes equation"))

    @Test fun lookalikePhraseIsNotSwallowed() =
        assertEquals("never stoke the fire", v.correct("never stoke the fire"))

    @Test fun differentSoundingPhraseIsNotSwallowed() =
        assertEquals("a thermal lord appeared", v.correct("a thermal lord appeared"))

    @Test fun lineBreaksSurviveCorrection() =
        assertEquals("first line\nuse Kubernetes today\n\nsecond", v.correct("first line\nuse kubernetis today\n\nsecond"))

    @Test fun termsNeverJoinAcrossPunctuationOrLines() {
        assertEquals("navia, stokes", v.correct("navia, stokes"))
        assertEquals("eigen\nvalue", v.correct("eigen\nvalue"))
    }

    @Test fun splitWordIsJoined() = assertEquals("find the Eigenvalue now", v.correct("find the eigan value now"))
}
