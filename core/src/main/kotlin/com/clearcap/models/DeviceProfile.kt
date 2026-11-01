package com.clearcap.models

/** What the app knows about the phone. All values come from Android APIs at runtime; this class only holds them. */
data class DeviceInfo(
    val totalRamMb: Int,
    val availRamMb: Int,
    val freeStorageMb: Long,
    val cores: Int,
    val abis: List<String>,
    val sdk: Int,
)

enum class FitLevel { OK, WARN, BLOCK }

data class Fit(val level: FitLevel, val message: String)

/**
 * Chooses and vets models for a device. The RAM numbers are conservative estimates, NOT measurements:
 * they exist to stop obviously unsuitable choices (a 640 MB Whisper model on a 2 GB phone), and every
 * message says so. Real memory and speed must be measured on devices (see docs/EVALUATION.md).
 */
object DeviceProfile {
    /** Android reports total RAM a little under the nominal size (a "4 GB" phone shows about 3.7 GB). */
    fun tier(totalRamMb: Int): DeviceTier = when {
        totalRamMb < 3_000 -> DeviceTier.LOW
        totalRamMb < 5_500 -> DeviceTier.MID
        else -> DeviceTier.HIGH
    }

    /** The bundled sherpa-onnx library has native code for these CPU types only. */
    val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")

    fun abiSupported(abis: List<String>): Boolean = abis.any { it in SUPPORTED_ABIS }

    /** Download needs the archive plus the unpacked files on disk at the same time (the archive is deleted after). */
    fun peakStorageMb(m: ModelInfo): Long = ((m.archiveBytes + m.installedBytes) * 11L / 10L + 999_999) / 1_000_000

    fun storageFit(m: ModelInfo, d: DeviceInfo): Fit {
        val need = peakStorageMb(m)
        return if (d.freeStorageMb < need) Fit(FitLevel.BLOCK, "Needs about $need MB of free storage while installing; ${d.freeStorageMb} MB is free.")
        else Fit(FitLevel.OK, "Storage OK.")
    }

    fun fit(m: ModelInfo, d: DeviceInfo): Fit {
        if (!abiSupported(d.abis)) return Fit(FitLevel.BLOCK, "This CPU (${d.abis.firstOrNull() ?: "unknown"}) is not supported by the speech library (arm64-v8a or armeabi-v7a needed).")
        if (d.totalRamMb < m.minTotalRamMb * 3 / 4) {
            return Fit(FitLevel.BLOCK, "Estimated to need about ${m.minTotalRamMb} MB of RAM; this phone has ${d.totalRamMb} MB. Choose a smaller model.")
        }
        if (d.totalRamMb < m.minTotalRamMb) {
            return Fit(FitLevel.WARN, "Close to this phone's memory limit (estimate: ${m.minTotalRamMb} MB needed, ${d.totalRamMb} MB installed). It may be slow or be stopped by Android.")
        }
        if (d.availRamMb < m.installedMb * 3 / 2 + 250) {
            return Fit(FitLevel.WARN, "Little free memory right now (${d.availRamMb} MB). Close other apps before starting.")
        }
        return Fit(FitLevel.OK, "Should fit this phone (estimate, not a measurement).")
    }

    /** Largest multilingual Whisper model suited to the device tier. */
    fun recommendedWhisper(d: DeviceInfo): ModelInfo {
        val tier = tier(d.totalRamMb)
        return ModelCatalog.whisper
            .filter { it.minTier.ordinal <= tier.ordinal && fit(it, d).level != FitLevel.BLOCK }
            .maxByOrNull { it.relativeAccuracy } ?: ModelCatalog.WHISPER_TINY
    }

    /** Inference threads: leave a core for audio capture and the UI. */
    fun threads(cores: Int, max: Int = 4): Int = (cores - 1).coerceIn(1, max)
}
