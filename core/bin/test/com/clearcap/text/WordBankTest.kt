package com.clearcap.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases ported from OpenWhispr's own tests for the same utilities. */
class WordBankTest {
    private val big = listOf(
        "OpenWhispr", "Parakeet", "Alcahest", "Chromium", "TypeScript", "Electron", "testing", "data",
        "benchmark", "inference", "transcription", "dictionary", "microphone", "renderer", "latency", "pipeline",
    ).joinToString(", ")

    // ---- import ----
    @Test fun importAcceptsCommasLinesAndMixes() {
        assertEquals(listOf("Alice", "Bob", "Carol"), DictionaryImport.parse("Alice, Bob, Carol"))
        assertEquals(listOf("Alice", "Bob", "Carol"), DictionaryImport.parse("Alice\nBob\nCarol"))
        assertEquals(listOf("Alice", "Bob", "Carol"), DictionaryImport.parse("Alice,\n Bob\n\nCarol, "))
    }

    @Test fun importDedupesAndStripsMarkup() {
        assertEquals(listOf("Alice", "Bob"), DictionaryImport.parse("Alice, alice; <Bob>"))
        assertEquals(emptyList<String>(), DictionaryImport.parse(null))
    }

    // ---- prompt cap ----
    @Test fun promptIsCutAtTheLastCommaSoNoTermIsHalfSpelled() {
        val t = DictionaryPrompt.trim("alpha, bravo, charlie", 15)
        assertEquals("alpha, bravo", t.prompt)
        assertTrue(t.truncated)
        assertEquals("alpha, bravo", DictionaryPrompt.trim("alpha, bravo", 50).prompt)
    }

    @Test fun promptSkipsBlanksAndDuplicates() =
        assertEquals("Kubernetes, PyTorch", DictionaryPrompt.build(listOf("Kubernetes", " ", "pytorch".replaceFirstChar { it.uppercase() }.let { "PyTorch" }, "kubernetes")))

    // ---- whole-text echo ----
    @Test fun detectsEchoVariants() {
        val d = "OpenWhispr, Parakeet, Alcahest"
        assertTrue(DictionaryEchoFilter.matchesPrompt("OpenWhispr, Parakeet, Alcahest", d))
        assertTrue(DictionaryEchoFilter.matchesPrompt("OpenWhispr, Parakeet, Alcahest.", d))
        assertTrue(DictionaryEchoFilter.matchesPrompt("openwhispr, parakeet, alcahest", d))
        assertTrue(DictionaryEchoFilter.matchesPrompt("OpenWhispr Parakeet Alcahest", d))
        assertTrue(DictionaryEchoFilter.matchesPrompt("OpenWhispr,  Parakeet,  Alcahest", d))
        assertTrue(DictionaryEchoFilter.matchesPrompt("OpenWhispr, Parakeet, Alcahest, OpenWhispr, Parakeet, Alcahest", d))
    }

    @Test fun legitimateSpeechIsNotAnEcho() {
        val d = "OpenWhispr, Parakeet, Alcahest"
        assertFalse(DictionaryEchoFilter.matchesPrompt("I just installed OpenWhispr and it works great", d))
        assertFalse(DictionaryEchoFilter.matchesPrompt("OpenWhispr, Parakeet", d))
        assertFalse(DictionaryEchoFilter.matchesPrompt("The quick brown fox jumps over the lazy dog", d))
        assertFalse(DictionaryEchoFilter.matchesPrompt("some text", null))
        assertFalse(DictionaryEchoFilter.matchesPrompt(null, "OpenWhispr"))
        assertFalse(DictionaryEchoFilter.matchesPrompt("", ""))
        assertFalse(DictionaryEchoFilter.matchesPrompt("...", "..."))
        assertFalse(DictionaryEchoFilter.matchesPrompt("???", "OpenWhispr, Parakeet"))
    }

    @Test fun singleWordAndUnicodeBehaveLikeUpstream() {
        assertTrue(DictionaryEchoFilter.matchesPrompt("OpenWhispr", "OpenWhispr"))
        assertFalse(DictionaryEchoFilter.matchesPrompt("OpenWhispr is great", "OpenWhispr"))
        assertTrue(DictionaryEchoFilter.matchesPrompt("Müller, François, José", "Müller, François, José"))
        assertFalse(DictionaryEchoFilter.matchesPrompt("muller francois jose", "Müller, François, José"))
        assertTrue(DictionaryEchoFilter.matchesPrompt("東京, 大阪", "東京, 大阪"))
        assertTrue(DictionaryEchoFilter.matchesPrompt("श्रीनिवास, रामानुजन", "श्रीनिवास, रामानुजन"))
    }

    @Test fun fillerAmongDictionaryWordsStillCountsAsEcho() {
        val d = "Alpha, Bravo, Charlie, Delta, Echo, Foxtrot, Golf, Hotel, India, Juliet"
        assertTrue(DictionaryEchoFilter.matchesPrompt("Alpha Bravo Charlie Delta Echo Foxtrot Golf Hotel India Juliet the", d))
    }

