package com.clearcap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.engine.Gemini
import com.clearcap.engine.GeminiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(onOpen: (String) -> Unit) {
    val t = LocalTokens.current
    val all by LocalContext.current.app.store.all.collectAsState()
    var q by remember { mutableStateOf("") }
    val shown = if (q.isBlank()) all else all.filter { it.title.contains(q, true) || it.text.contains(q, true) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
        OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search transcripts") }, modifier = Modifier.fillMaxWidth())
        if (shown.isEmpty()) Text(if (all.isEmpty()) "No recordings yet." else "Nothing matches.", color = t.muted, modifier = Modifier.padding(top = 16.dp))
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { r ->
                Panel(Modifier.clickable { onOpen(r.id) }) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val tags = listOfNotNull(if (r.edited != null) "edited" else if (r.accurate != null) "accurate" else "live", if (r.notes != null) "notes" else null)
                    Text("${fmtDate(r.createdAt)}  |  ${fmtDur(r.durationMs)}  |  ${r.words} words  |  ${tags.joinToString(", ")}",
                        style = MaterialTheme.typography.labelMedium, color = t.muted)
                    Text(r.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val all by ctx.app.store.all.collectAsState()
    val hasKey by ctx.app.keys.hasKey.collectAsState()
    val r = all.firstOrNull { it.id == id }
    var showLive by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var needKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (r == null) { LaunchedEffect(Unit) { onBack() }; return }
    val shown = if (showLive) r.live else r.text

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(r.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { share(ctx, r.title, shown) }) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
        Text("${fmtDate(r.createdAt)}  |  ${fmtDur(r.durationMs)}  |  ${r.words} words", style = MaterialTheme.typography.labelMedium, color = t.muted)
        if (r.behindMs > 5_000) Banner("The phone fell about ${r.behindMs / 1000} s behind during this recording; some audio may be missing.", false)
        if (r.accurate != null || r.edited != null) Segmented(listOf("Final", "Live version"), if (showLive) 1 else 0, { showLive = it == 1; editing = false })

        if (editing) {
            OutlinedTextField(draft, { draft = it }, minLines = 8, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val learned = ctx.app.dictionary.learnFrom(r.text, draft)
                    ctx.app.store.save(r.copy(edited = draft))
                    editing = false
                    if (learned.isNotEmpty()) Toast.makeText(ctx, "Added to dictionary: ${learned.joinToString(", ")}", Toast.LENGTH_LONG).show()
                }, enabled = draft.isNotBlank() && draft != r.text) { Text("Save") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        } else {
            Panel { Text(shown.ifBlank { "(empty)" }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(ctx, shown) }) { Text("Copy") }
                if (!showLive) OutlinedButton(onClick = { draft = r.text; editing = true }) { Text("Edit") }
            }
            if (!showLive) Text("Fix a misheard word and save: it is added to your dictionary for next time.", style = MaterialTheme.typography.labelSmall, color = t.muted)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val key = ctx.app.keys.get()
                if (key == null) { needKey = true; return@Button }
                busy = true; err = null
                scope.launch {
                    try {
                        val md = withContext(Dispatchers.IO) { Gemini.notes(r.text, key, ctx.app.settings.value.geminiModel) }
                        ctx.app.store.save(r.copy(notes = md))
                    } catch (e: GeminiException) {
                        err = e.message
                    } finally { busy = false }
                }
            }, enabled = !busy) { Text(if (r.notes == null) "Make AI notes" else "Remake notes") }
            if (busy) CircularProgressIndicator(Modifier.padding(2.dp))
        }
        Text("AI notes send this transcript's text to Google Gemini with your own key, only when you tap the button.", style = MaterialTheme.typography.labelSmall, color = t.muted)
        err?.let { Text(it, color = t.danger) }
        r.notes?.let { n ->
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(ctx, n) }) { Text("Copy notes") }
                    OutlinedButton(onClick = { share(ctx, "Notes - ${r.title}", n) }) { Text("Share notes") }
                }
                Text(n, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this recording?") },
        text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; ctx.app.store.delete(r.id) }) { Text("Delete", color = t.danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (needKey) AlertDialog(
        onDismissRequest = { needKey = false },
        title = { Text("Gemini key needed") },
        text = { Text(if (hasKey) "The saved key could not be read. Add it again in Settings." else "Add your own Gemini API key in Settings. It is stored encrypted on this phone.") },
        confirmButton = { TextButton(onClick = { needKey = false; onSettings() }) { Text("Open Settings") } },
        dismissButton = { TextButton(onClick = { needKey = false }) { Text("Cancel") } },
    )
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcript", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, title: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Share"))
}
