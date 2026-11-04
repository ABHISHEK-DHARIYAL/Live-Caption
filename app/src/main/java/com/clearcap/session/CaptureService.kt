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
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.clearcap.MainActivity
import com.clearcap.R
import com.clearcap.app
import com.clearcap.capture.CaptureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns one recording: the capture, the session controller, the MediaProjection and the caption bar.
 * Started only by a user tap in the app. It is NOT sticky: if Android kills it the recording ends, what was saved is kept,
 * and the next app start marks the session as interrupted (see [RecoverySweep]).
 */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val ui = CoroutineScope(job + Dispatchers.Main)
    private var controller: SessionController? = null
    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlay? = null
    private var overlayJob: Job? = null
    private var started = false
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSession()
            ACTION_PAUSE -> controller?.pause()
            ACTION_RESUME -> controller?.resume()
            ACTION_SKIP_REFINE -> controller?.skipRefinement()
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(i: Intent) {
        if (started) return
        started = true
        val kind = CaptureKind.values().firstOrNull { it.name == i.getStringExtra(EXTRA_KIND) } ?: CaptureKind.MICROPHONE
        try {
            startForegroundTyped(kind)
        } catch (e: Exception) {
            return fail("Android did not allow a recording service to start: ${e.message ?: e.javaClass.simpleName}")
        }
        SessionBus.reset(Phase.STARTING)
        val code = i.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java) else i.getParcelableExtra(EXTRA_DATA)

        scope.launch {
            val cfg = app.settings.current()
            var proj: MediaProjection? = null
            if (kind == CaptureKind.SYSTEM) {
                if (data == null) return@launch fail("System-audio capture was not approved. Tap Start and accept the prompt.")
                proj = try {
                    getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                } catch (e: SecurityException) { null }
                if (proj == null) return@launch fail("Android refused the audio-capture permission. Tap Start and accept the prompt again.")
                // Required before capture on Android 14+. Also fires if the user ends sharing from the system UI.
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { SessionBus.update { it.copy(status = "Android ended audio capture.") }; stopSession() } }
                }, main)
                projection = proj
            }
            val c = SessionController(this@CaptureService, app.repo, app.models, scope, ::updateNotification) { main.post { finishService() } }
            controller = c
            val err = c.start(kind, proj, cfg)
            if (err != null) return@launch fail(err)
            withContext(Dispatchers.Main) {
                updateNotification("Listening...")
                if (cfg.overlay) showOverlay(cfg.overlayTextSp, cfg.overlayBottomPx)
            }
        }
    }

    private fun stopSession() {
        val c = controller
        if (c == null) { finishService(); return }
        if (stopRequested) { c.skipRefinement(); return } // a second tap skips the second pass
        stopRequested = true
        hideOverlay()
        updateNotification("Saving...")
        scope.launch { runCatching { c.stop() } }
    }

    private fun finishService() {
        hideOverlay()
        runCatching { projection?.stop() }
        projection = null
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(msg: String) {
        SessionBus.update { it.copy(phase = Phase.IDLE, error = msg) }
        main.post { finishService() }
    }

    // ---- caption bar ----

    private fun showOverlay(textSp: Int, bottomPx: Int) {
        if (!Settings.canDrawOverlays(this)) {
            SessionBus.update { it.copy(status = "Allow \"Display over other apps\" in Settings to see the floating captions.") }
            return
        }
        val o = CaptionOverlay(
            this, textSp, bottomPx,
            onMoved = { y -> app.settings.update { it.copy(overlayBottomPx = y) } },
            onClose = { hideOverlay() }, // closes the floating bar only; recording/transcription keeps running
        ).also { it.show() }
        overlay = o
        overlayJob = SessionBus.state.onEach { st ->
            val warn = when {
                st.phase == Phase.PAUSED -> "Paused"
                st.behindMs > 5_000 -> "Falling behind"
                else -> null
            }
            o.set(st.partial.ifBlank { st.lines.lastOrNull().orEmpty() }, warn)
        }.launchIn(ui)
    }

    private fun hideOverlay() {
        overlayJob?.cancel()
        overlay?.hide()
        overlay = null
    }

    // ---- notification ----

    private fun startForegroundTyped(kind: CaptureKind) {
        // API 29 has no separate microphone type, so it uses the types declared in the manifest.
        val type = when {
            Build.VERSION.SDK_INT < 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            kind == CaptureKind.SYSTEM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(NOTIF_ID, notification("Starting..."), type)
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Captioning", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(label: String, act: String): Notification.Action {
            val pi = PendingIntent.getService(this, act.hashCode(), Intent(this, CaptureService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), label, pi).build()
        }
        val paused = controller?.paused == true
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Clearcap")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(if (paused) "Resume" else "Pause", if (paused) ACTION_RESUME else ACTION_PAUSE))
            .addAction(action("Stop", ACTION_STOP))
            .build()
    }

    override fun onDestroy() {
        hideOverlay()
        // Android is ending the service without a normal Stop: keep what was captured and mark the session interrupted.
        controller?.let { c -> runBlocking(Dispatchers.IO) { withTimeoutOrNull(2_500) { c.abort() } } }
        runCatching { projection?.stop() }
        ui.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.clearcap.START"
        const val ACTION_STOP = "com.clearcap.STOP"
        const val ACTION_PAUSE = "com.clearcap.PAUSE"
        const val ACTION_RESUME = "com.clearcap.RESUME"
        const val ACTION_SKIP_REFINE = "com.clearcap.SKIP_REFINE"
        const val EXTRA_KIND = "kind"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 7

        fun start(ctx: Context, kind: CaptureKind, code: Int = 0, data: Intent? = null) {
            val i = Intent(ctx, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_CODE, code)
            if (data != null) i.putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun send(ctx: Context, action: String) { ctx.startService(Intent(ctx, CaptureService::class.java).setAction(action)) }
        fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
