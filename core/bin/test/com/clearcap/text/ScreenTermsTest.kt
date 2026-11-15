package com.clearcap.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTermsTest {
    @Test fun picksCamelCaseAndMixedDigits() {
        val t = ScreenTerms.extract("We train with TensorFlow on an EC2 box running Python3 and macOS.")
        assertTrue(t.containsAll(listOf("TensorFlow", "EC2", "Python3", "macOS")))
    }

    @Test fun ignoresOrdinaryWords() {
        val t = ScreenTerms.extract("The lecture covers networks, memory and scheduling in detail today.")
        assertTrue(t.isEmpty())
    }

    @Test fun midSentenceCapitalisedWordIsATerm() =
        assertTrue("Kubernetes" in ScreenTerms.extract("Today we deploy on Kubernetes clusters."))

    @Test fun sentenceStartCapitalIsNotEnough() {
        assertFalse("Introduction" in ScreenTerms.extract("Introduction\nSlide one\nOverview"))
        assertFalse("Networking" in ScreenTerms.extract("Today. Networking is fun."))
    }

    @Test fun capitalisedWordAlsoSeenInLowercaseIsOrdinary() =
        assertFalse("Scheduling" in ScreenTerms.extract("we like scheduling. Our Scheduling notes"))

    @Test fun acronymNeedsTwoSightingsAndNotCommon() {
        assertFalse("GPU" in ScreenTerms.extract("one GPU"))
        assertTrue("GPU" in ScreenTerms.extract("the GPU and a second GPU"))
        assertFalse("SAVE" in ScreenTerms.extract("SAVE SAVE SAVE"))
    }

    @Test fun snakeCaseAndHyphenRules() {
        val t = ScreenTerms.extract("call load_data then use Wi-Fi6 not well-known stuff")
        assertTrue("load_data" in t); assertTrue("Wi-Fi6" in t); assertFalse("well-known" in t)
    }

    @Test fun skipsNonLatinScripts() = assertTrue(ScreenTerms.extract("नमस्ते Kubernetes और TensorFlow").none { it.any { c -> c.code in 0x900..0x97F } })

    @Test fun mostFrequentFirst() {
        val t = ScreenTerms.extract("use PyTorch and TensorFlow and TensorFlow again")
        assertEquals("TensorFlow", t.first())
    }

    @Test fun contextVocabularyDropsOldestWhenFull() {
        val v = ContextVocabulary(maxTerms = 3)
        v.add(listOf("AlphaOne", "BetaTwo", "GammaThree"))
        v.add(listOf("DeltaFour"))
        assertEquals(listOf("BetaTwo", "GammaThree", "DeltaFour"), v.snapshot())
        v.add(listOf("BetaTwo", "EpsilonFive")) // re-seen term is refreshed, so GammaThree is the oldest
        assertEquals(listOf("DeltaFour", "BetaTwo", "EpsilonFive"), v.snapshot())
    }

    @Test fun contextVocabularyClearsAndCorrects() {
        val v = ContextVocabulary()
        v.add(listOf("PyTorch"))
        assertEquals("Use PyTorch, then stop.", v.corrector().correct("Use pie torch, then stop."))
        v.clear()
        assertEquals("Use pie torch, then stop.", v.corrector().correct("Use pie torch, then stop."))
    }

    @Test fun pipelineAppliesScreenTermsAfterUserVocabulary() {
        val ctx = ContextVocabulary().apply { add(listOf("Kubernetes")) }
        val p = SegmentTextPipeline(VocabularyCorrector(emptyList()), VocabularyRules(emptyList()), ctx)
        assertTrue(p.process("we deploy on cuber nettis today", 3_000).contains("Kubernetes"))
        val without = SegmentTextPipeline(VocabularyCorrector(emptyList()), VocabularyRules(emptyList()))
        assertFalse(without.process("we deploy on cuber nettis today", 3_000).contains("Kubernetes"))
    }
}
