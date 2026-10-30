package com.clearcap.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiveMode(val label: String) { ACCURATE("Accurate"), FAST("Fast") }

enum class Source(val label: String) { SYSTEM("System audio"), MIC("Microphone"), BOTH("Both") }

data class AppSettings(
    val source: Source = Source.SYSTEM,
    val liveMode: LiveMode = LiveMode.ACCURATE, // ACCURATE: strong model per sentence, a few seconds late. FAST: streaming, instant but weaker.
    val accurate: Boolean = true,            // re-transcribe with the accurate model when recording stops
    val accurateModelId: String = "parakeet-600m",
    val language: String = "auto",           // Whisper only: auto | en | hi
    val autoGain: Boolean = true,
    val overlay: Boolean = true,
    val geminiModel: String = "gemini-2.5-flash",
)

class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _s = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _s.asStateFlow()
    val value: AppSettings get() = _s.value

    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_s.value)
        _s.value = n
        p.edit()
            .putString("source", n.source.name).putString("livemode", n.liveMode.name).putBoolean("accurate", n.accurate)
            .putString("amodel", n.accurateModelId).putString("lang", n.language)
            .putBoolean("gain", n.autoGain).putBoolean("overlay", n.overlay).putString("gmodel", n.geminiModel)
            .apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            source = Source.values().firstOrNull { it.name == p.getString("source", null) } ?: d.source,
            liveMode = LiveMode.values().firstOrNull { it.name == p.getString("livemode", null) } ?: d.liveMode,
            accurate = p.getBoolean("accurate", d.accurate),
            accurateModelId = p.getString("amodel", d.accurateModelId) ?: d.accurateModelId,
            language = p.getString("lang", d.language) ?: d.language,
            autoGain = p.getBoolean("gain", d.autoGain),
            overlay = p.getBoolean("overlay", d.overlay),
            geminiModel = (p.getString("gmodel", d.geminiModel) ?: d.geminiModel).ifBlank { d.geminiModel },
        )
    }
}
