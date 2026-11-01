package com.clearcap.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.clearcap.app
import com.clearcap.session.AudioMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val app = ctx.app
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val cfg by app.settings.state.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var sp by remember(cfg.overlayTextSp) { mutableFloatStateOf(cfg.overlayTextSp.toFloat()) }
    val audioBytes by produceState(0L, dialog, cfg.audioMode) {
        value = withContext(Dispatchers.IO) { app.repo.audioDir.listFiles()?.sumOf { it.length() } ?: 0L }
    }
    val modes = AudioMode.values().toList()
    val retention = listOf(0, 30, 90, 365)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Settings")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel {
                Text("Recognition and models", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { nav.navigate(Routes.MODELS) }) { Text("Models, languages and offline readiness") }
                TextButton(onClick = { nav.navigate(Routes.SOURCE) }) { Text("Audio source and permissions") }
                SwitchRow("Automatic volume boost", "Gently lifts quiet speech, never silence or noise (up to 4x).", cfg.autoGain) { v -> app.settings.update { it.copy(autoGain = v) } }
            }

            Panel {
                Text("Floating captions", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Show captions over other apps", "Needs \"Display over other apps\". Drag the bar up or down to move it.", cfg.overlay) { v -> app.settings.update { it.copy(overlay = v) } }
                Text("Text size: ${sp.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
                Slider(value = sp, onValueChange = { sp = it }, valueRange = 12f..34f, onValueChangeFinished = { app.settings.update { it.copy(overlayTextSp = sp.toInt()) } })
                if (!Settings.canDrawOverlays(ctx)) TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Allow display over other apps") }
            }

            Panel {
                Text("Audio storage (privacy)", style = MaterialTheme.typography.titleMedium)
                Segmented(listOf("Off", "Temporary", "Keep"), modes.indexOf(cfg.audioMode), { i -> app.settings.update { it.copy(audioMode = modes[i]) } }, Modifier.padding(top = 8.dp))
                Text(
                    when (cfg.audioMode) {
                        AudioMode.OFF -> "Audio is never written to storage. If Android stops the app mid-recording, only captions already saved are kept."
                        AudioMode.TEMPORARY -> "Audio is saved privately while recording and deleted automatically once the transcript is saved. This lets an interrupted recording be recovered and checked again."
                        AudioMode.KEEP -> "Audio is kept until you delete it, so you can check a recording again later. Uses about 115 MB per hour."
                    },
                    style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 8.dp),
                )
                Text("Audio is stored in this app's private folder, excluded from Android backups, and never uploaded. Stored now: ${audioBytes / 1_000_000} MB.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { dialog = "deleteAudio" }, enabled = audioBytes > 0) { Text("Delete all saved audio", color = t.danger) }
            }

            Panel {
                Text("Transcript retention", style = MaterialTheme.typography.titleMedium)
                Text("Delete transcripts older than:", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 4.dp))
                Segmented(listOf("Never", "30 days", "90 days", "1 year"), retention.indexOf(cfg.transcriptRetentionDays).coerceAtLeast(0), { i -> app.settings.update { it.copy(transcriptRetentionDays = retention[i]) } }, Modifier.padding(top = 6.dp))
                TextButton(onClick = { dialog = "deleteAll" }) { Text("Delete all transcripts and audio", color = t.danger) }
            }

            Panel {
                Text("If recordings stop by themselves", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Some phone makers (Xiaomi, Oppo, Vivo, Huawei, OnePlus, and Samsung's \"deep sleeping apps\") stop background apps aggressively. " +
                        "Set Clearcap to \"No restrictions\" or \"Unrestricted\" battery use, and allow auto-start if your phone has that option. Settings differ per brand.",
                    style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }) { Text("Battery settings") }
            }

            Panel {
                TextButton(onClick = { nav.navigate(Routes.DIAGNOSTICS) }) { Text("Diagnostics") }
                TextButton(onClick = { nav.navigate(Routes.PRIVACY) }) { Text("Privacy") }
                TextButton(onClick = { nav.navigate(Routes.ABOUT) }) { Text("About and licenses") }
            }
        }
    }

    when (dialog) {
        "deleteAudio" -> ConfirmDialog("Delete all saved audio?", "Transcripts stay. Recordings can no longer be checked again.", "Delete audio", danger = true,
            onConfirm = { scope.launch { val n = app.repo.deleteAllAudio(); Toast.makeText(ctx, "Deleted audio of $n recording(s)", Toast.LENGTH_SHORT).show() } }, onDismiss = { dialog = null })
        "deleteAll" -> ConfirmDialog("Delete everything?", "All transcripts and saved audio will be removed from this phone. Your vocabulary and settings are kept.", "Delete all", danger = true,
            onConfirm = { scope.launch { app.repo.deleteAllTranscripts() } }, onDismiss = { dialog = null })
    }
}
