package com.clearcap.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyRulesTest {
    private val rules = VocabularyRules(listOf(
        VocabEntry("Kubernetes", listOf("कुबेरनेट्स", "cuber nets")),
        VocabEntry("Dr. Sharma", listOf("डॉक्टर शर्मा")),
    ))

    @Test fun replacesDevanagariAlias() = assertEquals("हम Kubernetes पर काम कर रहे हैं", rules.apply("हम कुबेरनेट्स पर काम कर रहे हैं"))
    @Test fun replacesMultiWordAliasIgnoringCase() = assertEquals("we use Kubernetes daily", rules.apply("we use Cuber  Nets daily"))
    @Test fun respectsWordBoundaries() = assertEquals("xकुबेरनेट्सy", rules.apply("xकुबेरनेट्सy"))
    @Test fun leavesUnlistedTextAlone() = assertEquals("hello world", rules.apply("hello world"))
    @Test fun emptyRulesAreNoOp() { assertTrue(VocabularyRules(emptyList()).isEmpty); assertEquals("abc", VocabularyRules(emptyList()).apply("abc")) }
    @Test fun ignoresAliasEqualToTerm() = assertTrue(VocabularyRules(listOf(VocabEntry("Abc", listOf("abc")))).isEmpty)

    @Test fun pipelineKeepsRawSeparateAndCleansInventedText() {
        val p = SegmentTextPipeline(VocabularyCorrector(emptyList()), rules)
        assertEquals("", p.process("[MUSIC]", 3_000))
        assertEquals("Hello Kubernetes.", p.process("hello cuber nets", 3_000))
        assertEquals("मैं Kubernetes सीख रहा हूँ।", p.process("मैं कुबेरनेट्स सीख रहा हूँ", 3_000))
    }
}
