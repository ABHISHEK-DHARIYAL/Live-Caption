package com.clearcap.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiveState(
    val running: Boolean = false,
    val partial: String = "",
    val lines: List<String> = emptyList(),
    val level: Float = 0f,              // 0..1, drives the audio bars
    val elapsedMs: Long = 0,
    val behindMs: Long = 0,             // how far captions lag behind the audio
    val status: String? = null,
    val error: String? = null,
    val progress: Float? = null,        // accurate pass, 0..1; null when not running
    val progressLabel: String = "",
    val savedId: String? = null,
)

/** Process-wide channel from the capture service to the UI and the overlay. */
object SessionBus {
    private val _s = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _s.asStateFlow()

    fun update(f: (LiveState) -> LiveState) = _s.update(f)

    fun reset(running: Boolean) {
        _s.value = LiveState(running = running)
    }
}
