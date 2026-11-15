package com.clearcap.session

import com.clearcap.transcript.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionAndRecoveryTest {
    @Test fun offWritesNothing() = assertFalse(AudioRetention.spoolEnabled(AudioMode.OFF))
    @Test fun temporaryAndKeepWrite() { assertTrue(AudioRetention.spoolEnabled(AudioMode.TEMPORARY)); assertTrue(AudioRetention.spoolEnabled(AudioMode.KEEP)) }
    @Test fun temporaryDeletedOnlyAfterSuccess() {
        assertTrue(AudioRetention.deleteAfterFinalize(AudioMode.TEMPORARY, true))
        assertFalse(AudioRetention.deleteAfterFinalize(AudioMode.TEMPORARY, false))
        assertFalse(AudioRetention.deleteAfterFinalize(AudioMode.KEEP, true))
        assertFalse(AudioRetention.deleteAfterFinalize(AudioMode.OFF, true))
    }
    @Test fun runningSessionWithAudioIsRetranscribable() {
        val d = RecoveryPlanner.classify(SessionStatus.RECORDING, 16_000L * 2 * 30)
        assertEquals(RecoveryAction.MARK_INTERRUPTED_RETRANSCRIBABLE, d.action)
    }
    @Test fun runningSessionWithTinyAudioIsOnlyMarked() =
        assertEquals(RecoveryAction.MARK_INTERRUPTED, RecoveryPlanner.classify(SessionStatus.FINALIZING, 1_000).action)
    @Test fun completedSessionIsLeftAlone() =
        assertEquals(RecoveryAction.NONE, RecoveryPlanner.classify(SessionStatus.COMPLETE, 10_000_000).action)
}
