package com.clearcap.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clearcap.capture.CaptureKind
import com.clearcap.session.AudioMode
import com.clearcap.transcript.LanguageMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.settingsStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val source: CaptureKind = CaptureKind.SYSTEM,
    val language: LanguageMode = LanguageMode.AUTO,
    val refine: Boolean = false,                // second pass over finished audio. OFF until it is shown to help on the user's own audio
    val preferredWhisperId: String? = null,     // null = the largest installed model this phone can run
    val preferredAccurateId: String? = null,
    val autoGain: Boolean = true,
    val overlay: Boolean = true,
    val overlayTextSp: Int = 18,
    val overlayBottomPx: Int = 180,
    val audioMode: AudioMode = AudioMode.OFF,   // audio is never stored unless the user turns this on
    val transcriptRetentionDays: Int = 0,       // 0 = keep until deleted
    val wifiOnlyDownloads: Boolean = true,
)

/** Preferences in DataStore. [state] is a hot copy for the UI; [current] reads the stored value for background work. */
class SettingsRepository(private val ctx: Context, private val scope: CoroutineScope) {
    private object K {
        val source = stringPreferencesKey("source")
        val language = stringPreferencesKey("language")
        val refine = booleanPreferencesKey("refine")
        val whisper = stringPreferencesKey("whisper")
        val accurate = stringPreferencesKey("accurate")
        val gain = booleanPreferencesKey("gain")
        val overlay = booleanPreferencesKey("overlay")
        val overlaySp = intPreferencesKey("overlay_sp")
        val overlayY = intPreferencesKey("overlay_y")
        val audio = stringPreferencesKey("audio_mode")
        val retention = intPreferencesKey("retention_days")
        val wifi = booleanPreferencesKey("wifi_only")
    }

    private val flow = ctx.settingsStore.data.map { read(it) }
    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    fun update(f: (AppSettings) -> AppSettings) {
        scope.launch { updateNow(f) }
    }

    suspend fun updateNow(f: (AppSettings) -> AppSettings) {
        ctx.settingsStore.edit { p -> write(p, f(read(p))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            source = CaptureKind.values().firstOrNull { it.name == p[K.source] } ?: d.source,
            language = LanguageMode.fromCode(p[K.language]),
            refine = p[K.refine] ?: d.refine,
            preferredWhisperId = p[K.whisper]?.ifBlank { null },
            preferredAccurateId = p[K.accurate]?.ifBlank { null },
            autoGain = p[K.gain] ?: d.autoGain,
            overlay = p[K.overlay] ?: d.overlay,
            overlayTextSp = (p[K.overlaySp] ?: d.overlayTextSp).coerceIn(12, 34),
            overlayBottomPx = (p[K.overlayY] ?: d.overlayBottomPx).coerceAtLeast(0),
            audioMode = AudioMode.values().firstOrNull { it.name == p[K.audio] } ?: d.audioMode,
            transcriptRetentionDays = (p[K.retention] ?: d.transcriptRetentionDays).coerceAtLeast(0),
            wifiOnlyDownloads = p[K.wifi] ?: d.wifiOnlyDownloads,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        p[K.source] = s.source.name
        p[K.language] = s.language.code
        p[K.refine] = s.refine
        p[K.whisper] = s.preferredWhisperId ?: ""
        p[K.accurate] = s.preferredAccurateId ?: ""
        p[K.gain] = s.autoGain
        p[K.overlay] = s.overlay
        p[K.overlaySp] = s.overlayTextSp
        p[K.overlayY] = s.overlayBottomPx
        p[K.audio] = s.audioMode.name
        p[K.retention] = s.transcriptRetentionDays
        p[K.wifi] = s.wifiOnlyDownloads
    }
}
