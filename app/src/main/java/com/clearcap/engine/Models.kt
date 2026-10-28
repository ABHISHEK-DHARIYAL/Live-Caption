package com.clearcap.engine

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

enum class ModelKind { LIVE, ACCURATE, VAD }

/** A downloadable model. [speed]/[accuracy] are 1..5 ratings shown in the picker (measured, see README). */
class ModelSpec(
    val id: String,
    val label: String,
    val kind: ModelKind,
    val url: String,
    val downloadMb: Int,
    val speed: Int,
    val accuracy: Int,
    val bestFor: String,
)

/** Everything the app can download. Nothing is fetched until the user taps Download. */
object Models {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    val live = ModelSpec(
        "zipformer-en", "Live English (Zipformer)", ModelKind.LIVE,
        BASE + "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2", 310, 5, 4,
        "Live captions while audio plays. Streaming, about 10x faster than real time on a laptop core.",
    )
    val parakeet = ModelSpec(
        "parakeet-110m", "Parakeet 110M (English)", ModelKind.ACCURATE,
        BASE + "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2", 104, 5, 5,
        "Most accurate English transcript, with punctuation and capitals. Fast.",
    )
    val whisperBase = ModelSpec(
        "whisper-base", "Whisper base (multilingual)", ModelKind.ACCURATE,
        BASE + "sherpa-onnx-whisper-base.tar.bz2", 208, 3, 3,
        "Hindi and other languages. Slower and less accurate than Parakeet for English.",
    )
    val whisperSmall = ModelSpec(
        "whisper-small", "Whisper small (multilingual)", ModelKind.ACCURATE,
        BASE + "sherpa-onnx-whisper-small.tar.bz2", 640, 2, 4,
        "Best for Hindi and mixed languages. Large download, slowest.",
    )
    val vad = ModelSpec(
        "silero-vad", "Speech detector (Silero VAD)", ModelKind.VAD,
        BASE + "silero_vad.onnx", 1, 5, 4,
        "Finds the speech so the accurate pass skips silence and noise. Needed for the accurate transcript.",
    )

    val all = listOf(live, parakeet, whisperBase, whisperSmall, vad)
    val accurate = listOf(parakeet, whisperBase, whisperSmall)
}

data class DownloadState(val progress: Float = 0f, val phase: String = "", val error: String? = null)

class ModelFiles(val a: String, val b: String = "", val c: String = "", val tokens: String)