    // ---- prompt fragments ----
    @Test fun detectsLoopedAndDanglingFragments() {
        assertTrue(DictionaryEchoFilter.isLikelyPromptFragment("data, data, data, data,", big))
        assertTrue(DictionaryEchoFilter.isLikelyPromptFragment("testing,", big))
        assertTrue(DictionaryEchoFilter.isLikelyPromptFragment("testing, ", big))
        assertTrue(DictionaryEchoFilter.isLikelyPromptFragment("OpenWhispr, OpenWhispr, OpenWhispr", big))
    }

    @Test fun ordinaryShortSpeechIsNotAFragment() {
        assertFalse(DictionaryEchoFilter.isLikelyPromptFragment("OpenWhispr is working", big))
        assertFalse(DictionaryEchoFilter.isLikelyPromptFragment("hello there", big))
        assertFalse(DictionaryEchoFilter.isLikelyPromptFragment("testing", big))
        assertFalse(DictionaryEchoFilter.isLikelyPromptFragment("Electron renderer latency", big))
        assertFalse(DictionaryEchoFilter.isLikelyPromptFragment("OpenWhispr Parakeet Alcahest Chromium", big))
        assertFalse(DictionaryEchoFilter.isLikelyPromptFragment("", big))
        assertFalse(DictionaryEchoFilter.isLikelyPromptFragment("testing", null))
    }

    // ---- recital after real speech ----
    @Test fun recitedTailIsRemoved() {
        val dict = listOf("OpenWhispr", "n8n")
        assertEquals(
            "Appreciate the time. Thanks, all. Thanks, you.",
            DictionaryEchoFilter.stripTrailingEcho("Appreciate the time. Thanks, all. Thanks, you. OpenWhispr, n8n.", "OpenWhispr, n8n", dict),
        )
    }

    @Test fun realSentencesThatMentionTermsAreKept() {
        val dict = listOf("OpenWhispr", "n8n")
        val t = "I connected OpenWhispr to n8n today."
        assertEquals(t, DictionaryEchoFilter.stripTrailingEcho(t, "OpenWhispr, n8n", dict))
        val one = "We shipped it. n8n."
        assertEquals(one, DictionaryEchoFilter.stripTrailingEcho(one, "OpenWhispr, n8n", dict))
    }

    @Test fun midListRecitalIsKeptButPromptEndRecitalOfFiveRemoved() {
        val terms = (1..8).map { "Term$it" }
        val prompt = terms.joinToString(", ")
        val mid = "We did it. Term3, Term4."
        assertEquals(mid, DictionaryEchoFilter.stripTrailingEcho(mid, prompt, terms))
        val end = "We did it. Term4, Term5, Term6, Term7, Term8."
        assertEquals("We did it.", DictionaryEchoFilter.stripTrailingEcho(end, prompt, terms))
    }

    @Test fun abbreviationPeriodsDoNotStartATail() {
        val dict = listOf("OpenWhispr", "n8n")
        val t = "Ask Dr. OpenWhispr, n8n."
        assertEquals(t, DictionaryEchoFilter.stripTrailingEcho(t, "OpenWhispr, n8n", dict))
    }

    // ---- correction learner ----
    @Test fun learnsAPhoneticFix() =
        assertTrue(CorrectionLearner.extract("Hey Shunade how are you", "Hey Sinead how are you", emptyList()).contains("Sinead"))

    @Test fun doesNotRelearnKnownWordsInAnyCase() {
        val o = "Hey Shunade how are you"; val e = "Hey Sinead how are you"
        assertFalse(CorrectionLearner.extract(o, e, listOf("Sinead")).contains("Sinead"))
        assertFalse(CorrectionLearner.extract(o, e, listOf("sinead")).contains("Sinead"))
    }

    @Test fun rewritesShortWordsUnrelatedSwapsAndEverydayWordsAreNotLearned() {
        assertEquals(emptyList<String>(), CorrectionLearner.extract("the cat sat on the mat", "a dog stood under a rug", emptyList()))
        assertFalse(CorrectionLearner.extract("I went to see XX today", "I went to see Al today", emptyList()).contains("Al"))
        assertFalse(CorrectionLearner.extract("I saw a cat yesterday", "I saw a elephant yesterday", emptyList()).contains("elephant"))
        val o = "This is why I'm speaking"
        assertEquals(emptyList<String>(), CorrectionLearner.extract(o, "This is what I'm speaking", emptyList()))
        assertEquals(emptyList<String>(), CorrectionLearner.extract(o, "This is where I'm speaking", emptyList()))
    }

    @Test fun emptyOrIdenticalInputsAndRepeatsBehave() {
        assertEquals(emptyList<String>(), CorrectionLearner.extract(null, "hello", emptyList()))
        assertEquals(emptyList<String>(), CorrectionLearner.extract("hello", "", emptyList()))
        assertEquals(emptyList<String>(), CorrectionLearner.extract("hello world", "hello world", emptyList()))
        val r = CorrectionLearner.extract("Shunade said hi to Shunade", "Sinead said hi to Sinead", emptyList())
        assertTrue(r.count { it.equals("sinead", true) } <= 1)
    }
}
