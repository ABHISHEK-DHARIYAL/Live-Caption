package com.lecturecaption.app.audio

import com.lecturecaption.app.data.repository.TranscriptSink
import com.lecturecaption.app.stt.RecognitionResult
import com.lecturecaption.app.stt.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * Proves the pipeline contract with a scripted engine standing in for Vosk:
 * only FINAL results are persisted, unchanged, in order, and Finish waits for the last write.
 * (Pure JVM: no Android, no Room, no real audio.)
 */
class TranscriptionPipelineTest {

    private class ScriptedEngine(private val script: List<RecognitionResult>) : SpeechEngine {
        override val id = "FAKE"
        override val displayName = "Fake"
        override val requiredSampleRateHz = 16_000
        private var i = 0
        override suspend fun isReady() = true
        override suspend fun initialize() {}
        override suspend fun acceptAudio(pcm: ShortArray, length: Int, onResult: (RecognitionResult) -> Unit) {
            if (i < script.size) onResult(script[i++])
        }
        override suspend fun finalizeSession(onResult: (RecognitionResult) -> Unit) {
            while (i < script.size) onResult(script[i++])
        }
        override suspend fun release() {}
    }

    private class RecordingSink : TranscriptSink {
        val saved: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun appendSegment(lectureId: Long, startTimeMs: Long, endTimeMs: Long, text: String) {
            saved.add(text)
        }
    }

    private fun r(text: String, final: Boolean) = RecognitionResult(text, final, 0, 0)

    @Test
    fun finalResultsAreSavedInOrder_partialsAreNeverSaved() = runBlocking {
        val script = listOf(
            r("the quick", false),
            r("the quick brown fox", false),
            r("The quick brown fox jumps over the lazy dog.", true),
            r("an array", false),
            r("An array stores elements of the same type.", true),
            r("", true),                                  // blank result must be ignored
            r("Now let's look at an example.", true)
        )
        val sink = RecordingSink()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = TranscriptionPipeline(
            engine = ScriptedEngine(script), sink = sink, scope = scope,
            playbackSpeed = 1f, clockMs = { 0L }, writerDispatcher = Dispatchers.Default
        )
        pipeline.attachLecture(1L)
        repeat(3) { pipeline.onPcmChunk(PcmChunk(ShortArray(1600), 1600)) }
        pipeline.finish() // must not return before the last segment is written

        assertEquals(
            listOf(
                "The quick brown fox jumps over the lazy dog.",
                "An array stores elements of the same type.",
                "Now let's look at an example."
            ),
            sink.saved.toList()
        )
    }
}
