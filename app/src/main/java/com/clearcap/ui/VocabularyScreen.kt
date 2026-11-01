package com.clearcap.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.data.db.VocabularyEntity
import com.clearcap.text.VocabEntry
import com.clearcap.text.VocabularyImport
import kotlinx.coroutines.launch

private fun splitAliases(s: String) = s.split(',', ';', '|').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
fun VocabularyScreen() {
    val ctx = LocalContext.current
    val app = ctx.app
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val entries by remember { app.repo.vocabulary() }.collectAsState(initial = emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<VocabularyEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Vocabulary")
        Text(
            "Names, technical terms and abbreviations. A term is the spelling you want. \"Also written as\" lists spellings the recognizer tends to produce " +
                "(for example in Devanagari or Roman letters); those are replaced exactly, whole words only. Terms in Roman letters are also matched by sound. " +
                "Corrections are applied after recognition and the raw recognizer text is always kept.",
            style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editing = null; dialog = "add" }) { Text("Add") }
            TextButton(onClick = { dialog = "import" }) { Text("Import list") }
            if (entries.isNotEmpty()) TextButton(onClick = { dialog = "clear" }) { Text("Clear all", color = t.danger) }
        }
        if (entries.isEmpty()) Text("No terms yet. Words you correct while editing a transcript are added automatically.", color = t.muted, modifier = Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { e ->
                Panel {
                    Text(e.term, style = MaterialTheme.typography.titleMedium)
                    val aliases = e.spokenAs.lines().filter { it.isNotBlank() }
                    if (aliases.isNotEmpty()) Text("Also written as: ${aliases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    Row {
                        TextButton(onClick = { editing = e; dialog = "edit" }) { Text("Edit") }
                        TextButton(onClick = { scope.launch { app.repo.deleteVocabulary(e.id) } }) { Text("Remove", color = t.danger) }
                    }
                }
            }
        }
    }

    when (dialog) {
        "add" -> TextInputDialog("Add term", "Preferred spelling", secondLabel = "Also written as (optional, comma separated)", onConfirm = { a, b ->
            scope.launch { val n = app.repo.addVocabulary(listOf(VocabEntry(a.trim(), splitAliases(b)))); if (n == 0) Toast.makeText(ctx, "Already in the list", Toast.LENGTH_SHORT).show() }
        }, onDismiss = { dialog = null })
        "edit" -> editing?.let { e -> TextInputDialog("Edit term", "Preferred spelling", e.term, secondLabel = "Also written as (comma separated)", secondInitial = e.spokenAs.lines().filter { it.isNotBlank() }.joinToString(", "), onConfirm = { a, b ->
            scope.launch { app.repo.updateVocabulary(e.id, VocabEntry(a.trim(), splitAliases(b))) }
        }, onDismiss = { dialog = null }) }
        "import" -> TextInputDialog("Import list", "One per line. Use  alias, alias => Term  for spellings", confirm = "Import", singleLine = false, onConfirm = { a, _ ->
            scope.launch { val n = app.repo.addVocabulary(VocabularyImport.parse(a)); Toast.makeText(ctx, "$n added", Toast.LENGTH_SHORT).show() }
        }, onDismiss = { dialog = null })
        "clear" -> ConfirmDialog("Remove all terms?", "This clears your whole vocabulary. Saved transcripts are not changed.", "Remove all", danger = true, onConfirm = { scope.launch { app.repo.clearVocabulary() } }, onDismiss = { dialog = null })
    }
}
