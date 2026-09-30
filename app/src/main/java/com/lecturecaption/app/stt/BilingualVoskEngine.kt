package com.lecturecaption.app.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.abs

/**
 * Hindi + English live captions with automatic language detection, fully offline.
 *
 * How the "detection" works: Vosk models are single-language, so we run BOTH a Hindi and an
 * English recognizer on the exact same audio, in parallel. Every recognized word carries a
 * confidence score. For each spoken segment we keep the text from the recognizer that is more
 * confident (the wrong-language recognizer produces low-confidence junk). Hindi comes out in
 * Devanagari, English in Latin script.
 *
 * Limits (honest): a single sentence that mixes both languages ("Hinglish") is written entirely
 * in whichever language scores better for that segment; small Vosk models are less accurate
 * than large cloud models.
 */
class BilingualVoskEngine(
    private val englishModelDir: File,
    private val hindiModelDir: File
) : SpeechEngine {

    override val id = EngineType.BILINGUAL.id
    override val displayName = EngineType.BILINGUAL.displayName
    override val requiredSampleRateHz = 16_000

    private data class Parsed(val text: String, val confidences: List<Double>)

    private class Lane(val label: String, val model: Model, val recognizer: Recognizer) {
        val text = StringBuilder()
        val confidences = ArrayList<Double>()
        var partial = ""
        var gotFinal = false

        fun addFinal(text: String, confs: List<Double>) {
            if (text.isBlank()) return
            if (this.text.isNotEmpty()) this.text.append(' ')
            this.text.append(text.trim())
            confidences.addAll(confs)
        }

        fun live(): String = (text.toString() + " " + partial).trim()

        fun reset() {
            text.setLength(0)
            confidences.clear()
            partial = ""
            gotFinal = false
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private var english: Lane? = null
    private var hindi: Lane? = null

    // Segment bookkeeping (all in "engine time" = amount of audio fed so far).
    private var timelineMs = 0L
    private var segmentStartMs = 0L
    private var segmentChunks = 0
    private var firstFinalChunk = -1
    private var lastPartialShown = ""
    private var decidedOnce = false
    private var lastWinner = "en"

    // Compute optimization: run BOTH recognizers only long enough to decide the language of this
    // segment (LOCK_AFTER_CHUNKS), then feed ONLY the winning recognizer for the rest of the
    // segment. This roughly halves sustained CPU use compared to running both lanes for the
    // full sentence, which is what let the pipeline fall behind at 1.5x/2x on slower phones.
    private var lockedLane: Lane? = null

    // Small batching buffer (see BATCH_SAMPLES).
    private val pending = ShortArray(BATCH_SAMPLES)
    private var pendingLen = 0

    companion object {
        // Audio is fed to Vosk in ~200ms batches (2x fewer native calls / JSON parses than 100ms
        // chunks), so one "chunk" below means one 200ms batch.
        private const val BATCH_SAMPLES = 3_200       // 200ms @ 16kHz
        private const val WAIT_CHUNKS = 2             // after one recognizer ends a sentence, wait for the other
        private const val MAX_SEGMENT_MS = 12_000L    // never hold more than this before deciding
        private const val TIE_MARGIN = 0.05           // scores closer than this -> keep previous language
        private const val LOCK_AFTER_CHUNKS = 2       // ~400ms of dual-recognizer audio before locking a language
    }

    override suspend fun isReady(): Boolean = english != null && hindi != null

    override suspend fun initialize() {
        if (isReady()) return
        require(englishModelDir.isDirectory && englishModelDir.list()?.isNotEmpty() == true) {
            "English speech model not found. Open Settings and download the Hindi + English pack."
        }
        require(hindiModelDir.isDirectory && hindiModelDir.list()?.isNotEmpty() == true) {
            "Hindi speech model not found. Open Settings and download the Hindi + English pack."
        }
        val enModel = Model(englishModelDir.absolutePath)
        val enRec = Recognizer(enModel, requiredSampleRateHz.toFloat()).also { it.setWords(true) }
        val hiModel: Model
        val hiRec: Recognizer
        try {
            hiModel = Model(hindiModelDir.absolutePath)
            hiRec = Recognizer(hiModel, requiredSampleRateHz.toFloat()).also { it.setWords(true) }
        } catch (t: Throwable) {
            enRec.close()
            enModel.close()
            throw t
        }
        english = Lane("en", enModel, enRec)
        hindi = Lane("hi", hiModel, hiRec)
        timelineMs = 0L
        segmentStartMs = 0L
        segmentChunks = 0
        firstFinalChunk = -1
        lastPartialShown = ""
        decidedOnce = false
        lastWinner = "en"
        lockedLane = null
        pendingLen = 0
    }

    override suspend fun acceptAudio(
        pcm: ShortArray,
        length: Int,
        onResult: (RecognitionResult) -> Unit
    ) {
        val en = english ?: return
        val hi = hindi ?: return
        var offset = 0
        while (offset < length) {
            val n = minOf(length - offset, BATCH_SAMPLES - pendingLen)
            System.arraycopy(pcm, offset, pending, pendingLen, n)
            pendingLen += n
            offset += n
            if (pendingLen >= BATCH_SAMPLES) {
                processBatch(en, hi, pending, pendingLen, onResult)
                pendingLen = 0
            }
        }
    }

    private suspend fun processBatch(
        en: Lane,
        hi: Lane,
        pcm: ShortArray,
        length: Int,
        onResult: (RecognitionResult) -> Unit
    ) {
        val bytes = shortsToLittleEndianBytes(pcm, length)

        val locked = lockedLane
        if (locked == null) {
            // Still deciding: both recognizers listen to the same audio in parallel.
            coroutineScope {
                val a = async(Dispatchers.Default) { feed(en, bytes) }
                val b = async(Dispatchers.Default) { feed(hi, bytes) }
                a.await()
                b.await()
            }
        } else {
            // Decided: only the winning recognizer keeps working for the rest of this segment.
            feed(locked, bytes)
        }

        timelineMs += (length * 1000L) / requiredSampleRateHz
        segmentChunks++

        val activeGotFinal = locked?.gotFinal ?: (en.gotFinal || hi.gotFinal)
        if (activeGotFinal && firstFinalChunk < 0) firstFinalChunk = segmentChunks

        if (lockedLane == null && segmentChunks >= LOCK_AFTER_CHUNKS) {
            lockInLanguage(en, hi)
        }

        val waitedEnough = firstFinalChunk >= 0 && segmentChunks - firstFinalChunk >= WAIT_CHUNKS
        val tooLong = timelineMs - segmentStartMs >= MAX_SEGMENT_MS
        if (waitedEnough || tooLong) {
            commitSegment(en, hi, onResult)
        } else {
            emitPartial(en, hi, onResult)
        }
    }

    /** Picks a winner from ~400ms of dual-recognizer evidence, then frees the losing recognizer
     *  from doing any more work this segment (it is flushed/reset, not fed further audio). */
    private fun lockInLanguage(en: Lane, hi: Lane) {
        val enWords = wordCount(en.live())
        val hiWords = wordCount(hi.live())
        val winner = when {
            enWords == hiWords -> if (lastWinner == "hi") hi else en
            enWords > hiWords -> en
            else -> hi
        }
        val loser = if (winner === en) hi else en
        // Flush the loser's internal buffer so it starts the next segment clean; its partial
        // guess for THIS segment is discarded — only the winner's continued recognition counts.
        try { loser.recognizer.finalResult } catch (_: Exception) {}
        loser.reset()
        lockedLane = winner
    }

    override suspend fun finalizeSession(onResult: (RecognitionResult) -> Unit) {
        val en = english ?: return
        val hi = hindi ?: return
        if (pendingLen > 0) { // flush the last partial batch so the end of the lecture isn't lost
            processBatch(en, hi, pending, pendingLen, onResult)
            pendingLen = 0
        }
        commitSegment(en, hi, onResult)
    }

    override suspend fun release() {
        english?.let { it.recognizer.close(); it.model.close() }
        hindi?.let { it.recognizer.close(); it.model.close() }
        english = null
        hindi = null
    }

    // ------------------------------------------------------------------ internals

    private fun feed(lane: Lane, bytes: ByteArray) {
        if (lane.recognizer.acceptWaveForm(bytes, bytes.size)) {
            val parsed = parseFinal(lane.recognizer.result)
            lane.addFinal(parsed.text, parsed.confidences)
            lane.partial = ""
            lane.gotFinal = true
        } else {
            lane.partial = parsePartial(lane.recognizer.partialResult)
        }
    }

    private fun commitSegment(en: Lane, hi: Lane, onResult: (RecognitionResult) -> Unit) {
        val locked = lockedLane
        val winner: Lane? = if (locked != null) {
            // Language was already decided early; only the winner was fed audio this segment.
            val parsed = parseFinal(locked.recognizer.finalResult)
            locked.addFinal(parsed.text, parsed.confidences)
            locked.takeIf { it.text.isNotBlank() }
        } else {
            // Segment ended before LOCK_AFTER_CHUNKS was reached (very short utterance) — fall
            // back to the original dual-score comparison.
            for (lane in listOf(en, hi)) {
                val parsed = parseFinal(lane.recognizer.finalResult)
                lane.addFinal(parsed.text, parsed.confidences)
            }
            val enText = en.text.toString().trim()
            val hiText = hi.text.toString().trim()
            val sEn = score(en)
            val sHi = score(hi)
            when {
                enText.isBlank() && hiText.isBlank() -> null
                enText.isBlank() -> hi
                hiText.isBlank() -> en
                abs(sEn - sHi) < TIE_MARGIN -> if (lastWinner == "hi") hi else en
                sEn > sHi -> en
                else -> hi
            }
        }

        if (winner != null) {
            lastWinner = winner.label
            decidedOnce = true
            onResult(
                RecognitionResult(
                    text = winner.text.toString().trim(),
                    isFinal = true,
                    startTimeMs = segmentStartMs,
                    endTimeMs = timelineMs
                )
            )
        }

        en.reset()
        hi.reset()
        lockedLane = null
        firstFinalChunk = -1
        segmentChunks = 0
        segmentStartMs = timelineMs
        lastPartialShown = ""
    }

    private fun emitPartial(en: Lane, hi: Lane, onResult: (RecognitionResult) -> Unit) {
        val enLive = en.live()
        val hiLive = hi.live()
        val enWords = wordCount(enLive)
        val hiWords = wordCount(hiLive)

        val chosen = when {
            !decidedOnce -> if (hiWords > enWords) hiLive else enLive
            lastWinner == "hi" -> if (enWords >= hiWords + 3) enLive else hiLive
            else -> if (hiWords >= enWords + 3) hiLive else enLive
        }

        if (chosen.isNotBlank() && chosen != lastPartialShown) {
            lastPartialShown = chosen
            onResult(
                RecognitionResult(
                    text = chosen,
                    isFinal = false,
                    startTimeMs = segmentStartMs,
                    endTimeMs = timelineMs
                )
            )
        }
    }

    /** Average word confidence, discounted when there is only a single word (junk is often 1 word). */
    private fun score(lane: Lane): Double {
        if (lane.confidences.isEmpty()) return 0.0
        val avg = lane.confidences.average()
        val volume = minOf(1.0, lane.confidences.size / 2.0)
        return avg * volume
    }

    private fun wordCount(s: String): Int =
        if (s.isBlank()) 0 else s.trim().split(Regex("\\s+")).size

    private fun parseFinal(raw: String): Parsed = try {
        val obj = json.parseToJsonElement(raw).jsonObject
        val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
        val confs = obj["result"]?.jsonArray?.mapNotNull {
            it.jsonObject["conf"]?.jsonPrimitive?.content?.toDoubleOrNull()
        } ?: emptyList()
        Parsed(text, confs)
    } catch (_: Exception) {
        Parsed("", emptyList())
    }

    private fun parsePartial(raw: String): String = try {
        json.parseToJsonElement(raw).jsonObject["partial"]?.jsonPrimitive?.content.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun shortsToLittleEndianBytes(pcm: ShortArray, length: Int): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = pcm[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
