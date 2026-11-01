package com.clearcap.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyImportTest {
    @Test fun plainTermsSplitOnCommasAndLines() {
        val r = VocabularyImport.parse("Kubernetes, PyTorch\nOpenWhispr")
        assertEquals(listOf("Kubernetes", "PyTorch", "OpenWhispr"), r.map { it.term })
    }
    @Test fun aliasSyntax() {
        val r = VocabularyImport.parse("कुबेरनेट्स, cuber nets => Kubernetes")
        assertEquals(1, r.size)
        assertEquals("Kubernetes", r[0].term)
        assertEquals(listOf("कुबेरनेट्स", "cuber nets"), r[0].spokenAs)
    }
    @Test fun duplicatesAndBlanksDropped() = assertEquals(1, VocabularyImport.parse("a\n\nA\n").size)
    @Test fun emptyInput() { assertTrue(VocabularyImport.parse(null).isEmpty()); assertTrue(VocabularyImport.parse("   ").isEmpty()) }
}
