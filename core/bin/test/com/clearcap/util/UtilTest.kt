package com.clearcap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UtilTest {
    @Test fun sha256KnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".toByteArray()))
    }

    @Test fun sha256OfFileMatchesBytes() {
        val f = File.createTempFile("hash", ".bin"); f.deleteOnExit()
        f.writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        assertEquals(Sha256.hex(f.readBytes()), Sha256.hex(f))
    }

    @Test fun jsonRoundTripKeepsHindiAndEscapes() {
        val src = mapOf("a" to "नमस्ते \"दुनिया\"\n\ttab", "n" to 5L, "f" to 1.5, "b" to true, "z" to null, "l" to listOf(1L, "x", mapOf("k" to "v")))
        val back = MiniJson.parseObject(MiniJson.write(src))
        assertEquals(src["a"], back["a"])
        assertEquals(5L, back["n"])
        assertEquals(1.5, back["f"])
        assertEquals(true, back["b"])
        assertEquals(null, back["z"])
        assertEquals(listOf(1L, "x", mapOf("k" to "v")), back["l"])
    }

    @Test fun jsonReadsEscapedUnicode() {
        assertEquals("é", MiniJson.parseObject("{\"x\":\"\\u00e9\"}")["x"])
    }

    @Test fun malformedJsonThrows() {
        var failed = 0
        for (bad in listOf("{", "{\"a\":}", "[1,]", "{\"a\" 1}", "nul", "{\"a\":1} x")) {
            try { MiniJson.parse(bad) } catch (e: MiniJson.JsonException) { failed++ }
        }
        assertEquals(6, failed)
    }

    @Test fun prettyOutputParsesBack() {
        val m = mapOf("a" to listOf(1L, 2L), "b" to mapOf("c" to "d"))
        assertEquals(m, MiniJson.parseObject(MiniJson.writePretty(m)))
        assertTrue(MiniJson.writePretty(m).contains("\n"))
    }
}
