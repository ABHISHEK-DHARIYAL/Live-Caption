package com.clearcap.transcript

/**
 * Capture and recognition counters. Numbers and codes only: it never holds transcript text or audio,
 * so the report is safe to copy into a bug report.
 */
data class DiagnosticsSnapshot(
    val appVersion: String = "",
    val androidSdk: Int = 0,
    val device: String = "",
    val abi: String = "",
    val totalRamMb: Int = 0,
    val source: String = "",
    val captureRate: Int = 0,
    val captureChannels: Int = 0,
    val captureBufferFrames: Int = 0,
    val usedSoftwareResampler: Boolean = false,
    val chunksRead: Long = 0,
    val chunksDropped: Long = 0,
    val readErrors: Long = 0,
    val clientSilenced: Boolean = false,
    val clippingWarnings: Long = 0,
    val silentMs: Long = 0,
    val liveMode: String = "",
    val liveModel: String = "",
    val refineModel: String = "",
    val timeToFirstCaptionMs: Long = -1,
    val worstLagMs: Long = 0,
    val segments: Int = 0,
    val lastError: String = "",
    val notes: List<String> = emptyList(),
) {
    fun report(): String = buildString {
        append("Clearcap diagnostics (no transcript text or audio is included)\n")
        append("App: $appVersion | Android SDK $androidSdk | $device | ABI $abi | RAM ${totalRamMb} MB\n")
        append("Source: $source\n")
        append("Capture: ${captureRate} Hz x $captureChannels ch, buffer $captureBufferFrames frames, ")
        append(if (usedSoftwareResampler) "software resampled to 16 kHz mono\n" else "delivered directly as 16 kHz mono\n")
        append("Chunks: read $chunksRead, dropped $chunksDropped, read errors $readErrors\n")
        append("Android silenced this capture: ${if (clientSilenced) "yes" else "no"}\n")
        append("Clipping warnings: $clippingWarnings | current silence: ${silentMs / 1000} s\n")
        append("Live: $liveMode ($liveModel) | refine model: ${refineModel.ifEmpty { "none" }}\n")
        append("Time to first caption: ${if (timeToFirstCaptionMs < 0) "n/a" else "$timeToFirstCaptionMs ms"} | worst lag: $worstLagMs ms | segments: $segments\n")
        if (lastError.isNotEmpty()) append("Last error: $lastError\n")
        notes.forEach { append("Note: $it\n") }
    }
}
