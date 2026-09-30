package com.lecturecaption.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed class AudioCaptureError : Exception() {
    object PermissionDenied : AudioCaptureError()
    object PlaybackCaptureUnavailable : AudioCaptureError()
    object TargetAppBlocksCapture : AudioCaptureError()
    object StreamInterrupted : AudioCaptureError()
    /** Second AudioRecord (mic) could not be opened alongside playback capture on this device/OEM. */
    object ConcurrentCaptureUnsupported : AudioCaptureError()
    data class Unknown(override val message: String) : AudioCaptureError()
}

enum class AudioSource { SYSTEM_AUDIO, MICROPHONE, SYSTEM_AND_MICROPHONE }

/**
 * One PCM chunk pulled off the device. `samples` is only valid for the duration of the
 * emission — consumers must copy it if they need to hold onto it (the pipeline does, via
 * copyOf in beginReadLoop below).
 */
data class PcmChunk(val samples: ShortArray, val length: Int)

/**
 * Lightweight automatic gain control. Quiet system audio (low device/app volume, a quiet
 * lecturer, a video mixed low) is a common, boring cause of "wrong words" from an ASR model —
 * the recognizer is working on a signal that's mostly noise floor. This tracks a slow envelope
 * of recent peak level and boosts quiet audio back toward a target level, gently, per-sample
 * ramped so consecutive chunks don't click. It does NOT fix a genuinely garbled/interrupted
 * stream, background music, or heavy accents — those are separate, real limits of a small
 * offline model.
 */
private class AutoGainControl {
    private var envelope = 0.1f      // running estimate of recent peak level (0..1)
    private var lastGain = 1f
    private val targetPeak = 0.55f
    private val maxGain = 6f
    private val minGain = 0.8f       // never attenuate below this; avoid double-quieting an already OK signal
    private val attack = 0.5f        // envelope reacts fast to a sudden loud sound
    private val release = 0.03f      // envelope forgets slowly, so a brief pause doesn't spike the gain

    fun apply(samples: ShortArray, length: Int) {
        var peak = 0f
        for (i in 0 until length) {
            val a = kotlin.math.abs(samples[i].toInt())
            if (a > peak) peak = a.toFloat()
        }
        val peakNorm = (peak / 32768f).coerceAtLeast(0.0005f)
        envelope = if (peakNorm > envelope) envelope + attack * (peakNorm - envelope)
        else envelope + release * (peakNorm - envelope)

        val desiredGain = (targetPeak / envelope).coerceIn(minGain, maxGain)
        for (i in 0 until length) {
            val t = if (length > 1) i.toFloat() / (length - 1) else 1f
            val g = lastGain + (desiredGain - lastGain) * t
            samples[i] = (samples[i] * g).coerceIn(-32768f, 32767f).toInt().toShort()
        }
        lastGain = desiredGain
    }
}

