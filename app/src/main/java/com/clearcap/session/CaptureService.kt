package com.clearcap.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.clearcap.MainActivity
import com.clearcap.R
import com.clearcap.app
import com.clearcap.capture.AudioCaptureError
import com.clearcap.capture.AudioCaptureManager
import com.clearcap.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service that owns the capture, the recording session and the caption overlay. */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var capture: AudioCaptureManager? = null
    private var session: RecordingSession? = null
    private var overlay: CaptionOverlay? = null
    private var overlayJob: Job? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSession()
            ACTION_SKIP_ACCURATE -> session?.cancelAccurate()
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        if (session != null) return
        val cfg = app.settings.value
        val source = Source.values().firstOrNull { it.name == i.getStringExtra(EXTRA_SOURCE) } ?: cfg.source
        val wantsMic = source != Source.SYSTEM
        val micOk = has(Manifest.permission.RECORD_AUDIO)
        startForegroundCompat(source, micOk)

        val cap = AudioCaptureManager(this)
        capture = cap
        SessionBus.reset(running = true)

        if (source != Source.MIC) {
            val data = IntentCompat.getParcelableExtra(i, EXTRA_DATA, Intent::class.java)
            if (data == null) return fail("Audio capture permission was not granted")
            cap.attachProjection(i.getIntExtra(EXTRA_CODE, 0), data) { main.post { stopSession() } }
        }
        scope.launch {
            cap.errors.collect { e -> SessionBus.update { it.copy(error = describe(e)) } }
        }
        val s = RecordingSession(app, cap, cfg, ::updateNotification)
        val err = s.start(scope, includeSystem = source != Source.MIC, includeMic = wantsMic)
        if (err != null) {
            // "" means capture already reported its own reason through the error flow.
            main.postDelayed({ fail(err.ifEmpty { SessionBus.state.value.error ?: "Could not start capturing audio" }) }, if (err.isEmpty()) 150 else 0)
            return
        }
        session = s
        updateNotification("Listening...")
        if (cfg.overlay) showOverlay()
    }

    private fun describe(e: AudioCaptureError): String = when (e) {
        AudioCaptureError.PermissionDenied -> "Permission needed: allow microphone access and audio capture."
        AudioCaptureError.PlaybackCaptureUnavailable -> "This Android version cannot capture system audio."
        AudioCaptureError.TargetAppBlocksCapture -> "That app blocks audio capture (protected video). Try the microphone."
        AudioCaptureError.StreamInterrupted -> "Audio capture was interrupted."
        AudioCaptureError.ConcurrentCaptureUnsupported -> "This phone cannot record system audio and the microphone together. Pick one."
        is AudioCaptureError.Unknown -> e.message
    }

    private fun fail(msg: String) {
        SessionBus.update { it.copy(running = false, error = msg) }
        capture?.releaseProjection()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSession() {
        val s = session ?: return
        if (stopping) { s.cancelAccurate(); return }
        stopping = true
        hideOverlay()
        updateNotification("Saving...")
        scope.launch {
            runCatching { s.stop() }
            runCatching { capture?.releaseProjection() }
            main.post {
                session = null
                ServiceCompat.stopForeground(this@CaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            SessionBus.update { it.copy(status = "Allow \"display over other apps\" in Settings to see the floating captions.") }
            return
        }
        val o = CaptionOverlay(this).also { it.show() }
        overlay = o
        overlayJob = ui.launch {
            SessionBus.state.collect { st ->
                o.set(st.partial.ifBlank { st.lines.lastOrNull().orEmpty() }, st.behindMs > 5_000)
            }
        }
    }

    private fun hideOverlay() {
        overlayJob?.cancel()
        overlay?.hide()
        overlay = null
    }

    private fun startForegroundCompat(source: Source, micOk: Boolean) {
        var type = 0
        if (Build.VERSION.SDK_INT >= 29 && source != Source.MIC) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 30 && source != Source.SYSTEM && micOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= 30 && source == Source.MIC && !micOk) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting..."), type)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Captioning", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Clearcap")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        hideOverlay()
        ui.cancel()
        session?.abort()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.clearcap.START"
        const val ACTION_STOP = "com.clearcap.STOP"
        const val ACTION_SKIP_ACCURATE = "com.clearcap.SKIP_ACCURATE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 7

        fun start(ctx: Context, source: Source, code: Int = 0, data: Intent? = null) {
            val i = Intent(ctx, CaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source.name).putExtra(EXTRA_CODE, code)
            if (data != null) i.putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) = ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        fun skipAccurate(ctx: Context) = ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_SKIP_ACCURATE))
    }
}
