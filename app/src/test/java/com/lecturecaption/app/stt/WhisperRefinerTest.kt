package com.lecturecaption.app.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperRefinerTest {
    @Test fun dropsInventedPhraseOnShortNoise() =
        assertEquals("", WhisperRefiner.cleanUp("Thank you.", 1_500))

    @Test fun keepsRealThankYouInLongSpeech() =
        assertEquals("Thank you.", WhisperRefiner.cleanUp("Thank you.", 5_000))

    @Test fun collapsesLoopedWord() =
        assertEquals("hello hello hello hello", WhisperRefiner.cleanUp("hello hello hello hello hello hello hello", 5_000))

    @Test fun dropsMusicTagsAndSubtitleJunk() {
        assertEquals("", WhisperRefiner.cleanUp("[MUSIC]", 8_000))
        assertEquals("", WhisperRefiner.cleanUp("Subtitles by the Amara.org community", 8_000))
        assertEquals("so we start", WhisperRefiner.cleanUp("[BLANK_AUDIO] so we start", 8_000))
    }

    @Test fun keepsMathInParentheses() =
        assertEquals("compute (a + b) first", WhisperRefiner.cleanUp("compute (a + b) first", 8_000))

    @Test fun quietNoiseIsBelowSpeechLevel() {
        assert(WhisperRefiner.rms(FloatArray(1600) { 0.002f }) < 0.01f)
        assert(WhisperRefiner.rms(FloatArray(1600) { if (it % 2 == 0) 0.2f else -0.2f }) > 0.01f)
    }

    @Test fun mergesNearbyPiecesButNotAfterLongPause() {
        val merged = WhisperRefiner.mergeIntoParagraphs(
            listOf(
                RefinedSegment(0, 1_000, "First part."),
                RefinedSegment(1_500, 2_500, "Second part."),
                RefinedSegment(9_000, 10_000, "After a long pause.")
            )
        )
        assertEquals(2, merged.size)
        assertEquals("First part. Second part.", merged[0].text)
        assertEquals(0L, merged[0].startMs)
        assertEquals(2_500L, merged[0].endMs)
        assertEquals("After a long pause.", merged[1].text)
    }
}
