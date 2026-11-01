package com.clearcap.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.clearcap.dsp.AutoGain
import com.clearcap.dsp.LevelMeter
import com.clearcap.dsp.ToMono16k
import java.util.concurrent.atomic.AtomicLong

/** Why capture could not start or had to stop. [userMessage] is shown as-is; it never claims audio was captured when it was not. */
class CaptureException(val kind: Kind, message: String?) : Exception(message) {
    enum class Kind { PERMISSION, NO_PROJECTION, BLOCKED, FORMAT, INTERRUPTED, UNKNOWN }

    val userMessage: String
        get() = when (kind) {
            Kind.PERMISSION -> "Permission needed: allow microphone access (Android requires it for system audio too)."
            Kind.NO_PROJECTION -> "System-audio capture was not approved. Tap Start again and accept the screen-capture prompt."
            Kind.BLOCKED -> message ?: "Android could not open this audio source. Another app may be using it, or capture is not allowed here."
            Kind.FORMAT -> "This device did not accept any supported audio format. Try the other source."
            Kind.INTERRUPTED -> message ?: "Audio capture was interrupted."
            Kind.UNKNOWN -> message ?: "Audio capture failed."
        }
}

/** 100 ms of audio at 16 kHz mono. [raw] is the converted signal (what is spooled); [processed] has auto-gain applied (what is recognized). */
class AudioChunk(val startMs: Long, val raw: ShortArray, val processed: ShortArray)

/** Numbers about the capture, safe to show and copy: no audio and no text. */
data class CaptureStats(
    val format: CaptureFormat? = null,
    val bufferFrames: Int = 0,
    val chunksRead: Long = 0,
    val readErrors: Long = 0,
    val clientSilenced: Boolean = false,
    val clippingWarnings: Long = 0,
    val silentMs: Long = 0,
    val level: Float = 0f,
    val capturedMs: Long = 0,
    val reopened: Int = 0,
)

/**
 * One audio source: the microphone OR Android playback capture (other apps' audio). They are never combined.
 *
 * System audio needs a live [MediaProjection] (consent obtained by an Activity) and RECORD_AUDIO. Apps may forbid
 * capture of their audio; Android then delivers silence instead of an error, so [CaptureStats.silentMs] is what
 * lets the UI say "no audio detected" honestly.
 *
 * All reading happens on a dedicated high-priority thread. [onChunk] runs on that thread and must not block.
 */
