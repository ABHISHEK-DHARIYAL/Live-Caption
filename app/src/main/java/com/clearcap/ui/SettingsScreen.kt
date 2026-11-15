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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.clearcap.app
import com.clearcap.session.AudioMode
import com.clearcap.session.ScreenContext
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
    // Re-check the Accessibility switch whenever the user comes back from Android's settings.
    var a11yOn by remember { mutableStateOf(ScreenContext.serviceEnabled(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) a11yOn = ScreenContext.serviceEnabled(ctx) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
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
                SwitchRow("Show captions over other apps", "Needs \"Display over other apps\". Drag the bar up or down to move it. Use – to shrink it to a round CC bubble you can drag anywhere (tap it to open the captions again), or ✕ to close it.", cfg.overlay) { v -> app.settings.update { it.copy(overlay = v) } }
                Text("Text size: ${sp.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
                Slider(value = sp, onValueChange = { sp = it }, valueRange = 12f..34f, onValueChangeFinished = { app.settings.update { it.copy(overlayTextSp = sp.toInt()) } })
                if (!Settings.canDrawOverlays(ctx)) TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Allow display over other apps") }
            }

            Panel {
                Text("Words on screen (better names and terms)", style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    "Use words on screen",
                    "While recording, reads visible text such as slide titles or code to spell names and technical terms correctly. Off by default. Words stay in memory for that recording only.",
                    cfg.screenContext,
                ) { v ->
                    if (v) dialog = "screenContext" else app.settings.update { it.copy(screenContext = false) }
                }
                if (cfg.screenContext) {
                    Text(
                        if (a11yOn) "Accessibility access: on. It only reads while a recording is running."
                        else "Accessibility access: off. Turn on \"Clearcap screen words\" in Android's Accessibility settings.",
                        style = MaterialTheme.typography.bodySmall, color = if (a11yOn) t.muted else t.danger, modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!a11yOn) {
                        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open Accessibility settings") }
                        Text(
                            "If Android says the setting is restricted (apps installed outside Google Play): open this app's App info, tap the three dots, choose \"Allow restricted settings\", then try again.",
                            style = MaterialTheme.typography.bodySmall, color = t.muted,
                        )
                        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open App info") }
                    }
                }
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
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        "screenContext" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Use words on screen?") },
            text = {
                Text(
                    "To do this, Clearcap needs Android's Accessibility access.\n\n" +
                        "What it reads: the visible text of the app you are using while a recording is running (for example slide titles, code or names).\n\n" +
                        "What it never reads: password fields, text boxes you type in, keyboards and system screens.\n\n" +
                        "What it keeps: a short list of unusual words, in memory, for that recording only. Screen text is never saved, logged or sent anywhere, and it cannot tap, type or change anything.\n\n" +
                        "You can turn this off here or in Android's Accessibility settings at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.settings.update { it.copy(screenContext = true) }
                    dialog = null
                    if (!ScreenContext.serviceEnabled(ctx)) runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Agree and continue") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Not now") } },
        )
        "deleteAudio" -> ConfirmDialog("Delete all saved audio?", "Transcripts stay. Recordings can no longer be checked again.", "Delete audio", danger = true,
            onConfirm = { scope.launch { val n = app.repo.deleteAllAudio(); Toast.makeText(ctx, "Deleted audio of $n recording(s)", Toast.LENGTH_SHORT).show() } }, onDismiss = { dialog = null })
        "deleteAll" -> ConfirmDialog("Delete everything?", "All transcripts and saved audio will be removed from this phone. Your vocabulary and settings are kept.", "Delete all", danger = true,
            onConfirm = { scope.launch { app.repo.deleteAllTranscripts() } }, onDismiss = { dialog = null })
    }
}
