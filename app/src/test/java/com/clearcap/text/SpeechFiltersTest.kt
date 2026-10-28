package com.clearcap.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechFiltersTest {
    private fun tone(ms: Int, amp: Float) = FloatArray(16 * ms) { amp * sin(2 * PI * 200.0 * it / 16000).toFloat() }

    @Test fun silenceNoiseAndClicksAreNotSpeech() {
        assertFalse(SpeechFilters.isSpeechLike(FloatArray(16_000)))
        val rnd = java.util.Random(3)
        assertFalse(SpeechFilters.isSpeechLike(FloatArray(16_000) { (rnd.nextGaussian() * 0.002).toFloat() }))
        val click = FloatArray(32_000); for (i in 0 until 400) click[i] = 0.8f
        assertFalse(SpeechFilters.isSpeechLike(click))
    }

    @Test fun clearSignalIsSpeech() = assertTrue(SpeechFilters.isSpeechLike(tone(1500, 0.3f)))

    @Test fun signOffsAreInventedOnShortClipsOnly() {
        assertEquals("", SpeechFilters.cleanUp("Thanks for listening.", 1_500))
        assertEquals("", SpeechFilters.cleanUp("धन्यवाद", 1_000))
        assertEquals("Thanks for listening.", SpeechFilters.cleanUp("Thanks for listening.", 9_000))
    }

    @Test fun creditsAndSubscribeLinesAreRemoved() {
        assertEquals("", SpeechFilters.cleanUp("Subtitles by the Amara.org community", 20_000))
        assertEquals("So we start.", SpeechFilters.cleanUp("So we start. Please subscribe to my channel!", 20_000))
        assertEquals("", SpeechFilters.cleanUp("Transcribed by ESO, translated by —", 20_000))
    }

    @Test fun realSentencesWithSimilarWordsSurvive() {
        val t = "You can subscribe to a topic in a pub sub system."
        assertEquals(t, SpeechFilters.cleanUp(t, 6_000))
        assertEquals("compute (a + b) first", SpeechFilters.cleanUp("compute (a + b) first", 8_000))
    }

    @Test fun tagsAndPunctuationOnlyBecomeEmpty() {
        assertEquals("", SpeechFilters.cleanUp("[MUSIC]", 8_000))
        assertEquals("", SpeechFilters.cleanUp(" ... ", 8_000))
        assertEquals("so we start", SpeechFilters.cleanUp("[BLANK_AUDIO] so we start", 8_000))
    }

    @Test fun loopsAreCapped() {
        assertEquals("hello hello hello hello", SpeechFilters.cleanUp("hello hello hello hello hello hello hello", 5_000))
        val loop = "thank you very much ".repeat(6).trim()
        assertEquals("thank you very much thank you very much", SpeechFilters.cleanUp(loop, 30_000))
    }

    @Test fun paragraphsMergeOnlyAcrossShortPauses() {
        val m = SpeechFilters.mergeIntoParagraphs(listOf(
            RefinedSegment(0, 1_000, "A."), RefinedSegment(1_500, 2_500, "B."), RefinedSegment(9_000, 10_000, "C.")))
        assertEquals(listOf("A. B.", "C."), m.map { it.text })
    }
}
