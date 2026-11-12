package com.clearcap.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.capture.CaptureKind
import com.clearcap.data.sourceLabel
import com.clearcap.session.CaptureService
import com.clearcap.session.SessionBus
import com.clearcap.transcript.ExportFormat
import com.clearcap.transcript.TextVersion
import com.clearcap.transcript.TranscriptAssembler
import com.clearcap.transcript.TranscriptExporter
import com.clearcap.work.Retranscribe
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onModels: () -> Unit, onRecording: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.app
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val session by remember(id) { app.repo.session(id) }.collectAsState(initial = null)
    val segments by remember(id) { app.repo.segments(id) }.collectAsState(initial = emptyList())
    val live by SessionBus.state.collectAsState()

    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var exportFormat by remember { mutableStateOf<ExportFormat?>(null) }

    // ---- continue recording into this transcript ----
    val cfg by app.settings.state.collectAsState()
    val kindFor = { src: String? -> if (src == CaptureKind.SYSTEM.name) CaptureKind.SYSTEM else if (src == CaptureKind.MICROPHONE.name) CaptureKind.MICROPHONE else cfg.source }
    var pendingKind by remember { mutableStateOf(CaptureKind.MICROPHONE) }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) { CaptureService.start(ctx, CaptureKind.SYSTEM, r.resultCode, data, resumeId = id); onRecording() }
        else SessionBus.update { it.copy(error = "System-audio capture was not approved. Tap Add more recording and accept the prompt.") }
    }
    fun launchContinue() {
        SessionBus.update { it.copy(error = null, status = null, savedId = null) }
        if (pendingKind == CaptureKind.MICROPHONE) { CaptureService.start(ctx, CaptureKind.MICROPHONE, resumeId = id); onRecording() }
        else projection.launch((ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CaptureService.hasMic(ctx)) launchContinue()
        else SessionBus.update { it.copy(error = "Microphone permission was denied. Captions need it, and Android requires it for system audio too.") }
    }
    fun continueRecording(kind: CaptureKind) {
        pendingKind = kind
        val need = buildList {
            if (!CaptureService.hasMic(ctx)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) perms.launch(need.toTypedArray()) else launchContinue()
    }

    val s = session
    if (s == null) {
        Column { ScreenHeader("Transcript", onBack); Text("This recording is no longer available.", Modifier.padding(16.dp), color = t.muted) }
        return
    }
    val version = TextVersion.valueOf(s.activeVersion)
    val hasRefined = segments.any { it.refinedText != null }
    val hasEdit = s.editedText != null
    val shownText = TranscriptAssembler.text(segments, version, s.editedText)
    val audioKept = remember(s.audioFile) { s.audioFile != null && app.repo.audioFile(s) != null }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val fmt = exportFormat
        if (uri != null && fmt != null) scope.launch {
            val doc = app.repo.buildExport(id)
            if (doc != null) {
                val body = TranscriptExporter.export(doc, fmt)
                val ok = runCatching { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
                Toast.makeText(ctx, if (ok) "Exported" else "Could not write the file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(s.title, onBack)
        Text(
            listOfNotNull(fmtDate(s.startedAt), fmtDur(s.durationMs), sourceLabel(s.source), "${TranscriptWords.count(shownText)} words").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(horizontal = 16.dp),
        )
        s.statusNote?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Banner(it, s.status != "COMPLETE") } }
        s.notes?.let { Panel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Text("Notes", style = MaterialTheme.typography.labelLarge, color = t.muted); Text(it) } }

        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val versions = listOf(TextVersion.LIVE, TextVersion.REFINED, TextVersion.EDITED)
            Segmented(listOf("Original", "Refined", "My edit"), versions.indexOf(version), { i ->
                val v = versions[i]
                val allowed = v == TextVersion.LIVE || (v == TextVersion.REFINED && hasRefined) || (v == TextVersion.EDITED && hasEdit)
                if (allowed) scope.launch { app.repo.setVersion(id, v) }
                else Toast.makeText(ctx, if (v == TextVersion.REFINED) "No refined text for this recording" else "You have not edited this transcript yet", Toast.LENGTH_SHORT).show()
            })
        }
        if (version != TextVersion.LIVE) Text("The original recognizer text is kept. Tap Original to restore it.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(horizontal = 16.dp))

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (editing) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxSize(), label = { Text("Edit transcript") })
            } else if (version == TextVersion.EDITED && !showRaw) {
                SelectionContainer { LazyColumn { item { Text(s.editedText.orEmpty(), style = MaterialTheme.typography.bodyLarge) } } }
            } else {
                SelectionContainer {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(segments, key = { _, seg -> seg.index }) { _, seg ->
                            val text = if (showRaw) (seg.rawText ?: seg.liveText) else seg.textFor(version)
                            Column {
                                Text(fmtClock(seg.startMs), style = MaterialTheme.typography.labelSmall, color = t.muted)
                                Text(text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                if (segments.isEmpty()) Text("No captions were saved for this recording.", color = t.muted)
            }
        }

        if (editing) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val learned = app.repo.learnFromEdit(shownText, draft)
                        app.repo.saveEdit(id, draft)
                        if (learned.isNotEmpty()) Toast.makeText(ctx, "Added to vocabulary: ${learned.joinToString()}", Toast.LENGTH_LONG).show()
                    }
                    editing = false
                }) { Text("Save edit") }
                OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
            }
        } else {
            Button(
                onClick = { continueRecording(kindFor(s.source)) },
                enabled = !live.busy && s.status != "RECORDING" && s.status != "FINALIZING",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("+ Add more recording") }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { draft = shownText; editing = true }) { Text("Edit") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(shownText)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }) { Text("Copy") }
                TextButton(onClick = {
                    if (shownText.length > 150_000) Toast.makeText(ctx, "Too long to share as text. Use Export.", Toast.LENGTH_LONG).show()
                    else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shownText).putExtra(Intent.EXTRA_TITLE, s.title), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Share") }
                TextButton(onClick = { dialog = "export" }) { Text("Export") }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { dialog = "rename" }) { Text("Rename") }
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide raw" else "Raw text") }
                if (audioKept) TextButton(onClick = { Retranscribe.enqueue(ctx, id); Toast.makeText(ctx, "Checking the saved audio in the background", Toast.LENGTH_SHORT).show() }, enabled = !live.busy) { Text("Re-check audio") }
                TextButton(onClick = { dialog = "delete" }) { Text("Delete", color = t.danger) }
            }
            if (audioKept) TextButton(onClick = { dialog = "deleteAudio" }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Delete saved audio only") }
        }
        live.progress?.let { p -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()); Text(live.progressLabel, style = MaterialTheme.typography.labelMedium, color = t.muted) } }
        live.error?.takeIf { audioKept || s.status == "INTERRUPTED" }?.let { Box(Modifier.padding(16.dp)) { Banner(it, true) } }
    }

    when (dialog) {
        "rename" -> TextInputDialog("Rename", "Title", s.title, onConfirm = { a, _ -> scope.launch { app.repo.rename(id, a) } }, onDismiss = { dialog = null })
        "delete" -> ConfirmDialog("Delete this transcript?", "The transcript${if (audioKept) " and its saved audio" else ""} will be removed from this phone. This cannot be undone.", "Delete", danger = true,
            onConfirm = { scope.launch { app.repo.delete(id); onBack() } }, onDismiss = { dialog = null })
        "deleteAudio" -> ConfirmDialog("Delete the saved audio?", "The transcript stays. You will no longer be able to check the audio again.", "Delete audio", danger = true,
            onConfirm = { scope.launch { app.repo.deleteAudioOf(id) } }, onDismiss = { dialog = null })
        "export" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Export as") },
            text = {
                Column {
                    ExportFormat.values().forEach { f ->
                        TextButton(onClick = {
                            exportFormat = f; dialog = null
                            saver.launch(com.clearcap.transcript.TranscriptExporter.fileName(s.title, f))
                        }) { Text(f.label) }
                    }
                    if (version == TextVersion.EDITED) Text("Timestamped formats use the segment text; only plain text and Markdown include your full edit.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
    }
}

private object TranscriptWords { fun count(text: String) = com.clearcap.text.TranscriptText.wordCount(text) }
