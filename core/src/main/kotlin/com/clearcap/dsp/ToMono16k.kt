package com.clearcap.dsp

/**
 * Turns whatever the audio hardware delivers (any sample rate, 1 or more interleaved channels, 16-bit PCM)
 * into the 16 kHz mono PCM16 the speech models expect. Channels are averaged, then resampled by a real
 * low-pass filter ([StreamingResampler]). Chunk sizes may be arbitrary; a trailing partial frame is carried over.
 */
class ToMono16k(val inRate: Int, val inChannels: Int) {
    init { require(inChannels >= 1) { "channels must be >= 1" } }

    private val resampler = StreamingResampler(inRate, TARGET_RATE)
    private var carry = ShortArray(0)

    fun convert(interleaved: ShortArray, length: Int = interleaved.size): ShortArray {
        val total = carry.size + length
        val frames = total / inChannels
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until inChannels) {
                val idx = f * inChannels + c
                sum += if (idx < carry.size) carry[idx].toInt() else interleaved[idx - carry.size].toInt()
            }
            mono[f] = (sum / inChannels).toShort()
        }
        val used = frames * inChannels
        val rest = total - used
        val nc = ShortArray(rest)
        for (i in 0 until rest) {
            val idx = used + i
            nc[i] = if (idx < carry.size) carry[idx] else interleaved[idx - carry.size]
        }
        carry = nc
        return resampler.process(mono)
    }

    fun flush(): ShortArray = resampler.flush()

    companion object { const val TARGET_RATE = 16_000 }
}
