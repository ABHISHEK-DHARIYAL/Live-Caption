package com.lecturecaption.app.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.FilterInputStream
import java.io.InputStream

data class ModelInfo(
    val key: String,
    val displayName: String,
    val downloadUrl: String,
    /** true if downloadUrl points at a .zip that must be extracted (Vosk); false for a
     *  single .bin/.gguf file (Whisper). */
    val isZip: Boolean,
    val approxSizeMb: Int,
    /** true for sherpa-onnx Whisper packs (.tar.bz2): only the int8 encoder/decoder + tokens are kept. */
    val isTarBz2: Boolean = false
)

/**
 * Handles fetching a model on explicit user action (Settings screen), storing it under the
 * app's private files directory, and deleting it again. Nothing here runs automatically or
 * without the user tapping "Download" — this app does not silently pull data over the network.
 */
class ModelManager(private val context: Context) {

    private val rootDir = File(context.filesDir, "models").apply { mkdirs() }

    val availableModels = listOf(
        ModelInfo(
            key = EngineType.VOSK.id,
            displayName = "Vosk small (English, ~40 MB)",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            isZip = true,
            approxSizeMb = 40
        ),
        ModelInfo(
            key = "VOSK_EN_LARGE",
            displayName = "Vosk English - more accurate (~128 MB)",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
            isZip = true,
            approxSizeMb = 128
        ),
        ModelInfo(
            key = EngineType.VOSK_EN_IN.id,
            displayName = "Vosk English - India accent (~36 MB)",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
            isZip = true,
            approxSizeMb = 36
        ),
        ModelInfo(
            key = EngineType.VOSK_HI.id,
            displayName = "Vosk Hindi (~42 MB)",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            isZip = true,
            approxSizeMb = 42
        ),
        // Whisper via sherpa-onnx (multilingual: English, Hindi, many more). Used as the "accurate"
        // pass that rewrites the saved transcript. Download size is the full archive; only ~50-250 MB
        // of it is kept on the phone.
        ModelInfo(
            key = EngineType.WHISPER_TINY.id,
            displayName = "Whisper Tiny - fastest (~116 MB download)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            isZip = false,
            approxSizeMb = 116,
            isTarBz2 = true
        ),
        ModelInfo(
            key = EngineType.WHISPER_BASE.id,
            displayName = "Whisper Base - balanced (~208 MB download)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
            isZip = false,
            approxSizeMb = 208,
            isTarBz2 = true
        ),
        ModelInfo(
            key = EngineType.WHISPER_SMALL.id,
            displayName = "Whisper Small - most accurate, slower (~640 MB download)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            isZip = false,
            approxSizeMb = 640,
            isTarBz2 = true
        ),
        // Voice-activity detector that cuts the audio into speech segments for Whisper.
        ModelInfo(
            key = VAD_KEY,
            displayName = "Speech detector for Whisper (~1 MB)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            isZip = false,
            approxSizeMb = 1
        )
    )

    companion object {
        const val VAD_KEY = "SILERO_VAD"
        const val WHISPER_ENCODER = "encoder.int8.onnx"
        const val WHISPER_DECODER = "decoder.int8.onnx"
        const val WHISPER_TOKENS = "tokens.txt"
    }

    fun dirFor(key: String): File = File(rootDir, key)
    fun fileFor(key: String): File = File(rootDir, "$key.bin")

    fun isDownloaded(info: ModelInfo): Boolean = when {
        info.isTarBz2 -> whisperFilesComplete(dirFor(info.key))
        info.isZip -> dirFor(info.key).let { it.exists() && (it.listFiles()?.isNotEmpty() == true) }
        else -> fileFor(info.key).exists()
    }

    fun delete(info: ModelInfo) {
        if (info.isZip || info.isTarBz2) dirFor(info.key).deleteRecursively() else fileFor(info.key).delete()
    }

    private fun whisperFilesComplete(dir: File): Boolean =
        File(dir, WHISPER_ENCODER).length() > 0 && File(dir, WHISPER_DECODER).length() > 0 &&
            File(dir, WHISPER_TOKENS).length() > 0

    /** Best downloaded Whisper pack (small > base > tiny), or null if none is installed. */
    fun bestWhisperModel(): ModelInfo? =
        listOf(EngineType.WHISPER_SMALL.id, EngineType.WHISPER_BASE.id, EngineType.WHISPER_TINY.id)
            .mapNotNull { k -> availableModels.firstOrNull { it.key == k } }
            .firstOrNull { isDownloaded(it) }

    fun vadModelFile(): File = fileFor(VAD_KEY)

    /**
     * Downloads and, for zip models, extracts. Reports 0..100 progress via [onProgress].
     * Runs on Dispatchers.IO; caller should launch this from a coroutine scope, not the main thread.
     */
    suspend fun download(info: ModelInfo, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val url = URL(info.downloadUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply { connect() }
        val totalBytes = connection.contentLength.coerceAtLeast(1)

        if (info.isTarBz2) {
            val targetDir = dirFor(info.key).apply { mkdirs() }
            val counting = CountingInputStream(connection.inputStream.buffered())
            // Wanted files are renamed to fixed names so the engine never depends on the model size.
            val wanted = mapOf(
                "encoder.int8.onnx" to WHISPER_ENCODER,
                "decoder.int8.onnx" to WHISPER_DECODER,
                "tokens.txt" to WHISPER_TOKENS
            )
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                var entry = tar.nextTarEntry
                val buffer = ByteArray(64 * 1024)
                while (entry != null) {
                    val base = entry.name.substringAfterLast('/')
                    val outName = wanted.entries.firstOrNull { base.endsWith(it.key) }?.value
                    if (!entry.isDirectory && outName != null) {
                        File(targetDir, outName).outputStream().use { out ->
                            var n: Int
                            while (tar.read(buffer).also { n = it } > 0) {
                                out.write(buffer, 0, n)
                                onProgress(((counting.count * 100) / totalBytes).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
            if (!whisperFilesComplete(targetDir)) {
                targetDir.deleteRecursively()
                throw java.io.IOException("Whisper model archive was incomplete. Try the download again.")
            }
            onProgress(100)
        } else if (info.isZip) {
            val targetDir = dirFor(info.key).apply { mkdirs() }
            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                var readSoFar = 0L
                val buffer = ByteArray(64 * 1024)
                while (entry != null) {
                    // Vosk zips contain a single top-level folder; strip it so files land
                    // directly under targetDir (what Model(path) expects).
                    val relativePath = entry.name.substringAfter('/', entry.name)
                    val outFile = File(targetDir, relativePath)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            var n: Int
                            while (zip.read(buffer).also { n = it } > 0) {
                                out.write(buffer, 0, n)
                                readSoFar += n
                                onProgress(((readSoFar * 100) / totalBytes).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } else {
            val outFile = fileFor(info.key)
            connection.inputStream.buffered().use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readSoFar = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } > 0) {
                        output.write(buffer, 0, n)
                        readSoFar += n
                        onProgress(((readSoFar * 100) / totalBytes).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        connection.disconnect()
    }
}

/** Counts compressed bytes read so download progress works for a streamed archive. */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
}
