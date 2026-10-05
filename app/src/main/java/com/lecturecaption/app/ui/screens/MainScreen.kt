package com.lecturecaption.app.ui.screens

import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lecturecaption.app.audio.AudioSource
import com.lecturecaption.app.service.TranscriptionState
import com.lecturecaption.app.stt.EngineType
import com.lecturecaption.app.viewmodel.MainViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestMicPermission: (onResult: (Boolean) -> Unit) -> Unit,
    confirmStopRequested: Boolean = false,
    onConfirmStopHandled: () -> Unit = {},
    onOpenLecture: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val segmentCount by viewModel.segmentCount.collectAsState()
    val liveCaption by viewModel.liveCaption.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val recentLectures by viewModel.recentLectures.collectAsState()
    val currentTitle by viewModel.currentTitle.collectAsState()
    val savedMessage by viewModel.savedMessage.collectAsState()
    val backlogWarning by viewModel.backlogWarning.collectAsState()

    var showFinishDialog by remember { mutableStateOf(false) }
    var showOverlayDialog by remember { mutableStateOf(false) }
    var lectureName by remember { mutableStateOf("") }

    // Opened from the corner icon / notification: ask "Is it done?" (only if recording).
    LaunchedEffect(confirmStopRequested) {
        if (confirmStopRequested) {
            if (state == TranscriptionState.TRANSCRIBING) {
                lectureName = currentTitle
                showFinishDialog = true
            }
            onConfirmStopHandled()
        }
    }

    // Shared start logic: mic permission is needed for BOTH modes (Android requires it for
    // system-audio playback capture too), then begin.
    val beginStart: () -> Unit = {
        viewModel.dismissSavedMessage()
        onRequestMicPermission { granted ->
            if (granted) viewModel.startTranscription()
            else com.lecturecaption.app.service.TranscriptionForegroundService.reportError(
                "Microphone permission is required (Android needs it even for system-audio capture). " +
                    "Allow it and tap START again."
            )
        }
    }

    var audioSource by remember { mutableStateOf(AudioSource.SYSTEM_AUDIO) }
    var engine by remember { mutableStateOf(EngineType.BILINGUAL) }
    var speed by remember { mutableStateOf(1f) }
    val language = if (engine == EngineType.BILINGUAL) "Hindi + English (auto-detect)" else "English"

    Scaffold(
        topBar = { TopAppBar(title = { Text("LectureCaption") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            StatusRow(state)
            Spacer(Modifier.height(16.dp))

            errorMessage?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            savedMessage?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text("✓ $it", modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(12.dp))
            }

            if (state == TranscriptionState.TRANSCRIBING) {
                backlogWarning?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text("⚠ $it", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Duration: ${formatDuration(durationMs)}")
                        Text("Segments: $segmentCount")
                        Spacer(Modifier.height(8.dp))
                        Text("Live: $liveCaption", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        lectureName = currentTitle
                        showFinishDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("FINISH & SAVE") }
            } else {
                run {
                Text("Audio Source", fontWeight = FontWeight.Bold)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = audioSource == AudioSource.SYSTEM_AUDIO,
                            onClick = {
                                audioSource = AudioSource.SYSTEM_AUDIO
                                viewModel.setAudioSource(AudioSource.SYSTEM_AUDIO)
                            }
                        )
                        Column {
                            Text("System Audio")
                            Text(
                                "Only audio playing on this device (video, lecture app). Your microphone is not used.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = audioSource == AudioSource.SYSTEM_AND_MICROPHONE,
                            onClick = {
                                audioSource = AudioSource.SYSTEM_AND_MICROPHONE
                                viewModel.setAudioSource(AudioSource.SYSTEM_AND_MICROPHONE)
                            }
                        )
                        Column {
                            Text("Microphone")
                            Text(
                                "Device audio AND your voice together, mixed into one transcript. Needs microphone permission.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (audioSource == AudioSource.SYSTEM_AUDIO || audioSource == AudioSource.SYSTEM_AND_MICROPHONE) {
                    Text(
                        "Some apps prevent system-audio capture (e.g. DRM-protected players). " +
                            "If capture fails, try another app. " +
                            "Android also requires microphone permission for System Audio mode, even though " +
                            "no microphone audio is used — this is a platform requirement, not this app's choice.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (audioSource == AudioSource.SYSTEM_AND_MICROPHONE) {
                    Text(
                        "Capturing both at once is not guaranteed on every phone. If your device's audio " +
                            "hardware doesn't support it, you'll see a clear message instead of a silent fallback.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("Language: $language", fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(16.dp))
                Text("Transcription Engine", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = engine == EngineType.BILINGUAL,
                        onClick = { engine = EngineType.BILINGUAL; viewModel.setEngine(engine) }
                    )
                    Text("Hindi + English (auto-detect)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = engine == EngineType.VOSK,
                        onClick = { engine = EngineType.VOSK; viewModel.setEngine(engine) }
                    )
                    Text("English only (Vosk)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = engine == EngineType.WHISPER_TINY,
                        onClick = { engine = EngineType.WHISPER_TINY; viewModel.setEngine(engine) }
                    )
                    Text("Whisper (not available yet)")
                }

                Spacer(Modifier.height(16.dp))
                Text("Video speed", fontWeight = FontWeight.Bold)
                Text(
                    "Set this to the speed you are watching at, so fast speech is slowed down before it is written.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f to "1x", 1.25f to "1.25x", 1.5f to "1.5x", 2f to "2x").forEach { (value, label) ->
                        FilterChip(
                            selected = speed == value,
                            onClick = { speed = value; viewModel.setSpeed(value) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        viewModel.setAudioSource(audioSource)
                        if (!Settings.canDrawOverlays(context)) {
                            showOverlayDialog = true // explain first, then continue either way
                        } else {
                            beginStart()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("START TRANSCRIPTION") }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onOpenHistory) { Text("My Lectures") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }

            Spacer(Modifier.height(12.dp))
            Text("Previous Lectures", fontWeight = FontWeight.Bold)
            LazyColumn {
                items(recentLectures.take(5)) { lecture ->
                    ListItem(
                        headlineContent = { Text(lecture.title) },
                        supportingContent = { Text(formatDuration(lecture.durationMs)) }
                    )
                }
            }
        }
    }

    if (showOverlayDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            title = { Text("Show the corner icon?") },
            text = {
                Text(
                    "To show the small “recording” icon and live captions on top of your video, " +
                        "allow “Display over other apps”. You can also skip — recording still works, " +
                        "you just won't see the floating icon."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showOverlayDialog = false
                    onRequestOverlayPermission()
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverlayDialog = false
                    beginStart()
                }) { Text("Skip, start anyway") }
            }
        )
    }

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text("Is it done?") },
            text = {
                Column {
                    Text("Give this transcript a name. It will be saved in My Lectures and as a .txt file.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = lectureName,
                        onValueChange = { lectureName = it },
                        label = { Text("File name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFinishDialog = false
                    viewModel.finishAndSave(lectureName)
                }) { Text("Yes, save it") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) { Text("Keep recording") }
            }
        )
    }
}

@Composable
private fun StatusRow(state: TranscriptionState) {
    val (label, color) = when (state) {
        TranscriptionState.IDLE -> "Ready" to MaterialTheme.colorScheme.onSurface
        TranscriptionState.AWAITING_PERMISSION -> "Waiting for permission…" to MaterialTheme.colorScheme.tertiary
        TranscriptionState.TRANSCRIBING -> "● TRANSCRIBING" to MaterialTheme.colorScheme.primary
        TranscriptionState.STOPPED -> "Lecture saved" to MaterialTheme.colorScheme.onSurface
        TranscriptionState.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.titleMedium, color = color)
}

private fun formatDuration(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
}
