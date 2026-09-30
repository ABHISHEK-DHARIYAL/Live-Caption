package com.lecturecaption.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lecturecaption.app.data.db.AppDatabase
import com.lecturecaption.app.notes.ApiKeyStore
import com.lecturecaption.app.stt.EngineType
import com.lecturecaption.app.stt.ModelInfo
import com.lecturecaption.app.stt.ModelManager
import kotlinx.coroutines.launch

private const val DEVELOPER_NAME = "Abhishek.DL"
private const val DEVELOPER_GITHUB_URL = "https://github.com/ABHISHEK-DHARIYAL"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPrivacy: () -> Unit) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val scope = rememberCoroutineScope()

    var downloadProgress by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var refreshKey by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val downloadModel: suspend (ModelInfo) -> Unit = { info ->
        downloadProgress = downloadProgress + (info.key to 0)
        try {
            modelManager.download(info) { pct ->
                downloadProgress = downloadProgress + (info.key to pct)
            }
        } catch (e: Exception) {
            modelManager.delete(info) // never keep a half-downloaded model
            downloadError = "Download failed (${e.message ?: "network error"}). Check your internet and try again."
        } finally {
            downloadProgress = downloadProgress - info.key
            refreshKey++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Speech Models", style = MaterialTheme.typography.titleMedium)
            Text(
                "Models download only when you tap Download below, over your own network connection. " +
                    "No model or transcript is ever uploaded.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    downloadError = null
                    scope.launch {
                        modelManager.availableModels
                            .filter { it.key == EngineType.VOSK_EN_IN.id || it.key == EngineType.VOSK_HI.id }
                            .filter { !modelManager.isDownloaded(it) }
                            .forEach { downloadModel(it) }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Download Hindi + English pack (~80 MB)") }
            downloadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Words recognized wrong? Downloading \"Vosk English - more accurate\" below improves " +
                    "English accuracy somewhat and is used automatically once downloaded — no need to " +
                    "pick it anywhere. It's a real but modest improvement, not a different technology; " +
                    "Hindi doesn't currently have a larger offline model available from Vosk.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))

            modelManager.availableModels.forEach { info ->
                key(info.key, refreshKey) {
                    ModelRow(
                        info = info,
                        isDownloaded = modelManager.isDownloaded(info),
                        progress = downloadProgress[info.key],
                        onDownload = { scope.launch { downloadModel(info) } },
                        onDelete = {
                            modelManager.delete(info)
                            refreshKey++
                        }
                    )
                }
            }

            Divider(Modifier.padding(vertical = 12.dp))
            Text(
                "Whisper models download correctly here, but Whisper inference itself needs a " +
                    "native whisper.cpp module that isn't built into this project yet — see " +
                    "WhisperEngine.kt for exact steps. Vosk works end-to-end today.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )

            Divider(Modifier.padding(vertical = 12.dp))
            Text("AI Notes (Gemini)", fontWeight = FontWeight.Bold)
            Text(
                "Optional. Only used when you tap \"Generate Notes\" on a transcript — sends that " +
                    "transcript's text to Google's Gemini API using your own free API key. Nothing " +
                    "is sent unless you do this.",
                style = MaterialTheme.typography.bodySmall
            )
            var currentGeminiKey by remember { mutableStateOf(ApiKeyStore.getGeminiKey(context)) }
            if (currentGeminiKey != null) {
                Text("A Gemini API key is saved (encrypted) on this device.")
                OutlinedButton(onClick = {
                    ApiKeyStore.clearGeminiKey(context)
                    currentGeminiKey = null
                }) { Text("Remove saved API key") }
            } else {
                Text("No API key saved. You'll be asked for one the first time you tap Generate Notes.")
            }

            Divider(Modifier.padding(vertical = 12.dp))
            TextButton(onClick = onOpenPrivacy) { Text("Privacy") }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete All Data") }

            Divider(Modifier.padding(vertical = 12.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Developed by $DEVELOPER_NAME", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_GITHUB_URL))
                    )
                },
                contentPadding = PaddingValues(0.dp)
            ) { Text(DEVELOPER_GITHUB_URL) }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete all data?") },
            text = { Text("This permanently deletes every lecture, transcript, and downloaded model on this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        AppDatabase.getInstance(context).clearAllTables()
                        modelManager.availableModels.forEach { modelManager.delete(it) }
                        showDeleteConfirm = false
                        refreshKey++
                    }
                }) { Text("Delete Everything") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ModelRow(
    info: ModelInfo,
    isDownloaded: Boolean,
    progress: Int?,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(info.displayName) },
        supportingContent = {
            when {
                progress != null -> LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
                isDownloaded -> Text("Downloaded")
                else -> Text("~${info.approxSizeMb} MB")
            }
        },
        trailingContent = {
            if (progress == null) {
                if (isDownloaded) {
                    TextButton(onClick = onDelete) { Text("Delete") }
                } else {
                    TextButton(onClick = onDownload) { Text("Download") }
                }
            }
        }
    )
}
