package com.lecturecaption.app.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class ModelInfo(
    val key: String,
    val displayName: String,
    val downloadUrl: String,
    /** true if downloadUrl points at a .zip that must be extracted (Vosk); false for a
     *  single .bin/.gguf file (Whisper). */
    val isZip: Boolean,
    val approxSizeMb: Int
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
        ModelInfo(
            key = EngineType.WHISPER_TINY.id,
            displayName = "Whisper Tiny (~75 MB)",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            isZip = false,
            approxSizeMb = 75
        ),
        ModelInfo(
            key = EngineType.WHISPER_BASE.id,
            displayName = "Whisper Base (~142 MB)",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            isZip = false,
            approxSizeMb = 142
        ),
        ModelInfo(
            key = EngineType.WHISPER_SMALL.id,
            displayName = "Whisper Small (~466 MB)",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            isZip = false,
            approxSizeMb = 466
        )
    )

    fun dirFor(key: String): File = File(rootDir, key)
    fun fileFor(key: String): File = File(rootDir, "$key.bin")

    fun isDownloaded(info: ModelInfo): Boolean =
        if (info.isZip) dirFor(info.key).let { it.exists() && (it.listFiles()?.isNotEmpty() == true) }
        else fileFor(info.key).exists()

    fun delete(info: ModelInfo) {
        if (info.isZip) dirFor(info.key).deleteRecursively() else fileFor(info.key).delete()
    }

    /**
     * Downloads and, for zip models, extracts. Reports 0..100 progress via [onProgress].
     * Runs on Dispatchers.IO; caller should launch this from a coroutine scope, not the main thread.
     */
    suspend fun download(info: ModelInfo, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val url = URL(info.downloadUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply { connect() }
        val totalBytes = connection.contentLength.coerceAtLeast(1)

        if (info.isZip) {
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
