package com.lecturecaption.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.lecturecaption.app.MainActivity
import com.lecturecaption.app.audio.AudioCaptureError
import com.lecturecaption.app.audio.AudioCaptureManager
import com.lecturecaption.app.audio.AudioSource
import com.lecturecaption.app.audio.TranscriptionPipeline
import com.lecturecaption.app.data.repository.LectureRepository
import com.lecturecaption.app.export.TranscriptExporter
import com.lecturecaption.app.overlay.CaptionOverlayManager
import com.lecturecaption.app.overlay.OverlayPrefs
import com.lecturecaption.app.stt.BilingualVoskEngine
import com.lecturecaption.app.stt.EngineType
import com.lecturecaption.app.stt.ModelManager
import com.lecturecaption.app.stt.SpeechEngine
import com.lecturecaption.app.stt.VoskEngine
import com.lecturecaption.app.stt.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TranscriptionState { IDLE, AWAITING_PERMISSION, TRANSCRIBING, STOPPED, ERROR }

/**
 * The one long-running component. It owns the AudioRecord, the STT engine, Room writes and the
 * floating overlay (caption box + small corner "REC" indicator).
 *
 * Start flow (important for Android 14+):
 *  - System audio: UI -> MediaProjectionRequestActivity (consent dialog) -> startWithProjection()
 *    -> this service goes foreground WITH the mediaProjection type -> capture starts.
 *  - Microphone:   UI -> startMic() -> this service goes foreground with the microphone type.
 */
class TranscriptionForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "lecturecaption_transcription"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_MIC = "com.lecturecaption.app.action.START_MIC"
        const val ACTION_START_PROJECTION = "com.lecturecaption.app.action.START_PROJECTION"
        const val ACTION_STOP = "com.lecturecaption.app.action.STOP"

        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_ENGINE = "extra_engine"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_INCLUDE_MIC = "extra_include_mic"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _state = MutableStateFlow(TranscriptionState.IDLE)
        val state: StateFlow<TranscriptionState> = _state

        private val _durationMs = MutableStateFlow(0L)
        val durationMs: StateFlow<Long> = _durationMs

        private val _segmentCount = MutableStateFlow(0)
        val segmentCount: StateFlow<Int> = _segmentCount

        private val _liveCaption = MutableStateFlow("")
        val liveCaption: StateFlow<String> = _liveCaption

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val _currentTitle = MutableStateFlow("")
        val currentTitle: StateFlow<String> = _currentTitle

        private val _savedMessage = MutableStateFlow<String?>(null)
        val savedMessage: StateFlow<String?> = _savedMessage

        private val _backlogWarning = MutableStateFlow<String?>(null)
        val backlogWarning: StateFlow<String?> = _backlogWarning

        fun reportError(message: String) {
            _errorMessage.value = message
            _state.value = TranscriptionState.ERROR
        }

        fun clearSavedMessage() { _savedMessage.value = null }

        private fun resetForNewSession(title: String) {
            _errorMessage.value = null
            _savedMessage.value = null
            _backlogWarning.value = null
            _liveCaption.value = ""
            _segmentCount.value = 0
            _durationMs.value = 0L
            _currentTitle.value = title
        }

        /** Microphone mode: no consent dialog needed, start the service directly. */
        fun startMic(context: Context, language: String, engine: EngineType, title: String, speed: Float) {
            resetForNewSession(title)
            _state.value = TranscriptionState.AWAITING_PERMISSION
            val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
                action = ACTION_START_MIC
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_ENGINE, engine.name)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SPEED, speed)
            }
            context.startForegroundService(intent)
        }

        /** System audio (and optionally mic): call only AFTER the MediaProjection dialog is accepted. */
        fun startWithProjection(
            context: Context, resultCode: Int, projectionData: Intent,
            language: String, engine: String, title: String, speed: Float, includeMic: Boolean
        ) {
            resetForNewSession(title)
            val intent = Intent(context, TranscriptionForegroundService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_INCLUDE_MIC, includeMic)
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_ENGINE, engine)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SPEED, speed)
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, projectionData)
            }
            context.startForegroundService(intent)
        }

        /** [title] = the name the user typed in the "Is it done?" dialog. */
        fun stop(context: Context, title: String?) {
            context.startService(Intent(context, TranscriptionForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TITLE, title)
            })
        }

        /** Marks that the UI is about to show the permission flow (so the status text updates). */
        fun markAwaitingPermission() { _state.value = TranscriptionState.AWAITING_PERMISSION }
    }

    // Main dispatcher: overlay/WindowManager calls MUST run on the main thread.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    private lateinit var repository: LectureRepository
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var overlayManager: CaptionOverlayManager
    private lateinit var modelManager: ModelManager

    private var pipeline: TranscriptionPipeline? = null
    private var lectureId: Long = -1L
    private var language: String = "en-US"
    private var engineType: EngineType = EngineType.VOSK
    private var audioSource: AudioSource = AudioSource.SYSTEM_AUDIO
    private var title: String = "Untitled Lecture"
    private var playbackSpeed: Float = 1f
    private var startedAtMs: Long = 0L
    private var tickerJob: Job? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        repository = LectureRepository(applicationContext)
        audioCaptureManager = AudioCaptureManager(applicationContext)
        overlayManager = CaptionOverlayManager(applicationContext)
        modelManager = ModelManager(applicationContext)
        overlayManager.onIndicatorTap = { openAppToConfirmStop() }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MIC -> handleStart(intent, AudioSource.MICROPHONE)
            ACTION_START_PROJECTION -> handleStart(
                intent,
                if (intent.getBooleanExtra(EXTRA_INCLUDE_MIC, false)) AudioSource.SYSTEM_AND_MICROPHONE
                else AudioSource.SYSTEM_AUDIO
            )
            ACTION_STOP -> handleStop(intent.getStringExtra(EXTRA_TITLE))
            else -> stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun stopSelfIfIdle() {
        if (_state.value != TranscriptionState.TRANSCRIBING) stopSelf()
    }

    private fun handleStart(intent: Intent, source: AudioSource) {
        audioSource = source
        language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "en-US"
        engineType = EngineType.valueOf(intent.getStringExtra(EXTRA_ENGINE) ?: EngineType.VOSK.name)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Untitled Lecture"
        playbackSpeed = intent.getFloatExtra(EXTRA_SPEED, 1f).coerceIn(1f, 3f)
        stopping = false

        // 1) Go foreground immediately with the correct type (must happen within a few seconds
        //    of startForegroundService, and for mediaProjection only AFTER consent — which is
        //    already true here because the consent activity runs first).
        try {
            val type = when (source) {
                AudioSource.SYSTEM_AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                AudioSource.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                AudioSource.SYSTEM_AND_MICROPHONE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, buildNotification("Starting…"), type)
        } catch (e: Exception) {
            fail("Android would not let the app start capture: ${e.message}")
            return
        }

        // 2) System audio (or System + Mic): turn the consent result into a MediaProjection.
        if (source == AudioSource.SYSTEM_AUDIO || source == AudioSource.SYSTEM_AND_MICROPHONE) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            if (data == null) {
                fail("System audio permission data was missing. Please try again.")
                return
            }
            try {
                audioCaptureManager.attachProjection(resultCode, data) {
                    // User tapped "Stop sharing" in the system UI: save what we have.
                    if (!stopping) handleStop(null)
                }
            } catch (e: Exception) {
                fail("Could not start system audio capture: ${e.message}")
                return
            }
        }

        beginCaptureSession()
    }

    private fun fail(message: String) {
        stopping = true // releasing the projection triggers its onStop callback; don't treat that as "save"
        overlayManager.hide()
        reportError(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun beginCaptureSession() {
        serviceScope.launch {
            // Load the speech model FIRST (off the main thread). If it's missing we fail cleanly
            // and no empty lecture is left behind in the database.
            val engine = buildEngine(engineType)
            val newPipeline = TranscriptionPipeline(engine, repository, serviceScope, playbackSpeed)
            try {
                withContext(Dispatchers.Default) { newPipeline.prepareEngine() }
            } catch (e: Throwable) {
                stopping = true
                audioCaptureManager.releaseProjection()
                fail(
                    (e.message ?: "Could not load the speech model.") +
                        if (engineType == EngineType.WHISPER_TINY || engineType == EngineType.WHISPER_BASE || engineType == EngineType.WHISPER_SMALL) "" else " Open Settings and download the speech models first."
                )
                return@launch
            }

            lectureId = repository.startNewLecture(
                title = title,
                language = language,
                audioSource = audioSource.name,
                transcriptionEngine = engineType.name
            )
            newPipeline.attachLecture(lectureId)
            pipeline = newPipeline

            // Subscribe to capture errors BEFORE starting capture (the flow has no replay).
            serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioCaptureManager.errors.collect { err ->
                    _errorMessage.value = err.userFacingMessage()
                }
            }

            val started = when (audioSource) {
                AudioSource.SYSTEM_AUDIO -> audioCaptureManager.startSystemAudioCapture()
                AudioSource.SYSTEM_AND_MICROPHONE -> audioCaptureManager.startSystemAndMicrophoneCapture()
                AudioSource.MICROPHONE -> audioCaptureManager.startMicrophoneCapture()
            }

            if (!started) {
                if (_errorMessage.value == null) _errorMessage.value = "Could not start audio capture."
                repository.delete(lectureId) // don't leave an empty lecture behind
                lectureId = -1L
                stopping = true
                audioCaptureManager.releaseProjection()
                overlayManager.hide()
                _state.value = TranscriptionState.ERROR
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            serviceScope.launch(Dispatchers.Default) {
                audioCaptureManager.chunks.collect { chunk -> pipeline?.onPcmChunk(chunk) }
            }
            serviceScope.launch {
                newPipeline.liveCaptionText.collect { text ->
                    _liveCaption.value = text
                    overlayManager.updateText(text)
                }
            }
            serviceScope.launch {
                newPipeline.segmentCount.collect { count -> _segmentCount.value = count }
            }
            // Watch for the recognizer falling behind real time (2x/1.5x video can cause this on
            // slower phones) and for audio actually being dropped as a result.
            serviceScope.launch {
                var lastDropped = 0
                kotlinx.coroutines.flow.combine(newPipeline.backlogMs, newPipeline.droppedChunks) { backlog, dropped ->
                    backlog to dropped
                }.collect { (backlog, dropped) ->
                    val droppedNewly = dropped > lastDropped
                    lastDropped = dropped
                    _backlogWarning.value = when {
                        droppedNewly -> "Falling behind: some audio was skipped because recognition couldn't keep up. Switch to the English-only engine, or lower the video speed."
                        backlog > 4000L -> "Running about ${backlog / 1000}s behind. If this keeps growing, lower the video speed."
                        else -> null
                    }
                }
            }

            if (overlayManager.canDrawOverlays()) {
                overlayManager.showIndicator()
                overlayManager.show(OverlayPrefs())
            }

            startedAtMs = System.currentTimeMillis()
            _state.value = TranscriptionState.TRANSCRIBING
            startTicker()
        }
    }

    /** Downloading the large English model is itself the user's opt-in to the bigger, more
     *  accurate model — no separate toggle needed. Priority: large (best accuracy) > India
     *  accent (only helps if that's the accent being spoken) > the default small model. */
    private fun pickEnglishModelDir(): File {
        val large = modelManager.availableModels.first { it.key == "VOSK_EN_LARGE" }
        if (modelManager.isDownloaded(large)) return modelManager.dirFor("VOSK_EN_LARGE")
        val enIn = modelManager.availableModels.first { it.key == EngineType.VOSK_EN_IN.id }
        if (modelManager.isDownloaded(enIn)) return modelManager.dirFor(EngineType.VOSK_EN_IN.id)
        return modelManager.dirFor(EngineType.VOSK.id)
    }

    /** The bilingual engine runs TWO recognizers at once, so it must use the small English model.
     *  Using the 128 MB "large" model here is what made it run a minute behind real time. */
    private fun pickBilingualEnglishModelDir(): File {
        val enIn = modelManager.availableModels.first { it.key == EngineType.VOSK_EN_IN.id }
        if (modelManager.isDownloaded(enIn)) return modelManager.dirFor(EngineType.VOSK_EN_IN.id)
        return modelManager.dirFor(EngineType.VOSK.id)
    }

    private fun buildEngine(type: EngineType): SpeechEngine = when (type) {
        EngineType.VOSK -> VoskEngine(applicationContext, pickEnglishModelDir())
        EngineType.BILINGUAL -> BilingualVoskEngine(pickBilingualEnglishModelDir(), modelManager.dirFor(EngineType.VOSK_HI.id))
        else -> WhisperEngine(modelManager.fileFor(type.id), type.id, type.displayName)
    }

    private fun startTicker() {
        tickerJob = serviceScope.launch {
            while (_state.value == TranscriptionState.TRANSCRIBING) {
                val elapsed = System.currentTimeMillis() - startedAtMs
                _durationMs.value = elapsed
                val clock = "%02d:%02d".format(elapsed / 60000, (elapsed / 1000) % 60)
                overlayManager.updateIndicatorTime(clock)
                updateNotification(clock)
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /** Finish the session, save under [finalTitle] (if given), write a named .txt copy. */
    private fun handleStop(finalTitle: String?) {
        if (stopping) return
        stopping = true
        serviceScope.launch {
            tickerJob?.cancel()
            overlayManager.hide()
            try { withContext(Dispatchers.Default) { pipeline?.finish() } } catch (_: Throwable) {}
            audioCaptureManager.releaseProjection()
            kotlinx.coroutines.delay(400) // let the last recognized segment finish writing to Room

            var savedName = finalTitle?.trim().orEmpty().ifBlank { title }
            if (lectureId >= 0) {
                val realDurationMs = maxOf(_durationMs.value, System.currentTimeMillis() - startedAtMs)
                repository.finishLecture(lectureId, realDurationMs, savedName)
                try {
                    val lecture = repository.getLecture(lectureId)
                    if (lecture != null) {
                        val file = withContext(Dispatchers.IO) {
                            TranscriptExporter.saveTxtFile(
                                applicationContext, lecture, repository.getSegments(lectureId)
                            )
                        }
                        savedName = lecture.title
                        _savedMessage.value = "Saved “$savedName” (also as ${file.name})"
                    }
                } catch (_: Exception) {
                    _savedMessage.value = "Saved “$savedName”"
                }
            }
            pipeline = null
            lectureId = -1L
            _state.value = TranscriptionState.STOPPED
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        overlayManager.hide()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Tapping the corner indicator / notification opens the app with the "Is it done?" dialog. */
    private fun openAppToConfirmStop() {
        startActivity(confirmStopIntent(this).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun confirmStopIntent(context: Context) =
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CONFIRM_STOP, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Lecture Transcription", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows ongoing lecture transcription status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finishIntent = PendingIntent.getActivity(
            this, 1, confirmStopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LectureCaption is recording")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Finish & save", finishIntent)
            .build()
    }

    private fun updateNotification(clock: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Transcribing… $clock"))
    }

    private fun AudioCaptureError.userFacingMessage(): String = when (this) {
        is AudioCaptureError.PermissionDenied ->
            "Microphone/audio permission is missing. Allow “Microphone” for LectureCaption in Android Settings → Apps → Permissions."
        is AudioCaptureError.PlaybackCaptureUnavailable ->
            "System audio capture isn't available on this Android version."
        is AudioCaptureError.TargetAppBlocksCapture ->
            "System audio could not be captured. Start playing the video first, and note that some apps block Android playback capture. Try another player/browser or use Microphone mode."
        is AudioCaptureError.StreamInterrupted ->
            "Audio stream was interrupted."
        is AudioCaptureError.ConcurrentCaptureUnsupported ->
            "This device does not support capturing device audio and the microphone at the same time. " +
                "This is an Android/hardware limitation on this phone, not a bug in the app. " +
                "Try System Audio mode or Microphone mode instead."
        is AudioCaptureError.Unknown -> message
    }
}
