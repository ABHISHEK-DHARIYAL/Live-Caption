package com.clearcap.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers used to verify downloaded model archives. */
object Sha256 {
    fun hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(input: InputStream, onProgress: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
            onProgress?.invoke(total)
        }
        return toHex(md.digest())
    }

    fun hex(file: File, onProgress: ((Long) -> Unit)? = null): String = file.inputStream().buffered().use { hex(it, onProgress) }

    private fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }
}
