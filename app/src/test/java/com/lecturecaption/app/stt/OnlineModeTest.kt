package com.lecturecaption.app.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineModeTest {

    private fun seg(ms: Int, v: Float = 0.1f) = FloatArray(16 * ms) { v } // 16 samples per ms at 16 kHz

    // ---- SegmentBatcher ----

    @Test fun closeSegmentsJoinIntoOneBatch() {
        val b = SegmentBatcher()
        assertNull(b.add(0, seg(2000)))
        assertNull(b.add(2500, seg(2000)))   // 500 ms gap: same batch
        val batch = b.flush()!!
        assertEquals(0L, batch.startMs)
        assertEquals(4500L, batch.endMs)
        // 2 s + 2 s of speech + 300 ms of silence between them
        assertEquals(16 * 2000 * 2 + 16 * 300, batch.samples.size)
    }

    @Test fun longPauseStartsNewBatch() {
        val b = SegmentBatcher()
        b.add(0, seg(1000))
        val first = b.add(10_000, seg(1000))   // 9 s gap
        assertNotNull(first)
        assertEquals(0L, first!!.startMs)
        val second = b.flush()!!
        assertEquals(10_000L, second.startMs)
    }

    @Test fun batchIsCappedAtAboutFortyFiveSeconds() {
        val b = SegmentBatcher()
        var closed = 0
        var t = 0L
        repeat(10) {                            // 10 x 10 s back to back = 100 s
            if (b.add(t, seg(10_000)) != null) closed++
            t += 10_000
        }
        assertTrue(closed >= 2)
        assertNotNull(b.flush())
        assertNull(b.flush())
    }

    // ---- GeminiTranscriber helpers ----

    @Test fun wavHeaderIsValid() {
        val wav = GeminiTranscriber.pcmToWav(FloatArray(100) { 0.5f }, 16_000)
        assertEquals(44 + 200, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        // first sample 0.5 -> 16383 (0x3FFF), little endian
        assertEquals(0xFF.toByte(), wav[44])
        assertEquals(0x3F.toByte(), wav[45])
    }

    @Test fun recognisesNoSpeechReplies() {
        assertTrue(GeminiTranscriber.isNoSpeechReply(""))
        assertTrue(GeminiTranscriber.isNoSpeechReply("There is no speech in this audio."))
        assertTrue(GeminiTranscriber.isNoSpeechReply("I'm unable to transcribe this."))
        assertFalse(GeminiTranscriber.isNoSpeechReply("So the time complexity of this loop is n log n."))
        // a long real passage that happens to contain the word is kept
        assertFalse(GeminiTranscriber.isNoSpeechReply("In music theory there is a long silence marked by a rest. ".repeat(4)))
    }

    @Test fun promptCarriesLanguageAndVocabulary() {
        val en = GeminiTranscriber.buildPrompt("en", listOf("Kubernetes", " ", "PyTorch"))
        assertTrue(en.contains("The speech is in English."))
        assertTrue(en.contains("Kubernetes, PyTorch"))
        val mix = GeminiTranscriber.buildPrompt("", emptyList())
        assertTrue(mix.contains("Devanagari"))
        assertFalse(mix.contains("spell them exactly"))
    }

    @Test fun extractsTextFromResponse() {
        val t = GeminiTranscriber("k")
        val ok = """{"candidates":[{"content":{"parts":[{"text":"Hello "},{"text":"world."}]}}]}"""
        assertEquals("Hello world.", t.extractText(ok))
        assertEquals("", t.extractText("""{"candidates":[{"finishReason":"STOP"}]}"""))
        try {
            t.extractText("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            throw AssertionError("expected the block to be reported")
        } catch (e: GeminiTranscriber.TranscribeException) {
            assertFalse(e.retryable)
        }
    }
}
