package com.clearcap.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionReconcilerTest {
    @Test fun partialsStayProvisionalAndAreNotSegments() {
        val r = CaptionReconciler()
        r.onPartial("hello wor")
        assertEquals("hello wor", r.provisional)
        assertTrue(r.segments.isEmpty())
    }

    @Test fun finalAddsASegmentAndClearsTheProvisionalText() {
        val r = CaptionReconciler()
        r.onPartial("hello")
        val s = r.onFinal("Hello world.", 0, 1500)
        assertNotNull(s)
        assertEquals("", r.provisional)
        assertEquals(1, r.segments.size)
        assertEquals(0, r.segments[0].index)
    }

    @Test fun exactRepeatOfARecentSentenceIsDropped() {
        val r = CaptionReconciler()
        r.onFinal("Thank you all for coming.", 0, 2000)
        assertNull(r.onFinal("thank you all for coming", 2100, 4000))
        assertEquals(1, r.segments.size)
    }

    @Test fun legitimateRepetitionMuchLaterIsKept() {
        val r = CaptionReconciler()
        r.onFinal("Yes.", 0, 500)
        assertNotNull(r.onFinal("Yes.", 20_000, 20_500))
        assertEquals(2, r.segments.size)
    }

    @Test fun textInsideAnOverlappingSegmentIsDropped() {
        val r = CaptionReconciler()
        r.onFinal("we will start the meeting now", 0, 3000)
        assertNull(r.onFinal("start the meeting", 1000, 2500))
        assertEquals(1, r.segments.size)
    }

    @Test fun longerOverlappingTextExtendsTheEarlierSegment() {
        val r = CaptionReconciler()
        r.onFinal("start the meeting", 1000, 2500)
        val s = r.onFinal("we will start the meeting now", 0, 3000)
        assertEquals(1, r.segments.size)
        assertEquals("we will start the meeting now", s?.liveText)
        assertEquals(0L, s?.startMs)
        assertEquals(3000L, s?.endMs)
    }

    @Test fun emptyOrPunctuationOnlyTextIsIgnored() {
        val r = CaptionReconciler()
        assertNull(r.onFinal("   ", 0, 100))
        assertNull(r.onFinal("...", 0, 100))
        assertTrue(r.segments.isEmpty())
    }

    @Test fun hindiDuplicatesAreRecognisedToo() {
        val r = CaptionReconciler()
        r.onFinal("आज हम नया विषय शुरू करेंगे।", 0, 3000)
        assertNull(r.onFinal("आज हम नया विषय शुरू करेंगे", 3100, 5000))
    }

    @Test fun originalLiveTextIsKeptVerbatim() {
        val r = CaptionReconciler()
        r.onFinal("Hello,   world!", 0, 1000)
        assertEquals("Hello,   world!", r.segments[0].liveText)
    }
}
