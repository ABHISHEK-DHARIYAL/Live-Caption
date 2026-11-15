package com.clearcap.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyImportTest {
    private val json = """{"id":"abc","title":"Lecture 1","createdAt":1700000000000,"durationMs":65000,"live":"hello world","accurate":"Hello, world.","edited":null,"notes":null,"source":"mic","behindMs":1200}"""

    @Test fun parsesAnOldTranscript() {
        val t = LegacyImport.parse(json)!!
        assertEquals("abc", t.id); assertEquals("Lecture 1", t.title); assertEquals(65_000L, t.durationMs)
        assertEquals("Hello, world.", t.accurate); assertNull(t.edited); assertEquals("mic", t.source)
        assertEquals(TextVersion.REFINED, LegacyImport.initialVersion(t))
    }
    @Test fun editedWinsAndSegmentKeepsOriginalAsRaw() {
        val t = LegacyImport.parse(json.replace("\"edited\":null", "\"edited\":\"My text\""))!!
        assertEquals(TextVersion.EDITED, LegacyImport.initialVersion(t))
        val s = LegacyImport.toSegment(t)
        assertEquals("hello world", s.liveText); assertEquals("hello world", s.rawText); assertEquals(0L, s.startMs)
    }
    @Test fun badInputIsRejectedNotInvented() {
        assertNull(LegacyImport.parse("not json"))
        assertNull(LegacyImport.parse("""{"title":"no id"}"""))
        assertNotNull(LegacyImport.parse("""{"id":"x"}"""))
    }
}
