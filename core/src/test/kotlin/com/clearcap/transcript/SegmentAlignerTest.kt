package com.clearcap.transcript

import com.clearcap.transcript.SegmentAligner.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentAlignerTest {
    private fun live(i: Int, s: Long, e: Long, t: String) = Segment(i, s, e, t)

    @Test fun matchingBoundariesPairUpOneToOne() {
        val r = SegmentAligner.merge(
            listOf(live(0, 0, 2000, "hello wrld"), live(1, 3000, 5000, "how are yu")),
            listOf(Piece(0, 2000, "Hello world."), Piece(3000, 5000, "How are you?")),
        )
        assertEquals(2, r.segments.size)
        assertEquals("hello wrld", r.segments[0].liveText)
        assertEquals("Hello world.", r.segments[0].refinedText)
        assertEquals(2, r.accepted)
    }

    @Test fun oneRefinedPieceCoveringTwoLiveSentencesMergesThem() {
        val r = SegmentAligner.merge(
            listOf(live(0, 0, 2000, "first part"), live(1, 2100, 4000, "second part")),
            listOf(Piece(0, 4000, "First part, second part.")),
        )
        assertEquals(1, r.segments.size)
        assertEquals("first part second part", r.segments[0].liveText)
        assertEquals("First part, second part.", r.segments[0].refinedText)
    }

    @Test fun refinedTextThatIsFarLongerIsRejectedAndLiveIsKept() {
        val junk = (1..60).joinToString(" ") { "word$it" }
        val r = SegmentAligner.merge(listOf(live(0, 0, 2000, "short live text here")), listOf(Piece(0, 2000, junk)))
        assertNull(r.segments[0].refinedText)
        assertEquals(1, r.rejected)
        assertEquals("short live text here", r.segments[0].liveText)
    }

    @Test fun repeatedPhraseHallucinationIsRejected() {
        val loop = (1..8).joinToString(" ") { "thank you very" }
        val d = RefinementPolicy.evaluate("thanks", loop)
        assertFalse(d.accept)
    }

    @Test fun speechOnlyFoundByTheSecondPassIsAddedAsItsOwnSegment() {
        val r = SegmentAligner.merge(listOf(live(0, 0, 1000, "hi")), listOf(Piece(10_000, 12_000, "A sentence the live pass missed.")))
        assertEquals(2, r.segments.size)
        assertEquals(1, r.refinedOnly)
        assertEquals("", r.segments[1].liveText)
        assertEquals("A sentence the live pass missed.", r.segments[1].refinedText)
        assertEquals(listOf(0, 1), r.segments.map { it.index })
    }

    @Test fun segmentsNotCoveredByTheSecondPassKeepOnlyLiveText() {
        val r = SegmentAligner.merge(listOf(live(0, 0, 1000, "kept")), emptyList())
        assertNull(r.segments[0].refinedText)
        assertEquals("kept", r.segments[0].textFor(TextVersion.REFINED))
    }

    @Test fun effectiveTextFallsBackToLiveWhenNotRefined() {
        val s = Segment(0, 0, 1, "live", null)
        assertEquals("live", s.textFor(TextVersion.REFINED))
        assertEquals("live", Segment(0, 0, 1, "live", "  ").textFor(TextVersion.REFINED))
        assertEquals("refined", Segment(0, 0, 1, "live", "refined").textFor(TextVersion.REFINED))
        assertEquals("live", Segment(0, 0, 1, "live", "refined").textFor(TextVersion.LIVE))
    }

    @Test fun differentScriptsAreNotRejectedJustForLookingDifferent() {
        val d = RefinementPlanTestHelper.decide("aaj hum naya vishay shuru karenge", "आज हम नया विषय शुरू करेंगे")
        assertTrue(d)
    }

    @Test fun assemblerChoosesTheRequestedVersion() {
        val segs = listOf(Segment(0, 0, 1000, "helo", "Hello."), Segment(1, 1500, 2500, "wrld", null))
        assertEquals("helo wrld", TranscriptAssembler.text(segs, TextVersion.LIVE))
        assertEquals("Hello. wrld", TranscriptAssembler.text(segs, TextVersion.REFINED))
        assertEquals("my edit", TranscriptAssembler.text(segs, TextVersion.EDITED, "my edit"))
        assertEquals("Hello. wrld", TranscriptAssembler.text(segs, TextVersion.EDITED, null))
    }
}

private object RefinementPlanTestHelper {
    fun decide(live: String, refined: String) = RefinementPolicy.evaluate(live, refined).accept
}