class AudioCapture(
    private val context: Context,
    private val kind: CaptureKind,
    private val projection: MediaProjection?,
    private val autoGain: Boolean,
    private val onChunk: (AudioChunk) -> Unit,
    private val onFailure: (CaptureException) -> Unit,
) {
    @Volatile private var running = false
    @Volatile var paused = false
    private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null
    private var callback: AudioManager.AudioRecordingCallback? = null
    private val main = Handler(Looper.getMainLooper())

    private val meter = LevelMeter()
    private val agc = AutoGain()
    private val captured16k = AtomicLong(0)
    @Volatile private var format: CaptureFormat? = null
    @Volatile private var bufferFrames = 0
    @Volatile private var silenced = false
    @Volatile private var chunks = 0L
    @Volatile private var readErrors = 0L
    @Volatile private var clipWarnings = 0L
    @Volatile private var reopened = 0
    @Volatile private var snap = LevelMeter.Snapshot(0f, -120f, 0f, 0L, 0L, false)

    fun stats() = CaptureStats(format, bufferFrames, chunks, readErrors, silenced, clipWarnings, snap.silentMs, snap.peak, captured16k.get() * 1000L / CaptureFormat.TARGET_RATE, reopened)

    /** Opens the device and starts the read thread. Throws [CaptureException] with a specific reason on failure. */
    fun start() {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw CaptureException(CaptureException.Kind.PERMISSION, "RECORD_AUDIO not granted")
        }
        if (kind == CaptureKind.SYSTEM && projection == null) throw CaptureException(CaptureException.Kind.NO_PROJECTION, "No MediaProjection")
        val opened = open()
        record = opened.first
        format = opened.second
        watchSilencing(opened.first)
        running = true
        thread = Thread({ readLoop() }, "clearcap-capture").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1500) } }
        thread = null
        callback?.let { cb -> runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(cb) } }
        callback = null
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
    }

    // ---- opening ----

    @SuppressLint("MissingPermission") // checked at the top of start()
    private fun open(): Pair<AudioRecord, CaptureFormat> {
        var lastProblem: CaptureException? = null
        for (fmt in CaptureFormats.candidates(kind)) {
            try {
                val r = build(fmt) ?: continue
                bufferFrames = r.bufferSizeInFrames
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    r.release()
                    lastProblem = CaptureException(CaptureException.Kind.BLOCKED, "Android did not let this app record (the microphone may be in use, for example during a call).")
                    continue
                }
                return r to fmt
            } catch (e: SecurityException) {
                throw CaptureException(CaptureException.Kind.PERMISSION, e.message ?: "Security exception")
            } catch (e: UnsupportedOperationException) {
                // Thrown when the platform refuses playback capture for the current setup.
                throw CaptureException(CaptureException.Kind.BLOCKED, "Android refused system-audio capture on this device or for this audio.")
            } catch (e: IllegalArgumentException) {
                lastProblem = CaptureException(CaptureException.Kind.FORMAT, e.message)   // unsupported rate/channel combination: try the next format
            } catch (e: IllegalStateException) {
                lastProblem = CaptureException(CaptureException.Kind.BLOCKED, e.message)
            }
        }
        throw lastProblem ?: CaptureException(CaptureException.Kind.FORMAT, "No capture format accepted")
    }

    @SuppressLint("MissingPermission")
    private fun build(fmt: CaptureFormat): AudioRecord? {
        val mask = if (fmt.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(fmt.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null // ERROR or ERROR_BAD_VALUE: this rate/channel combination is not supported
        // Room for about 400 ms so a short stall in this app does not overrun the hardware buffer.
        val bytes = maxOf(min * 2, fmt.chunkSamples * 2 * 4)
        val af = AudioFormat.Builder().setSampleRate(fmt.rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val b = AudioRecord.Builder().setAudioFormat(af).setBufferSizeInBytes(bytes)
        if (kind == CaptureKind.SYSTEM) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            b.setAudioPlaybackCaptureConfig(cfg)
        } else {
            b.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        val r = b.build()
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        return r
    }

    /** Android silences a microphone client while another app (a call, the assistant) has priority. Report it instead of showing a blank caption. */
    private fun watchSilencing(r: AudioRecord) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val session = r.audioSessionId
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == session }?.let { silenced = it.isClientSilenced }
            }
        }
        callback = cb
        runCatching { am.registerAudioRecordingCallback(cb, main) }
    }

    // ---- reading ----

    private fun readLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var fmt = format!!
        var buf = ShortArray(fmt.chunkSamples)
        var conv = if (fmt.needsConversion) ToMono16k(fmt.rate, fmt.channels) else null
        var consecutiveErrors = 0
        while (running) {
            val r = record ?: break
            val n = r.read(buf, 0, buf.size)
            if (n > 0) {
                consecutiveErrors = 0
                chunks++
                if (paused) continue // keep draining the hardware buffer, but do not advance the recording timeline
                val mono = if (conv != null) conv.convert(buf, n) else buf.copyOf(n)
                if (mono.isEmpty()) continue
                meter.push(mono)
                snap = meter.snapshot()
                if (snap.clippingWarning) clipWarnings++
                val processed = if (autoGain) mono.copyOf().also { agc.apply(it) } else mono
                val start = captured16k.getAndAdd(mono.size.toLong()) * 1000L / CaptureFormat.TARGET_RATE
                onChunk(AudioChunk(start, mono, processed))
            } else if (n < 0) {
                readErrors++
                consecutiveErrors++
                if (n == AudioRecord.ERROR_DEAD_OBJECT) {
                    // The audio route changed (headset, Bluetooth) or the service restarted: try to open again.
                    val again = reopen() ?: run { fail(CaptureException(CaptureException.Kind.INTERRUPTED, "Audio capture was interrupted and could not be restarted.")); return }
                    fmt = again; buf = ShortArray(fmt.chunkSamples)
                    conv = if (fmt.needsConversion) ToMono16k(fmt.rate, fmt.channels) else null
                    consecutiveErrors = 0
                } else if (consecutiveErrors > 20) {
                    fail(CaptureException(CaptureException.Kind.INTERRUPTED, "Audio capture stopped delivering data (error $n)."))
                    return
                } else {
                    try { Thread.sleep(50) } catch (_: InterruptedException) { return }
                }
            }
        }
    }

    private fun reopen(): CaptureFormat? {
        for (attempt in 1..3) {
            if (!running) return null
            runCatching { record?.stop() }; runCatching { record?.release() }
            try {
                Thread.sleep(300)
                val (r, f) = open()
                record = r; format = f; reopened++
                callback?.let { cb -> runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(cb) } }
                watchSilencing(r)
                return f
            } catch (_: InterruptedException) { return null } catch (_: Exception) { /* try again */ }
        }
        return null
    }

    private fun fail(e: CaptureException) {
        running = false
        onFailure(e)
    }
}
