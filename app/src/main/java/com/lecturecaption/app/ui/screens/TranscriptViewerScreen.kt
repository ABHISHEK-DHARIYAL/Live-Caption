package com.lecturecaption.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lecturecaption.app.data.db.entity.Lecture
import com.lecturecaption.app.data.db.entity.TranscriptSegment
import com.lecturecaption.app.data.repository.LectureRepository
import com.lecturecaption.app.export.TranscriptExporter
import com.lecturecaption.app.notes.ApiKeyStore
import com.lecturecaption.app.notes.GeminiApiException
import com.lecturecaption.app.notes.GeminiNotesGenerator
import com.lecturecaption.app.notes.LectureNotes
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptViewerScreen(lectureId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { LectureRepository(context) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val lecture by repository.observeLecture(lectureId).collectAsState(initial = null)
    val segments by repository.observeSegments(lectureId).collectAsState(initial = emptyList())
    var notes by remember { mutableStateOf<LectureNotes?>(null) }
    var notesError by remember { mutableStateOf<String?>(null) }
    var notesLoading by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    fun runGenerateNotes() {
        val key = ApiKeyStore.getGeminiKey(context)
        if (key == null) {
            apiKeyInput = ""
            showApiKeyDialog = true
            return
        }
        notesError = null
        notesLoading = true
        scope.launch {
            try {
                val fullText = segments.joinToString(" ") { it.text }
                notes = GeminiNotesGenerator(key).generate(fullText, lecture?.title.orEmpty())
            } catch (e: GeminiApiException) {
                notesError = e.message
            } catch (e: Exception) {
                notesError = "Could not reach Gemini. Check your internet connection and try again."
            } finally {
                notesLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lecture?.title ?: "Transcript") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        renameText = lecture?.title.orEmpty()
                        showRenameDialog = true
                    }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(segments.joinToString("\n\n") { it.text }))
                }) { Text("Copy All") }

                TextButton(onClick = {
                    lecture?.let { l -> shareExport(context, l, segments, "txt") }
                }) { Text("Export TXT") }

                TextButton(onClick = {
                    lecture?.let { l -> shareExport(context, l, segments, "md") }
                }) { Text("Export Markdown") }

                TextButton(onClick = {
                    lecture?.let { l -> shareExport(context, l, segments, "pdf") }
                }) { Text("Export PDF") }
            }

            if (showRenameDialog) {
                AlertDialog(
                    onDismissRequest = { showRenameDialog = false },
                    title = { Text("Rename lecture") },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val newTitle = renameText.trim()
                            if (newTitle.isNotBlank()) {
                                scope.launch { repository.rename(lectureId, newTitle) }
                            }
                            showRenameDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { runGenerateNotes() }, enabled = !notesLoading) {
                    Text(if (notesLoading) "Generating…" else "Generate Notes (Gemini)")
                }
                if (ApiKeyStore.getGeminiKey(context) != null) {
                    TextButton(onClick = {
                        ApiKeyStore.clearGeminiKey(context)
                        notes = null
                    }) { Text("Forget API key") }
                }
            }
            Text(
                "Sends this transcript's text to Google's Gemini API to generate notes. " +
                    "Nothing else on this device is sent.",
                style = MaterialTheme.typography.bodySmall
            )

            notesError?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }

            notes?.let { n ->
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        if (n.quickRevision.isNotBlank()) {
                            Text("Quick revision", fontWeight = FontWeight.Bold)
                            Text(n.quickRevision)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (n.keyConcepts.isNotEmpty()) {
                            Text("Key concepts", fontWeight = FontWeight.Bold)
                            n.keyConcepts.forEach { Text("• $it") }
                            Spacer(Modifier.height(8.dp))
                        }
                        if (n.definitions.isNotEmpty()) {
                            Text("Definitions", fontWeight = FontWeight.Bold)
                            n.definitions.forEach { (term, def) -> Text("$term — $def") }
                            Spacer(Modifier.height(8.dp))
                        }
                        if (n.importantPoints.isNotEmpty()) {
                            Text("Important points", fontWeight = FontWeight.Bold)
                            n.importantPoints.forEach { Text("• $it") }
                            Spacer(Modifier.height(8.dp))
                        }
                        if (n.examQuestions.isNotEmpty()) {
                            Text("Possible exam questions", fontWeight = FontWeight.Bold)
                            n.examQuestions.forEach { Text("• $it") }
                        }
                    }
                }
            }

            if (showApiKeyDialog) {
                AlertDialog(
                    onDismissRequest = { showApiKeyDialog = false },
                    title = { Text("Gemini API key needed") },
                    text = {
                        Column {
                            Text(
                                "Notes are generated by Google's Gemini API, using your own free API key. " +
                                    "Get one at aistudio.google.com/apikey (Google's free tier), then paste it here. " +
                                    "It's stored encrypted on this device only."
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("API key") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (apiKeyInput.isNotBlank()) {
                                ApiKeyStore.setGeminiKey(context, apiKeyInput)
                                showApiKeyDialog = false
                                runGenerateNotes()
                            }
                        }) { Text("Save & generate") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showApiKeyDialog = false }) { Text("Cancel") }
                    }
                )
            }

            Divider(Modifier.padding(vertical = 8.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(segments, key = { it.id }) { seg ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            formatTimestamp(seg.startTimeMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

private fun shareExport(context: android.content.Context, lecture: Lecture, segments: List<TranscriptSegment>, format: String) {
    val uri: android.net.Uri
    val mimeType: String
    when (format) {
        "pdf" -> {
            uri = TranscriptExporter.writePdfAndGetShareUri(context, lecture, segments)
            mimeType = "application/pdf"
        }
        "md" -> {
            uri = TranscriptExporter.writeAndGetShareUri(context, lecture, TranscriptExporter.toMarkdown(lecture, segments), "md")
            mimeType = "text/markdown"
        }
        else -> {
            uri = TranscriptExporter.writeAndGetShareUri(context, lecture, TranscriptExporter.toTxt(lecture, segments), "txt")
            mimeType = "text/plain"
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export transcript"))
}

private fun formatTimestamp(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
