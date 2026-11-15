package com.clearcap.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorRatesTest {
    @Test fun identicalTextHasZeroErrors() {
        assertEquals(0.0, ErrorRates.wer("The cat sat.", "the cat sat").rate!!, 1e-9)
    }

    @Test fun oneSubstitutionInFourWords() {
        val s = ErrorRates.wer("the cat sat down", "the dog sat down")
        assertEquals(1, s.substitutions); assertEquals(0, s.deletions); assertEquals(0, s.insertions)
        assertEquals(0.25, s.rate!!, 1e-9)
    }

    @Test fun deletionAndInsertionAreCountedSeparately() {
        val d = ErrorRates.wer("a b c d", "a b d")
        assertEquals(1, d.deletions); assertEquals(0, d.insertions)
        val i = ErrorRates.wer("a b d", "a b c d")
        assertEquals(1, i.insertions); assertEquals(0, i.deletions)
    }

    @Test fun emptyReferenceGivesNoRate() {
        assertNull(ErrorRates.wer("", "anything").rate)
    }

    @Test fun werCanExceedOneWithManyInsertions() {
        assertTrue(ErrorRates.wer("a", "a b c d").rate!! > 1.0)
    }

    @Test fun hindiCharacterErrorRate() {
        val ref = "आज हम नया विषय शुरू करेंगे"
        assertEquals(0.0, ErrorRates.cer(ref, "आज हम नया विषय शुरू करेंगे।").rate!!, 1e-9) // danda ignored
        assertTrue(ErrorRates.cer(ref, "आज हम नया विषय शुरु करेंगे").rate!! > 0.0)
    }

    @Test fun wordSpacingDoesNotAffectCer() {
        assertEquals(0.0, ErrorRates.cer("नया विषय", "नयाविषय").rate!!, 1e-9)
    }

    @Test fun romanVersusDevanagariCountsAsWrong() {
        assertEquals(1.0, ErrorRates.wer("नमस्ते", "namaste").rate!!, 1e-9)
    }

    @Test fun scriptShare() {
        assertEquals(1.0, ErrorRates.devanagariShare("नमस्ते"), 1e-9)
        assertEquals(0.0, ErrorRates.devanagariShare("hello"), 1e-9)
        assertEquals(0.5, ErrorRates.devanagariShare("हा ab"), 0.01)
    }

    @Test fun knownTextbookDistance() {
        // kitten -> sitting = 3 edits over 6 characters
        val s = ErrorRates.edit("kitten".toList(), "sitting".toList())
        assertEquals(3, s.errors)
        assertEquals(2, s.substitutions); assertEquals(1, s.insertions)
    }
}