/**
 * Owns the MediaProjection + AudioRecord lifecycle. System Audio mode requires:
 *   - Android 10+ (API 29) for AudioPlaybackCaptureConfiguration itself.
 *   - A live MediaProjection obtained via MediaProjectionManager.createScreenCaptureIntent(),
 *     which MUST be launched from an Activity (see MediaProjectionRequestActivity) — a Service
 *     cannot show that system consent dialog itself.
 *   - Android 14+ (API 34): the *foreground service* using the resulting MediaProjection must
 *     declare foregroundServiceType="mediaProjection" and call
 *     MediaProjection.registerCallback() before use, or capture throws SecurityException.
 *   - Microphone mode is never used to satisfy System Audio — the two paths are fully separate;
 *     RECORD_AUDIO permission is only requested/checked on the Microphone path.
 *
 * Some apps mark their audio "not capturable" (e.g. many DRM-protected video/media players,
 * per AudioAttributes.Builder#setAllowedCapturePolicy). We cannot and do not attempt to bypass
 * that — see [AudioCaptureError.TargetAppBlocksCapture] for how we surface it instead.
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE_HZ = 16_000 // matches Vosk/Whisper expected input rate
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SAMPLES = SAMPLE_RATE_HZ / 10 // ~100ms chunks (low latency for fast speech)
    }

    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    // Only used in SYSTEM_AND_MICROPHONE mode: a second, simultaneous AudioRecord for the mic,
    // mixed in software with the playback-capture stream before being handed to the recognizer.
    private var secondaryAudioRecord: AudioRecord? = null
    private var mixThread: Thread? = null

    private val singleSourceAgc = AutoGainControl()

    private val _chunks = MutableSharedFlow<PcmChunk>(
        extraBufferCapacity = 256, // ~25 s of headroom so a busy phone never drops audio
        onBufferOverflow = BufferOverflow.DROP_OLDEST // backpressure: never let memory grow unbounded
    )
    val chunks: SharedFlow<PcmChunk> = _chunks

    private val _errors = MutableSharedFlow<AudioCaptureError>(extraBufferCapacity = 4)
    val errors: SharedFlow<AudioCaptureError> = _errors

    /** Call once you have the Activity-Result Intent+resultCode from MediaProjectionRequestActivity. */
    fun attachProjection(resultCode: Int, data: Intent, onProjectionEnded: () -> Unit = {}) {
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        // Required on Android 10+ before starting playback capture with this projection,
        // and mandatory bookkeeping the platform expects for the projection's lifecycle.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                onProjectionEnded()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    fun hasProjection(): Boolean = mediaProjection != null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked explicitly just below
    fun startSystemAudioCapture(): Boolean {
        val projection = mediaProjection ?: run {
            _errors.tryEmit(AudioCaptureError.PermissionDenied)
            return false
        }
        // Android requires RECORD_AUDIO for AudioPlaybackCapture too (it is NOT optional here).
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            _errors.tryEmit(AudioCaptureError.PermissionDenied)
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _errors.tryEmit(AudioCaptureError.PlaybackCaptureUnavailable)
            return false
        }

        // Matches the audio usages that carry actual lecture/media content. Apps whose audio is
        // tagged AudioAttributes.USAGE_ASSISTANCE_* etc. are deliberately not matched here — this
        // mirrors what Android's own Live Caption targets for playback capture.
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
                .coerceAtLeast(CHUNK_SAMPLES * 2)

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _errors.tryEmit(AudioCaptureError.TargetAppBlocksCapture)
                return false
            }

            audioRecord = record
            beginReadLoop(record)
            true
        } catch (e: SecurityException) {
            _errors.tryEmit(AudioCaptureError.PermissionDenied)
            false
        } catch (e: UnsupportedOperationException) {
            // Thrown when no capturable audio session exists (e.g. the foreground app's
            // AudioAttributes disallow capture) — this is the "target app blocks capture" case.
            _errors.tryEmit(AudioCaptureError.TargetAppBlocksCapture)
            false
        } catch (e: Exception) {
            _errors.tryEmit(AudioCaptureError.Unknown(e.message ?: "Unknown audio capture failure"))
            false
        }
    }

    /**
     * Captures BOTH device/video playback audio AND the microphone at the same time, mixes them
     * sample-by-sample in software, and feeds the SINGLE mixed stream to the recognizer. This is
     * what makes a lecturer's voice (from the video) and the user's own spoken voice ("pause
     * here") both show up in one transcript.
     *
     * Requires RECORD_AUDIO (checked by the caller/service before this is invoked) and an active
     * MediaProjection (via attachProjection). Two AudioRecord sessions run concurrently — this is
     * supported by the Android platform since API 29/30 but is NOT guaranteed on every OEM audio
     * stack. If the microphone AudioRecord fails to initialize while playback capture is active,
     * this returns false and emits [AudioCaptureError.ConcurrentCaptureUnsupported] — it does NOT
     * silently continue as system-audio-only.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO checked below, matching System Audio mode
    fun startSystemAndMicrophoneCapture(): Boolean {
        val projection = mediaProjection ?: run {
            _errors.tryEmit(AudioCaptureError.PermissionDenied)
            return false
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            _errors.tryEmit(AudioCaptureError.PermissionDenied)
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _errors.tryEmit(AudioCaptureError.PlaybackCaptureUnavailable)
            return false
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
            .coerceAtLeast(CHUNK_SAMPLES * 2)

        val playbackRecord: AudioRecord
        try {
            playbackRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (playbackRecord.state != AudioRecord.STATE_INITIALIZED) {
                playbackRecord.release()
                _errors.tryEmit(AudioCaptureError.TargetAppBlocksCapture)
                return false
            }
        } catch (e: SecurityException) {
            _errors.tryEmit(AudioCaptureError.PermissionDenied); return false
        } catch (e: UnsupportedOperationException) {
            _errors.tryEmit(AudioCaptureError.TargetAppBlocksCapture); return false
        } catch (e: Exception) {
            _errors.tryEmit(AudioCaptureError.Unknown(e.message ?: "Playback capture failed")); return false
        }

        val micRecord: AudioRecord
        try {
            micRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING, bufferSize
            )
            if (micRecord.state != AudioRecord.STATE_INITIALIZED) {
                micRecord.release()
                playbackRecord.release()
                // The specific, honest reason: this device's audio HAL would not allow a second
                // concurrent capture session, NOT a generic failure.
                _errors.tryEmit(AudioCaptureError.ConcurrentCaptureUnsupported)
                return false
            }
        } catch (e: Exception) {
            playbackRecord.release()
            _errors.tryEmit(AudioCaptureError.ConcurrentCaptureUnsupported)
            return false
        }

        audioRecord = playbackRecord
        secondaryAudioRecord = micRecord
        beginMixedReadLoop(playbackRecord, micRecord)
        return true
    }

    /** Reads both sources on their own threads (each blocks on AudioRecord.read, as Android
     *  expects), hands each finished chunk to a bounded queue, and a third thread mixes matching
     *  chunks sample-by-sample (simple average, clamped) into ONE PCM stream for the recognizer.
     *  No raw dual-source audio is ever written to disk — only the mixed short-lived buffer, same
     *  as the single-source path. */
    private fun beginMixedReadLoop(playback: AudioRecord, mic: AudioRecord) {
        isCapturing = true
        playback.startRecording()
        mic.startRecording()

        val playbackQueue = java.util.concurrent.ArrayBlockingQueue<ShortArray>(8)
        val micQueue = java.util.concurrent.ArrayBlockingQueue<ShortArray>(8)
        val playbackAgc = AutoGainControl()
        val micAgc = AutoGainControl()

        fun readerThread(
            record: AudioRecord, queue: java.util.concurrent.ArrayBlockingQueue<ShortArray>,
            label: String, agc: AutoGainControl
        ) =
            Thread({
                val buffer = ShortArray(CHUNK_SAMPLES)
                while (isCapturing) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        agc.apply(buffer, read) // normalize THIS source's level before it's mixed
                        queue.offer(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read).copyOf(CHUNK_SAMPLES))
                    } else if (read < 0) {
                        _errors.tryEmit(AudioCaptureError.StreamInterrupted)
                        break
                    }
                }
            }, "AudioCaptureThread-$label").apply { start() }

        val playbackThread = readerThread(playback, playbackQueue, "playback", playbackAgc)
        val micThread = readerThread(mic, micQueue, "mic", micAgc)

        mixThread = Thread({
            val mixed = ShortArray(CHUNK_SAMPLES)
            while (isCapturing) {
                val a = playbackQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val b = micQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: ShortArray(CHUNK_SAMPLES) // brief mic gap -> treat as silence, never blocks the video's audio
                for (i in 0 until CHUNK_SAMPLES) {
                    val sum = a[i] + b[i]
                    mixed[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                _chunks.tryEmit(PcmChunk(mixed.copyOf(), CHUNK_SAMPLES))
            }
            playbackThread.join(500)
            micThread.join(500)
        }, "AudioMixThread").apply { start() }
    }

    @SuppressLint("MissingPermission") // caller must have already checked RECORD_AUDIO for this path
    fun startMicrophoneCapture(): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
                .coerceAtLeast(CHUNK_SAMPLES * 2)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                ENCODING,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _errors.tryEmit(AudioCaptureError.Unknown("Microphone unavailable"))
                return false
            }
            audioRecord = record
            beginReadLoop(record)
            true
        } catch (e: SecurityException) {
            _errors.tryEmit(AudioCaptureError.PermissionDenied)
            false
        } catch (e: Exception) {
            // e.g. IllegalArgumentException for an unsupported sample rate/encoding combo
            // on a particular device — surface it rather than crashing the service.
            _errors.tryEmit(AudioCaptureError.Unknown(e.message ?: "Microphone capture failed"))
            false
        }
    }

    private fun beginReadLoop(record: AudioRecord) {
        isCapturing = true
        record.startRecording()
        captureThread = Thread({
            val buffer = ShortArray(CHUNK_SAMPLES)
            while (isCapturing) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    singleSourceAgc.apply(buffer, read)
                    // Copy so downstream consumers own a stable array (buffer gets reused next loop).
                    val copy = buffer.copyOf(read)
                    _chunks.tryEmit(PcmChunk(copy, read))
                } else if (read < 0) {
                    _errors.tryEmit(AudioCaptureError.StreamInterrupted)
                    break
                }
            }
        }, "AudioCaptureThread").apply { start() }
    }

    fun stopCapture() {
        isCapturing = false
        captureThread?.join(500)
        captureThread = null
        mixThread?.join(1000)
        mixThread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
        secondaryAudioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        secondaryAudioRecord = null
    }

    fun releaseProjection() {
        stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
    }
}
