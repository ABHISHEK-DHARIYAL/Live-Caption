package com.lecturecaption.app.audio

import kotlin.math.sqrt

/**
 * Gentle automatic gain control (pure Kotlin, unit-tested).
 *
 * It only ever BOOSTS (gain 1..[maxGain]) and only when a chunk is clearly above both an absolute
 * silence limit and a tracked noise floor, so silence, hiss and background noise are never
 * amplified into something the recognizer "hears" as words. It never attenuates real audio.
 * The gain moves slowly up (no pumping) and quickly down (noise is not boosted for long).
 */
class AutoGain(
    private val maxGain: Float = 4f,
    private val target: Float = 0.12f,      // wanted RMS (about -18 dBFS)
    private val minLevel: Float = 0.006f,   // below this RMS it is silence: never boosted
    private val noiseMargin: Float = 2.5f,  // must be this far above the noise floor to count as speech
) {
    var noiseFloor = 0.003f
        private set
    var gain = 1f
        private set

    /** Applies the gain in place to the first [length] samples. */
    fun apply(samples: ShortArray, length: Int = samples.size) {
        val level = rms(samples, length)
        trackFloor(level)
        val want = desiredGain(level)
        gain += (want - gain) * if (want > gain) ATTACK else RELEASE
        gain = gain.coerceIn(1f, maxGain)
        if (gain <= 1.001f) return
        for (i in 0 until length) {
            samples[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun trackFloor(level: Float) {
        noiseFloor = if (level < noiseFloor) noiseFloor + (level - noiseFloor) * 0.3f
        else noiseFloor + (level - noiseFloor) * 0.0004f
        noiseFloor = noiseFloor.coerceIn(0.0005f, 0.03f)
    }

    private fun desiredGain(level: Float): Float {
        val isSpeech = level > maxOf(minLevel, noiseFloor * noiseMargin)
        if (!isSpeech || level >= target) return 1f
        return (target / level).coerceIn(1f, maxGain)
    }

    companion object {
        private const val ATTACK = 0.1f
        private const val RELEASE = 0.4f

        /** RMS level 0..1 of the first [length] samples. */
        fun rms(s: ShortArray, length: Int = s.size): Float {
            if (length <= 0) return 0f
            var acc = 0.0
            for (i in 0 until length) acc += s[i].toDouble() * s[i]
            return (sqrt(acc / length) / 32768.0).toFloat()
        }
    }
}
