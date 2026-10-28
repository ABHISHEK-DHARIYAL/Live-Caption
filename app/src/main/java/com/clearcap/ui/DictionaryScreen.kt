package com.clearcap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.text.DictionaryImport

/** OpenWhispr-style dictionary: add one word, bulk import a list, edit, remove. */
@Composable
fun DictionaryScreen() {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val dict = ctx.app.dictionary
    val words by dict.words.collectAsState()
    var newWord by remember { mutableStateOf("") }
    var bulk by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editValue by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Dictionary", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Names and jargon you want spelled your way. After speech is recognized, close-sounding words are corrected to these spellings. Editing a transcript also adds the words you fix.",
                color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newWord, { newWord = it }, singleLine = true, label = { Text("Add a word") }, modifier = Modifier.weight(1f))
                Button(onClick = { dict.add(newWord); newWord = "" }, enabled = newWord.isNotBlank()) {
                    Icon(Icons.Filled.Add, null)
                }
            }
            Row {
                TextButton(onClick = { bulk = "" }) { Text("Bulk import") }
                if (words.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all", color = t.danger) }
            }
        }
        if (words.isEmpty()) item {
            Panel { Text("No words yet. Try the names of your lecturers, subjects or tools.", color = t.muted) }
        }
        items(words, key = { it }) { w ->
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(w, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { editing = w; editValue = w }) { Icon(Icons.Filled.Edit, "Edit $w", tint = t.muted) }
                    IconButton(onClick = { dict.remove(w) }) { Icon(Icons.Filled.Close, "Remove $w", tint = t.muted) }
                }
            }
        }
        item { Text("${words.size} words", color = t.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 16.dp)) }
    }

    bulk?.let { text ->
        val count = DictionaryImport.parse(text).size
        AlertDialog(
            onDismissRequest = { bulk = null },
            title = { Text("Bulk import") },
            text = {
                Column {
                    Text("Paste words separated by commas or new lines.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(text, { bulk = it }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("$count words found", style = MaterialTheme.typography.labelMedium, color = t.muted)
                }
            },
            confirmButton = { TextButton(onClick = { dict.add(text); bulk = null }, enabled = count > 0) { Text("Import") } },
            dismissButton = { TextButton(onClick = { bulk = null }) { Text("Cancel") } },
        )
    }
    editing?.let { old ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit word") },
            text = { OutlinedTextField(editValue, { editValue = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { dict.replace(old, editValue); editing = null }, enabled = editValue.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the dictionary?") },
        text = { Text("All ${words.size} words will be removed.") },
        confirmButton = { TextButton(onClick = { dict.clear(); confirmClear = false }) { Text("Clear", color = t.danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
