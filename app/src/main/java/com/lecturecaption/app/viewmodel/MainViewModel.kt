package com.lecturecaption.app.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lecturecaption.app.audio.AudioSource
import com.lecturecaption.app.data.repository.LectureRepository
import com.lecturecaption.app.service.MediaProjectionRequestActivity
import com.lecturecaption.app.service.TranscriptionForegroundService
import com.lecturecaption.app.service.TranscriptionState
import com.lecturecaption.app.stt.EngineType
import com.lecturecaption.app.stt.ModelManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LectureRepository(application)

    var selectedAudioSource = AudioSource.SYSTEM_AUDIO
        private set
    var selectedLanguage = "en-US"
        private set
    var selectedEngine = EngineType.BILINGUAL
        private set
    var selectedSpeed = 1f
        private set

    val state = TranscriptionForegroundService.state
    val durationMs = TranscriptionForegroundService.durationMs
    val segmentCount = TranscriptionForegroundService.segmentCount
    val liveCaption = TranscriptionForegroundService.liveCaption
    val errorMessage = TranscriptionForegroundService.errorMessage
    val currentTitle = TranscriptionForegroundService.currentTitle
    val savedMessage = TranscriptionForegroundService.savedMessage
    val backlogWarning = TranscriptionForegroundService.backlogWarning

    val recentLectures = repository.observeLectures()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setAudioSource(source: AudioSource) { selectedAudioSource = source }
    fun setLanguage(lang: String) { selectedLanguage = lang }
    fun setEngine(engine: EngineType) { selectedEngine = engine }
    fun setSpeed(speed: Float) { selectedSpeed = speed }

    /** Returns false (and shows an error) if the chosen speech model(s) aren't downloaded yet. */
    private fun modelReady(): Boolean {
        val mm = ModelManager(getApplication())
        fun has(key: String) = mm.isDownloaded(mm.availableModels.first { it.key == key })

        val missing: String? = when (selectedEngine) {
            EngineType.BILINGUAL -> {
                val englishOk = has(EngineType.VOSK_EN_IN.id) || has(EngineType.VOSK.id)
                val hindiOk = has(EngineType.VOSK_HI.id)
                when {
                    !englishOk && !hindiOk -> "Hindi and English speech models"
                    !englishOk -> "English speech model"
                    !hindiOk -> "Hindi speech model"
                    else -> null
                }
            }
            else -> if (has(selectedEngine.id)) null
            else mm.availableModels.first { it.key == selectedEngine.id }.displayName
        }
        if (missing == null) return true
        TranscriptionForegroundService.reportError(
            "Missing: $missing. Open Settings and tap “Download Hindi + English pack”, then try again."
        )
        return false
    }

    /** Call only after RECORD_AUDIO has been granted (needed for BOTH audio modes). */
    fun startTranscription() {
        if (!modelReady()) return
        val app = getApplication<Application>()
        val title = "Lecture ${SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(java.util.Date())}"
        val language = if (selectedEngine == EngineType.BILINGUAL) "hi+en" else selectedLanguage
        when (selectedAudioSource) {
            AudioSource.SYSTEM_AUDIO, AudioSource.SYSTEM_AND_MICROPHONE -> {
                TranscriptionForegroundService.markAwaitingPermission()
                val intent = MediaProjectionRequestActivity
                    .createIntent(
                        app, language, selectedEngine.name, title, selectedSpeed,
                        includeMic = selectedAudioSource == AudioSource.SYSTEM_AND_MICROPHONE
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
            }
            AudioSource.MICROPHONE ->
                TranscriptionForegroundService.startMic(app, language, selectedEngine, title, selectedSpeed)
        }
    }

    /** [name] is what the user typed in the "Is it done?" dialog. */
    fun finishAndSave(name: String) {
        TranscriptionForegroundService.stop(getApplication(), name)
    }

    fun dismissSavedMessage() = TranscriptionForegroundService.clearSavedMessage()

    fun deleteLecture(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun renameLecture(id: Long, newTitle: String) {
        viewModelScope.launch { repository.rename(id, newTitle) }
    }

    val currentState: TranscriptionState get() = state.value
}