class ModelManager(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _dl = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _dl.asStateFlow()
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun dir(m: ModelSpec) = File(ctx.filesDir, "models/${m.id}")
    fun isInstalled(m: ModelSpec) = File(dir(m), ".complete").exists()
    fun vadPath(): String = File(dir(Models.vad), "silero_vad.onnx").absolutePath

    /** Streaming transducer: encoder, decoder, joiner + tokens. */
    fun liveFiles(): ModelFiles? {
        val fs = dir(Models.live).listFiles() ?: return null
        fun pick(key: String) = fs.firstOrNull { it.name.contains(key) && it.name.endsWith(".int8.onnx") }?.absolutePath
        val tok = fs.firstOrNull { it.name == "tokens.txt" }?.absolutePath
        val e = pick("encoder") ?: return null
        val d = pick("decoder") ?: return null
        val j = pick("joiner") ?: return null
        return ModelFiles(e, d, j, tok ?: return null)
    }

    /** Parakeet: single model + tokens. Whisper: encoder, decoder + tokens. */
    fun accurateFiles(m: ModelSpec): ModelFiles? {
        val fs = dir(m).listFiles() ?: return null
        val tok = fs.firstOrNull { it.name.endsWith("tokens.txt") }?.absolutePath ?: return null
        if (m == Models.parakeet) {
            val model = fs.firstOrNull { it.name == "model.int8.onnx" }?.absolutePath ?: return null
            return ModelFiles(model, tokens = tok)
        }
        val enc = fs.firstOrNull { it.name.endsWith("-encoder.int8.onnx") }?.absolutePath ?: return null
        val dec = fs.firstOrNull { it.name.endsWith("-decoder.int8.onnx") }?.absolutePath ?: return null
        return ModelFiles(enc, dec, tokens = tok)
    }

    /** Only ever called from a user tap on Download. */
    fun download(m: ModelSpec) {
        if (jobs[m.id]?.isActive == true || isInstalled(m)) return
        _dl.update { it + (m.id to DownloadState(0f, "Starting")) }
        jobs[m.id] = scope.launch { runDownload(m) { isActive } }
    }

    /** Cancel keeps the partly downloaded file, so a later Download resumes where it stopped. */
    fun cancel(m: ModelSpec) {
        jobs[m.id]?.cancel()
        _dl.update { it - m.id }
    }

    fun delete(m: ModelSpec) {
        jobs[m.id]?.cancel()
        _dl.update { it - m.id }
        partFile(m).delete()
        dir(m).deleteRecursively()
        _installed.value = scan()
    }

    private fun partFile(m: ModelSpec) = File(ctx.filesDir, "models/${m.id}.part")

    private fun runDownload(m: ModelSpec, active: () -> Boolean) {
        val part = partFile(m)
        try {
            part.parentFile?.mkdirs()
            val have = if (part.exists()) part.length() else 0L
            val need = m.downloadMb * 1_300_000L - have
            if (ctx.filesDir.usableSpace < need) fail("Not enough free storage (need about ${m.downloadMb * 13 / 10} MB)")
            fetch(m, part, active)
            progress(m, 1f, "Unpacking")
            dir(m).deleteRecursively()
            dir(m).mkdirs()
            if (m.kind == ModelKind.VAD) part.copyTo(File(dir(m), "silero_vad.onnx"), overwrite = true)
            else untar(part, dir(m), active)
            if (m.kind == ModelKind.LIVE && liveFiles() == null) fail("Model files missing in archive")
            if (m.kind == ModelKind.ACCURATE && accurateFiles(m) == null) fail("Model files missing in archive")
            File(dir(m), ".complete").writeText("ok")
            part.delete()
            _installed.value = scan()
            _dl.update { it - m.id }
        } catch (e: CancellationException) {
            if (!isInstalled(m)) dir(m).deleteRecursively()
            throw e
        } catch (e: Exception) {
            if (!isInstalled(m)) dir(m).deleteRecursively()
            _dl.update { it + (m.id to DownloadState(error = e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Resumable download: continues the .part file with an HTTP Range request. */
    private fun fetch(m: ModelSpec, part: File, active: () -> Boolean) {
        var have = if (part.exists()) part.length() else 0L
        val c = URL(m.url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            if (code == 416) return // already complete
            if (code != 200 && code != 206) fail("Server answered $code")
            val append = code == 206
            if (!append) have = 0L
            val total = if (c.contentLengthLong > 0) c.contentLengthLong + have else -1L
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
                        if (total > 0 && now - lastUi > 250) { lastUi = now; progress(m, done.toFloat() / total, "Downloading") }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun progress(m: ModelSpec, p: Float, phase: String) {
        _dl.update { it + (m.id to DownloadState(p, phase)) }
    }

    /** Keeps only the int8 models and tokens (the archives also hold large fp32 copies we never use). */
    private fun untar(tar: File, to: File, active: () -> Boolean) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(tar.inputStream(), 1 shl 16))).use { t ->
            while (true) {
                val e = t.nextEntry ?: break
                if (!active()) throw CancellationException("Cancelled")
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || !(name.endsWith(".int8.onnx") || name.endsWith("tokens.txt"))) continue
                File(to, name).outputStream().use { t.copyTo(it) }
            }
        }
    }

    private fun scan(): Set<String> = Models.all.filter { isInstalled(it) }.map { it.id }.toSet()

    private fun fail(msg: String): Nothing = throw IOException(msg)
}
