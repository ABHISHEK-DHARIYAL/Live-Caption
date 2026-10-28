package com.clearcap.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextTest {
    private val none = VocabularyCorrector(emptyList())

    @Test fun shoutedEngineOutputBecomesSentenceCase() {
        assertEquals("God as a direct consequence of the sin which man thus punished.",
            TranscriptText.liveSentence("GOD AS A DIRECT CONSEQUENCE OF THE SIN WHICH MAN THUS PUNISHED", none))
    }

    @Test fun pronounIAndContractionsAreFixed() {
        assertEquals("I think I'm done. I'll go.", TranscriptText.sentenceCase("i think i'm done. i'll go."))
    }

    @Test fun mixedCaseTextIsLeftAlone() {
        assertEquals("Hello, this is iPhone and NASA.", TranscriptText.casing("Hello, this is iPhone and NASA."))
    }

    @Test fun dictionarySpellingWinsAfterCasing() {
        val v = VocabularyCorrector(listOf("Kubernetes"))
        assertEquals("We deploy on Kubernetes today.", TranscriptText.liveSentence("WE DEPLOY ON KUBERNETIS TODAY", v))
    }

    @Test fun terminalPunctuationIsNotDoubled() {
        assertEquals("Done.", TranscriptText.liveSentence("DONE.", none))
        assertEquals("", TranscriptText.liveSentence("   ", none))
    }

    @Test fun joinOrdersDedupesAndBreaksParagraphs() {
        val t = TranscriptText.join(listOf(
            Timed(9_000, 10_000, "Third."), Timed(0, 1_000, "First."), Timed(1_200, 2_000, "first."), Timed(2_500, 3_000, "Second."),
        ))
        assertEquals("First. Second.\n\nThird.", t)
    }

    @Test fun wordCountIgnoresExtraSpaces() = assertEquals(3, TranscriptText.wordCount("  one  two\nthree "))
}
