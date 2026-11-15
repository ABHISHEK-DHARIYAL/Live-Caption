package com.clearcap.session

import com.clearcap.dsp.PcmSpool
import com.clearcap.transcript.SessionStatus

/** OFF: no audio is written. TEMPORARY: written for recovery and re-checking, deleted after a successful save. KEEP: kept until the user deletes it. */
enum class AudioMode { OFF, TEMPORARY, KEEP }

object AudioRetention {
    fun spoolEnabled(mode: AudioMode): Boolean = mode != AudioMode.OFF

    /** Temporary audio is deleted only after the transcript was saved successfully. A failure keeps it so nothing is lost. */
    fun deleteAfterFinalize(mode: AudioMode, finalizeSucceeded: Boolean): Boolean = mode == AudioMode.TEMPORARY && finalizeSucceeded

    /** Audio that is too short to hold speech is not worth keeping or re-transcribing. */
    const val MIN_USEFUL_AUDIO_MS = 2_000L
}

enum class RecoveryAction { NONE, MARK_INTERRUPTED, MARK_INTERRUPTED_RETRANSCRIBABLE }

data class RecoveryDecision(val action: RecoveryAction, val reason: String)

/** Decides what to do with a session found in a running state when the app starts and no capture is active. */
object RecoveryPlanner {
    fun classify(status: SessionStatus, audioBytesOnDisk: Long): RecoveryDecision {
        if (status != SessionStatus.RECORDING && status != SessionStatus.FINALIZING) {
            return RecoveryDecision(RecoveryAction.NONE, "Session is not in a running state.")
        }
        val ms = PcmSpool.durationMs(audioBytesOnDisk)
        return if (ms >= AudioRetention.MIN_USEFUL_AUDIO_MS) {
            RecoveryDecision(RecoveryAction.MARK_INTERRUPTED_RETRANSCRIBABLE, "Recording stopped unexpectedly. ${ms / 1000} s of audio was kept and can be transcribed again.")
        } else {
            RecoveryDecision(RecoveryAction.MARK_INTERRUPTED, "Recording stopped unexpectedly. Saved captions are kept; no audio is available to re-check.")
        }
    }
}
