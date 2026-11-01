package com.clearcap.session

import android.content.Context
import com.clearcap.asr.OfflineDecoder
import com.clearcap.asr.SAMPLE_RATE
import com.clearcap.asr.Speech
import com.clearcap.asr.SpeechSegmenter
import com.clearcap.data.AppSettings
import com.clearcap.data.TranscriptRepository
import com.clearcap.dsp.AutoGain
import com.clearcap.dsp.PcmSpool
import com.clearcap.models.DeviceInfoReader
import com.clearcap.models.DeviceProfile
import com.clearcap.models.FitLevel
import com.clearcap.models.ModelCatalog
import com.clearcap.models.ModelInfo
import com.clearcap.models.ModelManager
import com.clearcap.pipeline.EngineRouter
import com.clearcap.text.SpeechFilters
import com.clearcap.transcript.LanguageMode
import com.clearcap.transcript.SegmentAligner
import com.clearcap.transcript.SessionStatus
import com.clearcap.transcript.TextVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transcribes a saved recording again from its spooled audio. Used to recover an interrupted session and for
 * "Check audio again" in History. Existing live text is never overwritten: new text becomes the refined version
 * (or, when nothing was captured live, the first recognition of that recording).
 */
class Retranscriber(
    private val ctx: Context,
    private val repo: TranscriptRepository,
    private val models: ModelManager,
) {
    sealed class Outcome {
        data class Done(val segments: Int, val model: ModelInfo) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** Best installed model for the language: Parakeet for English when present, otherwise the strongest Whisper this phone can run. */
    fun chooseModel(lang: LanguageMode, cfg: AppSettings): ModelInfo? {
        val device = DeviceInfoReader.read(ctx)
        val installed = models.installed.value
        if (lang == LanguageMode.ENGLISH && ModelCatalog.PARAKEET_110M.id in installed && DeviceProfile.fit(ModelCatalog.PARAKEET_110M, device).level != FitLevel.BLOCK) return ModelCatalog.PARAKEET_110M
        return EngineRouter.pickWhisper(installed, cfg.preferredWhisperId, device)
    }

    suspend fun run(sessionId: String, cfg: AppSettings, progress: (Float, String) -> Unit, cancelled: () -> Boolean): Outcome = withContext(Dispatchers.Default) {
        val s = repo.getSession(sessionId) ?: return@withContext Outcome.Failed("Recording not found.")
        val audio = repo.audioFile(s) ?: return@withContext Outcome.Failed("No saved audio for this recording (audio storage was off, or it was deleted).")
        val lang = LanguageMode.fromCode(s.languageMode)
        val model = chooseModel(lang, cfg) ?: return@withContext Outcome.Failed("Download a speech model first (Models screen).")
        val vad = models.vadPath() ?: return@withContext Outcome.Failed("Download the speech detector first (Models screen).")
        val paths = models.paths(model) ?: return@withContext Outcome.Failed("${model.label} is not installed correctly.")
        val device = DeviceInfoReader.read(ctx)
        val pipeline = repo.pipeline()
        val pieces = ArrayList<SegmentAligner.Piece>()
        val langs = HashMap<String, Int>()

        try {
            OfflineDecoder(model, paths, if (model.multilingual) lang.whisperLanguage else "", DeviceProfile.threads(device.cores)).use { dec ->
                SpeechSegmenter(vad, OfflineDecoder.MAX_SEGMENT_SEC).use { seg ->
                    fun handle(sp: Speech) {
                        val f = sp.floats()
                        if (!SpeechFilters.isSpeechLike(f)) return
                        val d = dec.decode(f)
                        d.language?.let { langs.merge(it, 1, Int::plus) }
                        val t = pipeline.process(d.text, sp.endMs - sp.startMs)
                        if (t.isNotEmpty()) pieces.add(SegmentAligner.Piece(sp.startMs, sp.endMs, t))
                    }
                    val total = maxOf(1L, PcmSpool.durationMs(audio))
                    val gain = AutoGain()
                    PcmSpool.read(audio) { block, n, startSample ->
                        if (cancelled()) return@read false
                        val b = block.copyOf(n).also { if (cfg.autoGain) gain.apply(it) }
                        seg.feed(b, n, startSample * 1000L / SAMPLE_RATE).forEach { handle(it) }
                        progress((startSample * 1000L / SAMPLE_RATE).toFloat() / total, "Transcribing with ${model.label}")
                        true
                    }
                    if (!cancelled()) seg.flush().forEach { handle(it) }
                }
            }
        } catch (e: Throwable) {
            return@withContext Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (cancelled()) return@withContext Outcome.Failed("Cancelled. The saved audio is still available.")
        if (pieces.isEmpty()) return@withContext Outcome.Failed("No speech was found in the saved audio.")

        val existing = repo.segmentList(sessionId)
        val merged = SegmentAligner.merge(existing, pieces).segments.map { seg ->
            // Nothing was captured live for this stretch: the recovered text is its first recognition, so it is the original.
            if (seg.liveText.isBlank()) seg.copy(liveText = seg.refinedText.orEmpty(), rawText = seg.refinedText, refinedText = null) else seg
        }
        repo.replaceSegments(sessionId, merged)
        val hasRefined = merged.any { it.refinedText != null }
        repo.upsertSession(s.copy(
            status = SessionStatus.COMPLETE.name, endedAt = s.endedAt ?: System.currentTimeMillis(),
            durationMs = maxOf(s.durationMs, PcmSpool.durationMs(audio)), refineModelId = model.id,
            detectedLanguage = langs.maxByOrNull { it.value }?.key ?: s.detectedLanguage,
            activeVersion = if (hasRefined) TextVersion.REFINED.name else TextVersion.LIVE.name,
            statusNote = "Transcribed again from the saved audio with ${model.label}. Original text is kept and can be restored.",
        ))
        if (AudioRetention.deleteAfterFinalize(cfg.audioMode, true)) repo.deleteAudioOf(sessionId)
        Outcome.Done(merged.size, model)
    }
}
