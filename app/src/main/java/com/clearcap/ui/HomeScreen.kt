package com.clearcap.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.clearcap.app
import com.clearcap.capture.CaptureKind
import com.clearcap.models.DeviceInfoReader
import com.clearcap.pipeline.EngineRouter
import com.clearcap.pipeline.LiveMode
import com.clearcap.session.CaptureService
import com.clearcap.session.Phase
import com.clearcap.session.SessionBus
import com.clearcap.transcript.LanguageMode
import com.clearcap.transcript.SessionStatus

@Composable
fun HomeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val app = ctx.app
    val t = LocalTokens.current
    val st by SessionBus.state.collectAsState()
    val cfg by app.settings.state.collectAsState()
    val installed by app.models.installed.collectAsState()
    val sessions by remember { app.repo.sessions() }.collectAsState(initial = emptyList())
    val device = remember { DeviceInfoReader.read(ctx) }
    val route = remember(cfg.language, installed, cfg.preferredWhisperId) { EngineRouter.route(cfg.language, installed, cfg.preferredWhisperId, device) }
    val ready = route.mode != LiveMode.NONE
    val busy = st.busy

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) CaptureService.start(ctx, CaptureKind.SYSTEM, r.resultCode, data)
        else SessionBus.update { it.copy(error = "System-audio capture was not approved. Tap Start and accept the prompt.") }
    }
    fun launchProjection() {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection.launch(mpm.createScreenCaptureIntent())
    }
    fun go() { if (cfg.source == CaptureKind.MICROPHONE) CaptureService.start(ctx, CaptureKind.MICROPHONE) else launchProjection() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CaptureService.hasMic(ctx)) go()
        else SessionBus.update { it.copy(error = "Microphone permission was denied. Captions need it, and Android requires it for system audio too.") }
    }
    fun begin() {
        SessionBus.update { it.copy(error = null, status = null, savedId = null) }
        val need = buildList {
            if (!CaptureService.hasMic(ctx)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) perms.launch(need.toTypedArray()) else go()
    }

    val interrupted = sessions.firstOrNull { it.status == SessionStatus.INTERRUPTED.name }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Clearcap", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Offline live captions", style = MaterialTheme.typography.bodyMedium, color = t.muted)
                }
                if (st.running) Text("REC ${fmtClock(st.elapsedMs)}", color = t.danger, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { nav.goTab(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(cfg.source.let { if (it == CaptureKind.SYSTEM) "System" else "Mic" }, "audio source", Modifier.weight(1f))
                StatTile(cfg.language.label.substringBefore(' ').substringBefore('('), "language", Modifier.weight(1f))
                StatTile(if (ready) "Ready" else "Setup", "offline engine", Modifier.weight(1f))
            }
        }
        item {
            Panel {
                Text("Language", style = MaterialTheme.typography.labelLarge, color = t.muted)
                val langs = LanguageMode.values().toList()
                Segmented(listOf("Auto", "English", "Hindi", "Hinglish"), langs.indexOf(cfg.language), { i -> if (!busy) app.settings.update { it.copy(language = langs[i]) } },
                    modifier = Modifier.padding(top = 6.dp))
                Text(route.explanation, style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 8.dp))
                Text("Tip: captions get less accurate when the audio is played faster than about 1.5x.", style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate(Routes.SOURCE) }, enabled = !busy) { Text(if (cfg.source == CaptureKind.SYSTEM) "Source: system audio" else "Source: microphone") }
                    OutlinedButton(onClick = { nav.navigate(Routes.MODELS) }) { Text("Models") }
                }
            }
        }
        item {
            Panel {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordButton(recording = st.running, enabled = ready && (st.running || !busy)) {
                        if (st.running) CaptureService.send(ctx, CaptureService.ACTION_STOP) else begin()
                    }
                    AudioBars(st.level, st.phase == Phase.RECORDING, Modifier.padding(top = 14.dp))
                    Text(
                        when (st.phase) {
                            Phase.STARTING -> "Starting..."
                            Phase.RECORDING -> if (st.listening) "Listening. Speech heard, text coming..." else "Listening. Tap to stop and save."
                            Phase.PAUSED -> "Paused"
                            Phase.FINALIZING -> "Saving..."
                            Phase.REFINING -> "Checking the recording with a stronger model..."
                            Phase.IDLE -> if (ready) "Tap to start captions" else "Download a model to start"
                        },
                        color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp),
                    )
                    if (st.running) {
                        OutlinedButton(onClick = { CaptureService.send(ctx, if (st.phase == Phase.PAUSED) CaptureService.ACTION_RESUME else CaptureService.ACTION_PAUSE) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (st.phase == Phase.PAUSED) "Resume" else "Pause")
                        }
                    }
                }
            }
        }
        if (!ready && !busy) item {
            Panel {
                Text("Download what this language needs", style = MaterialTheme.typography.titleMedium)
                Text("Captions run on this phone with no internet. The one-time download starts only when you tap Download on the Models screen.",
                    color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                route.missing.forEach { Text("• ${it.label}: ${it.downloadMb} MB", style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { nav.navigate(Routes.MODELS) }, modifier = Modifier.padding(top = 8.dp)) { Text("Open Models") }
            }
        }
        st.error?.let { e ->
            item {
                Banner(e, true)
                if (e.contains("ermission", ignoreCase = true)) TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open app settings") }
            }
        }
        st.status?.let { s -> item { Banner(s, false) } }
        if (st.running && st.behindMs > 5_000) item {
            Banner("Captions are about ${st.behindMs / 1000} s behind the audio" + (if (st.droppedMs > 0) " and ${st.droppedMs / 1000} s of audio was skipped for live captions" else "") + ". Close other apps, or choose a lighter model in Models.", true)
        }
        if (interrupted != null && !busy) item {
            Panel(fill = t.warnSoft) {
                Text("A recording was interrupted", style = MaterialTheme.typography.titleMedium)
                Text(interrupted.statusNote ?: "Captions saved so far are kept.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
                OutlinedButton(onClick = { nav.navigate(Routes.detail(interrupted.id)) }) { Text("Open it") }
            }
        }
        st.progress?.let { p ->
            item {
                Panel {
                    Text("Second pass", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    Text(st.progressLabel, color = t.muted, style = MaterialTheme.typography.labelMedium)
                    if (st.phase == Phase.REFINING) TextButton(onClick = { CaptureService.send(ctx, CaptureService.ACTION_SKIP_REFINE) }) { Text("Skip and keep the live transcript") }
                }
            }
        }
        if (st.running || st.lines.isNotEmpty() || st.partial.isNotEmpty()) item {
            Panel {
                Text(if (route.mode == LiveMode.STREAMING) "Live captions" else "Captions (a few seconds behind)", style = MaterialTheme.typography.labelLarge, color = t.muted)
                val scroll = rememberScrollState()
                LaunchedEffect(st.lines.size, st.partial) { scroll.animateScrollTo(scroll.maxValue) }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(scroll).padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    val body = st.lines.joinToString(" ")
                    if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyLarge)
                    if (st.partial.isNotEmpty()) Text(st.partial, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    if (body.isEmpty() && st.partial.isEmpty()) Text(if (st.running) "Waiting for speech..." else "", color = t.muted)
                }
            }
        }
        if (!st.busy) st.savedId?.let { id ->
            item { OutlinedButton(onClick = { nav.navigate(Routes.detail(id)) }, modifier = Modifier.fillMaxWidth()) { Text("Open saved transcript") } }
        }
        item {
            TextButton(onClick = { nav.goTab(Routes.HISTORY) }, modifier = Modifier.fillMaxWidth()) { Text("Transcript history (${sessions.size})") }
        }
    }
}
