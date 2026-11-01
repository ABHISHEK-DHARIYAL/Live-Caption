package com.clearcap.models

import android.content.Context
import com.clearcap.util.Sha256
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

class ModelException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { STORAGE, NETWORK, CHECKSUM, CORRUPT, UNSUPPORTED_CPU }
}

data class DownloadState(val progress: Float = 0f, val phase: String = "", val error: String? = null)

/** Result of checking an installed model against the catalog. */
data class VerifyResult(val ok: Boolean, val problems: List<String>)

/**
 * Installs, verifies and removes models. Nothing is downloaded until [com.clearcap.work.ModelDownloads.enqueue]
 * is called from a user action. A download is only trusted after (1) its SHA-256 matches the catalog and
 * (2) every unpacked file has the exact size the catalog lists. Files are unpacked into a temporary folder and
 * moved into place at the end, so a model is never half-installed.
 */
class ModelManager(private val ctx: Context) {
    private val _dl = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _dl.asStateFlow()
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val root get() = File(ctx.filesDir, "models").apply { mkdirs() }
    fun dir(m: ModelInfo) = File(root, m.id)
    private fun part(m: ModelInfo) = File(root, "${m.id}.part")

    /** Cheap check used at start-up and before every recording: marker present and every file has its exact size. */
    fun isInstalled(m: ModelInfo): Boolean = verify(m).ok

    fun verify(m: ModelInfo): VerifyResult {
        val d = dir(m)
        if (!d.isDirectory || !File(d, ".complete").exists()) return VerifyResult(false, listOf("Not installed"))
        val problems = m.files.mapNotNull { f ->
            val file = File(d, f.name)
            when {
                !file.exists() -> "Missing file: ${f.name}"
                file.length() != f.bytes -> "Wrong size: ${f.name} (${file.length()} bytes, expected ${f.bytes})"
                else -> null
            }
        }
        return VerifyResult(problems.isEmpty(), problems)
    }

    /** File name -> absolute path, or null when the model is not (correctly) installed. */
    fun paths(m: ModelInfo): Map<String, String>? =
        if (isInstalled(m)) m.files.associate { it.name to File(dir(m), it.name).absolutePath } else null

    fun vadPath(): String? = paths(ModelCatalog.SILERO_VAD)?.values?.firstOrNull()

    fun refresh() { _installed.value = scan() }

    fun setState(m: ModelInfo, s: DownloadState?) = _dl.update { if (s == null) it - m.id else it + (m.id to s) }

    fun delete(m: ModelInfo) {
        part(m).delete()
        File(root, "${m.id}.tmp").deleteRecursively()
        dir(m).deleteRecursively()
        setState(m, null)
        refresh()
    }

    /** Blocking. Runs inside a WorkManager worker. Throws [ModelException] or [CancellationException]. */
    fun downloadBlocking(m: ModelInfo, device: DeviceInfo, active: () -> Boolean) {
        if (isInstalled(m)) { refresh(); setState(m, null); return }
        if (!DeviceProfile.abiSupported(device.abis)) throw ModelException(ModelException.Kind.UNSUPPORTED_CPU, DeviceProfile.fit(m, device).message)
        val have = if (part(m).exists()) part(m).length() else 0L
        val storage = DeviceProfile.storageFit(m, device)
        if (storage.level == FitLevel.BLOCK && have == 0L) throw ModelException(ModelException.Kind.STORAGE, storage.message)

        val p = part(m)
        try {
            setState(m, DownloadState(0f, "Starting"))
            if (p.exists() && p.length() > m.archiveBytes) p.delete()
            if (!p.exists() || p.length() < m.archiveBytes) fetch(m, p, active)
            if (p.length() != m.archiveBytes) throw ModelException(ModelException.Kind.NETWORK, "Download ended early (${p.length()} of ${m.archiveBytes} bytes). It will resume.")

            setState(m, DownloadState(1f, "Verifying checksum"))
            val hash = Sha256.hex(p)
            if (hash != m.archiveSha256) {
                p.delete()
                throw ModelException(ModelException.Kind.CHECKSUM, "The downloaded file is corrupted (checksum mismatch). It was deleted; download again.")
            }

            setState(m, DownloadState(1f, "Unpacking"))
            val tmp = File(root, "${m.id}.tmp").also { it.deleteRecursively(); it.mkdirs() }
            if (m.isSingleFile) p.copyTo(File(tmp, m.files.first().name), overwrite = true)
            else Untar.extract(p, tmp, m.files.map { it.name }.toSet(), active)

            val bad = m.files.mapNotNull { f -> File(tmp, f.name).takeIf { !it.exists() || it.length() != f.bytes }?.let { f.name } }
            if (bad.isNotEmpty()) {
                tmp.deleteRecursively()
                throw ModelException(ModelException.Kind.CORRUPT, "Unpacked files are missing or the wrong size: ${bad.joinToString()}. Download again.")
            }
            File(tmp, ".complete").writeText(m.archiveSha256)
            dir(m).deleteRecursively()
            if (!tmp.renameTo(dir(m))) throw ModelException(ModelException.Kind.CORRUPT, "Could not move the model into place.")
            p.delete()
            refresh()
            setState(m, null)
        } catch (e: CancellationException) {
            File(root, "${m.id}.tmp").deleteRecursively() // the .part file stays so the next Download resumes
            throw e
        } catch (e: ModelException) {
            File(root, "${m.id}.tmp").deleteRecursively()
            setState(m, DownloadState(error = e.message))
            throw e
        } catch (e: IOException) {
            File(root, "${m.id}.tmp").deleteRecursively()
            val msg = if (e.message?.contains("ENOSPC", true) == true) "Ran out of storage while installing." else "Network problem: ${e.message ?: e.javaClass.simpleName}"
            setState(m, DownloadState(error = msg))
            throw ModelException(if (msg.startsWith("Ran out")) ModelException.Kind.STORAGE else ModelException.Kind.NETWORK, msg)
        }
    }

    /** Resumable: continues the .part file with an HTTP Range request. If the server ignores Range, it restarts cleanly. */
    private fun fetch(m: ModelInfo, part: File, active: () -> Boolean) {
        part.parentFile?.mkdirs()
        var have = if (part.exists()) part.length() else 0L
        val c = URL(m.url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            if (code == 416) return
            if (code != 200 && code != 206) throw ModelException(ModelException.Kind.NETWORK, "Server answered $code")
            val append = code == 206
            if (!append) have = 0L
            var done = have
            c.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(have)
                    if (!append) out.setLength(0)
                    val buf = ByteArray(64 * 1024)
                    var lastUi = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (!active()) throw CancellationException("Cancelled")
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 250) { lastUi = now; setState(m, DownloadState((done.toFloat() / m.archiveBytes).coerceAtMost(0.99f), "Downloading")) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun scan(): Set<String> = ModelCatalog.all.filter { isInstalled(it) }.map { it.id }.toSet()
}
