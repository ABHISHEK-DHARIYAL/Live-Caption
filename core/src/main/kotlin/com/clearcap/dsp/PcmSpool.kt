package com.clearcap.dsp

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Raw little-endian PCM16, 16 kHz, mono, appended to a file. This is the on-disk spool used for crash recovery
 * and re-transcription (only when the user enabled it). There is no header, so a file cut short by a crash is
 * still fully usable: its length alone gives the duration. An odd trailing byte is ignored.
 */
class PcmSpoolWriter(val file: File, private val flushBytes: Int = 64 * 1024) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file, true), flushBytes)
    private var bytes = file.length()

    val durationMs: Long get() = PcmSpool.durationMs(bytes)

    @Synchronized
    fun write(samples: ShortArray, length: Int) {
        val b = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = samples[i].toInt()
            b[i * 2] = (v and 0xff).toByte()
            b[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        out.write(b)
        bytes += b.size
    }

    @Synchronized fun flush() = out.flush()

    @Synchronized override fun close() {
        runCatching { out.flush() }
        runCatching { out.close() }
    }
}

object PcmSpool {
    const val RATE = 16_000

    fun durationMs(bytes: Long): Long = (bytes / 2) * 1000L / RATE

    fun durationMs(file: File): Long = if (file.exists()) durationMs(file.length()) else 0L

    /** Reads the file in blocks of [blockSamples]; the callback gets (samples, count, startSampleIndex). */
    fun read(file: File, blockSamples: Int = 16_000, onBlock: (ShortArray, Int, Long) -> Boolean) {
        RandomAccessFile(file, "r").use { f ->
            val usable = (f.length() / 2) * 2
            val bytes = ByteArray(blockSamples * 2)
            val shorts = ShortArray(blockSamples)
            var pos = 0L
            var index = 0L
            while (pos < usable) {
                val want = minOf(bytes.size.toLong(), usable - pos).toInt()
                f.seek(pos)
                f.readFully(bytes, 0, want)
                val n = want / 2
                for (i in 0 until n) shorts[i] = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                if (!onBlock(shorts, n, index)) return
                pos += want
                index += n
            }
        }
    }

    fun readAll(file: File): ShortArray {
        val usable = (file.length() / 2).toInt()
        val out = ShortArray(usable)
        read(file) { s, n, start -> System.arraycopy(s, 0, out, start.toInt(), n); true }
        return out
    }
}
