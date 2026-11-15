package com.clearcap.capture

/** Which audio the user chose. The two are never mixed. */
enum class CaptureKind { SYSTEM, MICROPHONE }

data class CaptureFormat(val rate: Int, val channels: Int) {
    /** True when the device format must be converted to 16 kHz mono before recognition. */
    val needsConversion: Boolean get() = rate != TARGET_RATE || channels != 1

    /** Interleaved 16-bit samples in one 100 ms chunk. */
    val chunkSamples: Int get() = rate / 10 * channels

    companion object { const val TARGET_RATE = 16_000 }
}

/**
 * Formats to try, in order, when opening the capture. The first one the device accepts is used and recorded in the
 * diagnostics. Microphone: ask for 16 kHz mono first (no conversion needed), then common hardware rates.
 * System audio: ask for the rates Android mixes at first, so the platform does not resample on our behalf.
 */
object CaptureFormats {
    fun candidates(kind: CaptureKind): List<CaptureFormat> = when (kind) {
        CaptureKind.MICROPHONE -> listOf(CaptureFormat(16_000, 1), CaptureFormat(48_000, 1), CaptureFormat(44_100, 1))
        CaptureKind.SYSTEM -> listOf(CaptureFormat(48_000, 2), CaptureFormat(44_100, 2), CaptureFormat(48_000, 1), CaptureFormat(16_000, 1))
    }
}
