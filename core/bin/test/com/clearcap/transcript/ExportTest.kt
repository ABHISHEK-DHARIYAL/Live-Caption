package com.clearcap.transcript

import com.clearcap.util.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {
    private val doc = ExportDoc(
        title = "Team call", startedAtMs = 0, durationMs = 65_000, language = "Hinglish", source = "Microphone",
        modelInfo = "whisper-base", version = TextVersion.REFINED, fullText = "Hello there.\n\nयह एक परीक्षण है।",
        segments = listOf(ExportSegment(0, 2000, "Hello there."), ExportSegment(1500, 4000, "यह एक परीक्षण है।")),
    )
    private fun iso(@Suppress("UNUSED_PARAMETER") ms: Long) = "2026-01-01T00:00:00Z"

    @Test fun srtHasIndexedMonotonicCues() {
        val s = TranscriptExporter.export(doc, ExportFormat.SRT, ::iso)
        assertTrue(s.startsWith("1\n00:00:00,000 --> 00:00:02,000\nHello there."))
        // second cue is pushed to start where the first ended, so cues never overlap
        assertTrue(s.contains("2\n00:00:02,000 --> 00:00:04,000"))
    }

    @Test fun vttUsesDotsAndHeader() {
        val s = TranscriptExporter.export(doc, ExportFormat.VTT, ::iso)
        assertTrue(s.startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:02.000"))
    }

    @Test fun timestampedTextLists() {
        val s = TranscriptExporter.export(doc, ExportFormat.TXT_TIMESTAMPS, ::iso)
        assertEquals("[00:00:00] Hello there.\n[00:00:01] यह एक परीक्षण है।\n", s)
    }

    @Test fun jsonKeepsHindiAndSegments() {
        val m = MiniJson.parseObject(TranscriptExporter.export(doc, ExportFormat.JSON, ::iso))
        assertEquals("Team call", m["title"])
        assertEquals("REFINED", m["textVersion"])
        val segs = m["segments"] as List<*>
        assertEquals(2, segs.size)
        assertEquals("यह एक परीक्षण है।", (segs[1] as Map<*, *>)["text"])
    }

    @Test fun markdownHasHeaderAndBody() {
        val s = TranscriptExporter.export(doc, ExportFormat.MARKDOWN, ::iso)
        assertTrue(s.startsWith("# Team call"))
        assertTrue(s.contains("Language setting: Hinglish"))
    }

    @Test fun emptySegmentsAreSkippedInSubtitles() {
        val d = doc.copy(segments = listOf(ExportSegment(0, 1000, "  "), ExportSegment(1000, 2000, "ok")))
        val s = TranscriptExporter.export(d, ExportFormat.SRT, ::iso)
        assertTrue(s.startsWith("1\n00:00:01,000"))
        assertFalse(s.contains("2\n"))
    }

    @Test fun longLinesWrap() {
        val w = TranscriptExporter.wrap("one two three four five six seven eight nine ten eleven twelve", 20)
        assertTrue(w.lines().all { it.length <= 20 })
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("Team_call_1_2.txt", TranscriptExporter.fileName("Team call: 1/2", ExportFormat.TXT))
        assertEquals("transcript.srt", TranscriptExporter.fileName("///", ExportFormat.SRT))
    }
}
