package com.clearcap.text

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyCorrectorTest {
    private val corrector = VocabularyCorrector(listOf("Kubernetes", "Dhariyal", "PyTorch", "Nowcasting"))

    @Test fun fixesMisheardMultiWordTerm() =
        assertEquals("we deploy on Kubernetes today", corrector.correct("we deploy on cuber nettis today"))

    @Test fun fixesNameSpelling() =
        assertEquals("professor Dhariyal said", corrector.correct("professor dariyal said"))

    @Test fun fixesSplitWordWithoutSwallowingNeighbours() =
        assertEquals("Nowcasting of storms", corrector.correct("now casting of storms"))

    @Test fun fixesCasing() =
        assertEquals("a test of Nowcasting", corrector.correct("a test of nowcasting"))

    @Test fun leavesOrdinaryTextAlone() =
        assertEquals("the cat sat on the mat", corrector.correct("the cat sat on the mat"))

    @Test fun keepsPunctuation() =
        assertEquals("Use PyTorch, then stop.", corrector.correct("Use pie torch, then stop."))

    @Test fun doesNotSwallowRealWordsThatOnlyLookSimilar() =
        assertEquals("the area all", corrector.correct("the area all"))

    @Test fun emptyVocabularyIsNoOp() =
        assertEquals("hello world", VocabularyCorrector(emptyList()).correct("hello world"))
}
