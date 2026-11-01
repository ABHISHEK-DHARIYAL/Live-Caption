package com.clearcap.session

import com.clearcap.data.TranscriptRepository
import com.clearcap.dsp.PcmSpool
import com.clearcap.transcript.SessionStatus

/**
 * Runs when the app starts and no recording is active. A session still marked RECORDING or FINALIZING means the
 * process was killed. Its segments are already saved; it is marked INTERRUPTED and, if audio was kept, can be
 * transcribed again by the user. Nothing heavy runs automatically.
 */
object RecoverySweep {
    suspend fun run(repo: TranscriptRepository): Int {
        var n = 0
        for (s in repo.runningSessions()) {
            val bytes = repo.audioFile(s)?.length() ?: 0L
            val d = RecoveryPlanner.classify(SessionStatus.valueOf(s.status), bytes)
            if (d.action == RecoveryAction.NONE) continue
            val ms = if (bytes > 0) PcmSpool.durationMs(bytes) else s.durationMs
            repo.upsertSession(s.copy(
                status = SessionStatus.INTERRUPTED.name, durationMs = maxOf(s.durationMs, ms), endedAt = s.endedAt ?: System.currentTimeMillis(),
                statusNote = d.reason,
            ))
            n++
        }
        return n
    }
}
