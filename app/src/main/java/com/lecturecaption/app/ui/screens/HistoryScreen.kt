package com.lecturecaption.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lecturecaption.app.data.repository.LectureRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenLecture: (Long) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { LectureRepository(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingId by remember { mutableStateOf<Long?>(null) }

    val lectures by remember(query) {
        if (query.isBlank()) repository.observeLectures() else repository.searchLectures(query)
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Lectures") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                items(lectures, key = { it.id }) { lecture ->
                    ListItem(
                        headlineContent = { Text(lecture.title) },
                        supportingContent = {
                            val dur = formatDuration(lecture.durationMs)
                            val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(lecture.createdAt)
                            Text("$dur  •  $date")
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    renamingId = lecture.id
                                    renameText = lecture.title
                                }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                                IconButton(onClick = { deletingId = lecture.id }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        },
                        modifier = Modifier.clickableRow { onOpenLecture(lecture.id) }
                    )
                    Divider()
                }
            }
        }
    }

    renamingId?.let { id ->
        AlertDialog(
            onDismissRequest = { renamingId = null },
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
                    if (newTitle.isNotBlank()) scope.launch { repository.rename(id, newTitle) }
                    renamingId = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingId = null }) { Text("Cancel") } }
        )
    }

    deletingId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Delete this lecture?") },
            text = { Text("This permanently deletes the transcript from this device. Any TXT/PDF files you already exported are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.delete(id) }
                    deletingId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } }
        )
    }
}

private fun formatDuration(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
}

// Small Modifier helper so ListItem rows are tappable without pulling in a separate Card per row.
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
