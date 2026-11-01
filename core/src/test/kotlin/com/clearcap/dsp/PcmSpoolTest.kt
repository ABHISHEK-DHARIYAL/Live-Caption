package com.clearcap.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PcmSpoolTest {
    private fun tmp(): File = File.createTempFile("spool", ".pcm").also { it.delete(); it.deleteOnExit() }

    @Test fun roundTripKeepsEverySample() {
        val f = tmp()
        val data = ShortArray(40_000) { ((it * 37) % 65535 - 32768).toShort() }
        PcmSpoolWriter(f).use { w -> w.write(data, 25_000); w.write(data.copyOfRange(25_000, 40_000), 15_000) }
        assertTrue(data.contentEquals(PcmSpool.readAll(f)))
        assertEquals(2500L, PcmSpool.durationMs(f))
    }

    @Test fun truncatedFileFromACrashIsStillReadable() {
        val f = tmp()
        PcmSpoolWriter(f).use { it.write(ShortArray(1600) { 5 }, 1600) }
        f.appendBytes(byteArrayOf(9)) // half a sample, as left by a killed process
        assertEquals(1600, PcmSpool.readAll(f).size)
        assertEquals(100L, PcmSpool.durationMs(f))
    }

    @Test fun appendingContinuesAnExistingSpool() {
        val f = tmp()
        PcmSpoolWriter(f).use { it.write(ShortArray(800) { 1 }, 800) }
        PcmSpoolWriter(f).use { it.write(ShortArray(800) { 2 }, 800) }
        val all = PcmSpool.readAll(f)
        assertEquals(1600, all.size)
        assertEquals(1.toShort(), all[0])
        assertEquals(2.toShort(), all[1599])
    }

    @Test fun readerCanStopEarly() {
        val f = tmp()
        PcmSpoolWriter(f).use { it.write(ShortArray(48_000), 48_000) }
        var blocks = 0
        PcmSpool.read(f, 16_000) { _, _, _ -> blocks++; false }
        assertEquals(1, blocks)
    }
}
