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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lecturecaption.app.data.db.AppDatabase
import com.lecturecaption.app.notes.ApiKeyStore
import com.lecturecaption.app.stt.CustomVocabulary
import com.lecturecaption.app.stt.EngineType
import com.lecturecaption.app.stt.ModelInfo
import com.lecturecaption.app.stt.ModelManager
import com.lecturecaption.app.stt.RefinerPrefs
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

    val downloadOne: suspend (ModelInfo) -> Unit = { info ->
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

    // A Whisper pack also needs the small speech-detector model; fetch it automatically first.
    val downloadModel: suspend (ModelInfo) -> Unit = { info ->
        if (info.isTarBz2) {
            modelManager.availableModels.firstOrNull { it.key == ModelManager.VAD_KEY }
                ?.takeIf { !modelManager.isDownloaded(it) }
                ?.let { downloadOne(it) }
        }
        downloadOne(info)
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
                    "Downloading a model never uploads anything from your phone.",
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
            Text("Custom words (new words)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Speech models only know a fixed vocabulary, so new words, names and technical terms " +
                    "come out as similar-sounding words. Add them here exactly as you want them " +
                    "written and they will be fixed in live captions and saved transcripts.",
                style = MaterialTheme.typography.bodySmall
            )
            var vocab by remember { mutableStateOf(CustomVocabulary.load(context)) }
            var newWord by remember { mutableStateOf("") }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newWord,
                    onValueChange = { newWord = it },
                    label = { Text("e.g. Kubernetes, Dr. Dhariyal") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    // Several words at once: separate with commas.
                    newWord.split(',').forEach { CustomVocabulary.add(context, it) }
                    vocab = CustomVocabulary.load(context)
                    newWord = ""
                }) { Text("Add") }
            }
            vocab.forEach { term ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(term, Modifier.weight(1f))
                    TextButton(onClick = {
                        CustomVocabulary.remove(context, term)
                        vocab = CustomVocabulary.load(context)
                    }) { Text("Remove") }
                }
            }

            Divider(Modifier.padding(vertical = 12.dp))
            Text("Accurate transcript (Whisper)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Vosk gives the instant live caption. Whisper, running in the background on your phone, " +
                    "listens to the same audio and — when you press Finish — replaces the saved transcript with a " +
                    "more accurate one. Unlike Vosk it can spell new words and names it has never seen, handles " +
                    "Hindi + English better and adds punctuation. Download one model below (Base is a good " +
                    "start; Small is most accurate but slower and may not keep up on a long lecture). If " +
                    "Whisper can't finish, the live transcript is kept — you never lose text.",
                style = MaterialTheme.typography.bodySmall
            )
            var refineEnabled by remember { mutableStateOf(RefinerPrefs.isEnabled(context)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Use Whisper to improve the saved transcript", Modifier.weight(1f))
                Switch(checked = refineEnabled, onCheckedChange = {
                    refineEnabled = it
                    RefinerPrefs.setEnabled(context, it)
                })
            }
            val activeWhisper = modelManager.bestWhisperModel()
            Text(
                if (activeWhisper != null) "Ready: ${activeWhisper.displayName.substringBefore(" -")} will be used."
                else "No Whisper model downloaded yet — pick one in the model list above.",
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
            Text("Online mode (most accurate)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Optional, OFF by default. The live caption stays on your phone. When you press Finish, the " +
                    "parts of the lecture where someone is speaking are sent to Google's Gemini API, which is " +
                    "much better with technical words, names, accents and Hindi + English, and replaces the " +
                    "saved transcript. Needs internet and your own free Gemini API key. If anything fails " +
                    "(no internet, bad key, limit reached) the offline transcript is kept — you never lose text.",
                style = MaterialTheme.typography.bodySmall
            )
            var onlineEnabled by remember { mutableStateOf(RefinerPrefs.isOnlineEnabled(context)) }
            var showOnlineWarning by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Use online mode for the saved transcript", Modifier.weight(1f))
                Switch(checked = onlineEnabled, onCheckedChange = { wantOn ->
                    if (wantOn) {
                        showOnlineWarning = true // never turned on without the warning
                    } else {
                        onlineEnabled = false
                        RefinerPrefs.setOnlineEnabled(context, false)
                    }
                })
            }
            if (currentGeminiKey == null) {
                var keyInput by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Gemini API key (from Google AI Studio)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = keyInput.isNotBlank(),
                    onClick = {
                        ApiKeyStore.setGeminiKey(context, keyInput)
                        currentGeminiKey = ApiKeyStore.getGeminiKey(context)
                        keyInput = ""
                    }
                ) { Text("Save key (stored encrypted)") }
            }
            if (onlineEnabled) {
                val vadReady = refreshKey >= 0 &&
                    modelManager.availableModels.firstOrNull { it.key == ModelManager.VAD_KEY }
                        ?.let { modelManager.isDownloaded(it) } == true
                val missing = buildList {
                    if (currentGeminiKey == null) add("save your Gemini API key above")
                    if (!vadReady) add("wait for the small speech-detector model to download")
                }
                Text(
                    if (missing.isEmpty()) "Ready: online mode will be used when the phone is online; otherwise offline mode is used."
                    else "To use it, " + missing.joinToString(" and ") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (showOnlineWarning) {
                AlertDialog(
                    onDismissRequest = { showOnlineWarning = false },
                    title = { Text("Send speech to Google?") },
                    text = {
                        Text(
                            "Online mode uploads the parts of your lecture where someone is speaking (as audio) " +
                                "to Google's Gemini API, using your own API key. Google's terms apply, and on the " +
                                "free tier Google may use the content to improve its products. If you turned on " +
                                "logging for your project in Google AI Studio, the audio can also be stored " +
                                "there. Don't use it for private or confidential lectures. You can turn it " +
                                "off at any time."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            RefinerPrefs.setOnlineEnabled(context, true)
                            onlineEnabled = true
                            showOnlineWarning = false
                            scope.launch {
                                modelManager.availableModels.firstOrNull { it.key == ModelManager.VAD_KEY }
                                    ?.takeIf { !modelManager.isDownloaded(it) }
                                    ?.let { downloadOne(it) }
                            }
                        }) { Text("I understand, turn on") }
                    },
                    dismissButton = { TextButton(onClick = { showOnlineWarning = false }) { Text("Cancel") } }
                )
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
