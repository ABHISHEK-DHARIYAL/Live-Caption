package com.clearcap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.data.db.SessionEntity
import com.clearcap.data.sourceLabel
import com.clearcap.transcript.LanguageMode
import com.clearcap.transcript.SessionStatus

@Composable
fun HistoryScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    var query by rememberSaveable { mutableStateOf("") }
    val list by remember(query) { ctx.app.repo.search(query) }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("History")
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search titles and text") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (list.isEmpty()) {
            Text(
                if (query.isBlank()) "No recordings yet. Start captions on the Home tab and they are saved here, on this phone." else "Nothing matches \"$query\".",
                color = t.muted, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { s -> SessionRow(s, onOpen) }
        }
    }
}

@Composable
private fun SessionRow(s: SessionEntity, onOpen: (String) -> Unit) {
    val t = LocalTokens.current
    val warn = s.status == SessionStatus.INTERRUPTED.name || s.status == SessionStatus.FAILED.name
    Panel(modifier = Modifier.clickable { onOpen(s.id) }, fill = if (warn) t.warnSoft else null) {
        Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                fmtDate(s.startedAt), fmtDur(s.durationMs), sourceLabel(s.source),
                LanguageMode.fromCode(s.languageMode).label.substringBefore(' ').substringBefore('(') + (s.detectedLanguage?.let { " ($it)" } ?: ""),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 2.dp),
        )
        val badges = listOfNotNull(
            when (s.status) { "INTERRUPTED" -> "Interrupted"; "FAILED" -> "Failed"; "RECORDING", "FINALIZING" -> "In progress"; else -> null },
            if (s.audioFile != null) "Audio kept" else null,
            if (s.refineModelId != null) "Refined" else null,
            if (s.editedText != null) "Edited" else null,
            if (s.imported) "Imported" else null,
        )
        if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = if (warn) t.danger else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    }
}
