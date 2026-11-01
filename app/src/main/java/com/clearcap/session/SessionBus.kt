package com.clearcap.session

import com.clearcap.transcript.DiagnosticsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { IDLE, STARTING, RECORDING, PAUSED, FINALIZING, REFINING }

data class LiveState(
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val lines: List<String> = emptyList(),   // only the most recent caption lines are kept here (bounded); everything is in the database
    val listening: Boolean = false,          // near-live: speech is being heard, its text is not ready yet
    val level: Float = 0f,
    val elapsedMs: Long = 0,
    val behindMs: Long = 0,
    val droppedMs: Long = 0,
    val route: String = "",
    val status: String? = null,
    val error: String? = null,
    val progress: Float? = null,
    val progressLabel: String = "",
    val savedId: String? = null,
    val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot(),
) {
    val running: Boolean get() = phase == Phase.RECORDING || phase == Phase.PAUSED || phase == Phase.STARTING
    val busy: Boolean get() = phase != Phase.IDLE
}

/** Process-wide channel from the recording service to the UI and the floating captions. */
object SessionBus {
    private val _s = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _s.asStateFlow()

    fun update(f: (LiveState) -> LiveState) = _s.update(f)

    /** Starts a new run but keeps the last diagnostics so they stay readable after a failed start. */
    fun reset(phase: Phase) = _s.update { LiveState(phase = phase, diagnostics = it.diagnostics) }
}
