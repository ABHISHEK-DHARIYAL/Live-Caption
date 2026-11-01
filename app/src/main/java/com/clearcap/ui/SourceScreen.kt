package com.clearcap.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.capture.CaptureKind
import com.clearcap.session.CaptureService
import com.clearcap.session.SessionBus

@Composable
fun SourceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by ctx.app.settings.state.collectAsState()
    val running = SessionBus.state.collectAsState().value.busy
    var micOk by remember { mutableStateOf(CaptureService.hasMic(ctx)) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    val t = LocalTokens.current

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        ScreenHeader("Audio source", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose one source. They are never mixed, so there is no echo and no duplicate text.", color = t.muted, style = MaterialTheme.typography.bodyMedium)
            SourceCard(
                title = "System audio (playback capture)",
                selected = cfg.source == CaptureKind.SYSTEM, enabled = !running,
                onSelect = { ctx.app.settings.update { it.copy(source = CaptureKind.SYSTEM) } },
                body = "Captions audio that other apps play on this phone: videos, lectures, recorded meetings.",
                limits = listOf(
                    "Needs Android 10 or newer (this app requires it).",
                    "Android asks you to approve a screen-capture prompt every time you start. This app records audio only, not the screen.",
                    "Android requires the microphone permission for this mode as well, even though the microphone is not used.",
                    "An app can forbid capture of its audio. Protected or DRM video, many calls and some meeting apps stay silent to this app. The app will say \"No audio heard\" instead of showing made-up text.",
                    "The speaker volume does not change what is captured.",
                    "Fast playback hurts accuracy. On a small English test, errors rose from 0% at normal speed to about 4% at 1.5x and about 16% at 2x (live model). Use 1.5x or slower for important content.",
                ),
            )
            SourceCard(
                title = "Microphone",
                selected = cfg.source == CaptureKind.MICROPHONE, enabled = !running,
                onSelect = { ctx.app.settings.update { it.copy(source = CaptureKind.MICROPHONE) } },
                body = "Captions speech around you: a lecture room, a conversation, your own voice.",
                limits = listOf(
                    "Works on all supported phones; needs only the microphone permission.",
                    "Another app using the microphone (a call, the assistant) can make Android silence this app. It will tell you when that happens.",
                    "If speakers are playing the audio you want, use headphones for the other source to avoid echo.",
                ),
            )
            Panel {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (micOk) "Microphone: allowed" else "Microphone: not allowed yet", modifier = Modifier.weight(1f))
                    if (!micOk) TextButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
                }
                val overlay = Settings.canDrawOverlays(ctx)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (overlay) "Floating captions: allowed" else "Floating captions: needs \"Display over other apps\"", modifier = Modifier.weight(1f))
                    if (!overlay) TextButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open") }
                }
                Text("Screen-capture approval for system audio is requested when you tap Start.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, body: String, limits: List<String>) {
    val t = LocalTokens.current
    Panel(modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect), fill = if (selected) t.accentSoft else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Text("Limits", style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.padding(top = 8.dp))
        limits.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = t.muted) }
    }
}
