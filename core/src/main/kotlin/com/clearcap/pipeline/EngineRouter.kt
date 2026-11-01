package com.clearcap.pipeline

import com.clearcap.models.DeviceInfo
import com.clearcap.models.DeviceProfile
import com.clearcap.models.DeviceTier
import com.clearcap.models.FitLevel
import com.clearcap.models.ModelCatalog
import com.clearcap.models.ModelInfo
import com.clearcap.transcript.LanguageMode

enum class LiveMode { STREAMING, NEAR_LIVE, NONE }

/**
 * How live captions will be produced for a language and the installed models.
 *  - STREAMING: a streaming transducer, text appears word by word (English only).
 *  - NEAR_LIVE: the speech detector cuts speech into segments and Whisper decodes each one; text appears after
 *    each segment, a few seconds late. Used for Hindi, Hinglish and auto-detect because no streaming model for
 *    them is part of this app.
 *  - NONE: required models are not installed ([missing] lists what to download).
 */
data class Route(
    val mode: LiveMode,
    val liveModel: ModelInfo?,
    val missing: List<ModelInfo>,
    val explanation: String,
)

object EngineRouter {
    fun route(lang: LanguageMode, installed: Set<String>, preferredWhisperId: String?, device: DeviceInfo): Route {
        val vad = ModelCatalog.SILERO_VAD
        val whisperChoice = pickWhisper(installed, preferredWhisperId, device)

        if (lang == LanguageMode.ENGLISH && ModelCatalog.ZIPFORMER_EN.id in installed) {
            return Route(LiveMode.STREAMING, ModelCatalog.ZIPFORMER_EN, emptyList(), "English streaming captions: text appears while people speak.")
        }
        if (lang == LanguageMode.ENGLISH && whisperChoice == null) {
            return Route(LiveMode.NONE, null, listOf(ModelCatalog.ZIPFORMER_EN), "Download the live English model to start captions.")
        }
        // Everything else needs Whisper + the speech detector.
        val missing = ArrayList<ModelInfo>()
        val whisper = whisperChoice ?: DeviceProfile.recommendedWhisper(device).also { missing.add(it) }
        if (vad.id !in installed) missing.add(vad)
        if (missing.isNotEmpty()) {
            val what = if (lang == LanguageMode.ENGLISH) "English fallback" else lang.label
            return Route(LiveMode.NONE, null, missing, "$what captions need ${missing.joinToString(" and ") { it.label }}.")
        }
        val why = if (lang == LanguageMode.ENGLISH)
            "The live English model is not installed, so captions use Whisper: they arrive a few seconds after each phrase."
        else "${lang.label} captions use ${whisper.label}: each phrase is shown a few seconds after it is spoken. There is no streaming model for this language in the app."
        return Route(LiveMode.NEAR_LIVE, whisper, emptyList(), why)
    }

    /** The user's preferred Whisper if installed; otherwise the most accurate installed one the device can run. */
    fun pickWhisper(installed: Set<String>, preferredId: String?, device: DeviceInfo): ModelInfo? {
        ModelCatalog.whisper.firstOrNull { it.id == preferredId && it.id in installed }?.let { return it }
        val inst = ModelCatalog.whisper.filter { it.id in installed }
        if (inst.isEmpty()) return null
        return inst.filter { DeviceProfile.fit(it, device).level != FitLevel.BLOCK }.maxByOrNull { it.relativeAccuracy }
            ?: inst.minByOrNull { it.relativeAccuracy }
    }
}

/** Whether a second pass over finished audio is worth running. It never replaces the original text. */
data class RefinementPlan(val run: Boolean, val model: ModelInfo?, val reason: String)

object RefinementPlanner {
    fun plan(
        enabled: Boolean,
        lang: LanguageMode,
        route: Route,
        installed: Set<String>,
        audioAvailable: Boolean,
        preferredAccurateId: String?,
        device: DeviceInfo,
    ): RefinementPlan {
        if (!enabled) return RefinementPlan(false, null, "Second pass is off.")
        if (!audioAvailable) return RefinementPlan(false, null, "No audio was kept in memory or on disk, so there is nothing to re-check.")
        if (ModelCatalog.SILERO_VAD.id !in installed) return RefinementPlan(false, null, "The speech detector is not installed.")
        val live = route.liveModel ?: return RefinementPlan(false, null, "No live model was used.")

        val candidates = ModelCatalog.accurate.filter { it.id in installed }
            .filter { it.id != live.id }
            .filter { lang == LanguageMode.ENGLISH || it.multilingual }              // Parakeet is English only
            .filter { DeviceProfile.fit(it, device).level != FitLevel.BLOCK }
            .filter { it.relativeAccuracy > live.relativeAccuracy }                   // only a stronger model is worth the time
        if (candidates.isEmpty()) return RefinementPlan(false, null, "No installed model is rated more accurate than the live model (${live.label}).")
        val pick = candidates.firstOrNull { it.id == preferredAccurateId } ?: candidates.maxByOrNull { it.relativeAccuracy }!!
        return RefinementPlan(true, pick, "Re-checking with ${pick.label}. This is not guaranteed to be better; the original text is kept and can be restored.")
    }
}
