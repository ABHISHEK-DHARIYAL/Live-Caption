package com.clearcap.models

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.CancellationException

/** Unpacks only the files named in [wanted] from a .tar.bz2 (the archives also hold large fp32 copies the app never uses). */
object Untar {
    fun extract(archive: File, into: File, wanted: Set<String>, active: () -> Boolean) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                if (!active()) throw CancellationException("Cancelled")
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || name !in wanted) continue
                File(into, name).outputStream().use { t.copyTo(it) }
            }
        }
    }
}
