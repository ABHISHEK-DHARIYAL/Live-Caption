package com.clearcap.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.clearcap.app
import com.clearcap.data.LiveMode
import com.clearcap.data.Source
import com.clearcap.engine.Models
import com.clearcap.session.CaptureService
import com.clearcap.session.SessionBus
import java.util.Calendar

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val st by SessionBus.state.collectAsState()
    val cfg by ctx.app.settings.state.collectAsState()
    val installed by ctx.app.models.installed.collectAsState()
    val all by ctx.app.store.all.collectAsState()
    val accSpec = Models.accurate.firstOrNull { it.id == cfg.accurateModelId } ?: Models.parakeet600
    val accurateLive = cfg.liveMode == LiveMode.ACCURATE
    val liveReady = if (accurateLive) accSpec.id in installed && Models.vad.id in installed else Models.live.id in installed
    val setupText = if (accurateLive) "${accSpec.label} (${accSpec.downloadMb} MB) and the speech detector (1 MB)" else "the live model (${Models.live.downloadMb} MB)"
    val busy = st.running || st.progress != null

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) CaptureService.start(ctx, cfg.source, r.resultCode, data)
    }
    fun launchProjection() {
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection.launch(mpm.createScreenCaptureIntent())
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) {
            if (cfg.source == Source.MIC) CaptureService.start(ctx, cfg.source) else launchProjection()
        }
    }
    fun begin() {
        val need = buildList {
            // Android requires RECORD_AUDIO even to capture system audio.
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        when {
            need.isNotEmpty() -> perms.launch(need.toTypedArray())
            cfg.source == Source.MIC -> CaptureService.start(ctx, cfg.source)
            else -> launchProjection()
        }
    }

    val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val today = all.filter { it.createdAt >= dayStart }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Clearcap", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Live captions for any audio", style = MaterialTheme.typography.bodyMedium, color = t.muted)
                }
                if (st.running) Text("REC ${fmtClock(st.elapsedMs)}", color = t.danger, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${today.sumOf { it.words }}", "words today", Modifier.weight(1f))
                StatTile("${all.size}", "recordings", Modifier.weight(1f))
                StatTile(if (liveReady) "Ready" else "Setup", "live engine", Modifier.weight(1f))
            }
        }
        item {
            Panel {
                Segmented(Source.values().map { it.label }, cfg.source.ordinal, { i ->
                    if (!busy) ctx.app.settings.update { it.copy(source = Source.values()[i]) }
                })
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordButton(recording = st.running, enabled = liveReady && (st.running || !busy)) {
                        if (st.running) CaptureService.stop(ctx) else begin()
                    }
                    AudioBars(st.level, st.running, Modifier.padding(top = 14.dp))
                    Text(
                        when {
                            st.running -> "Listening. Tap to stop and save."
                            st.progress != null -> "Making the accurate transcript..."
                            liveReady && accurateLive -> "Tap to start. Sentences appear a few seconds after they are spoken."
                            liveReady -> "Tap to start captions"
                            else -> "Download the models in Settings to start"
                        },
                        color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
        if (!liveReady) item {
            Panel {
                Text("Set up the live engine", style = MaterialTheme.typography.titleMedium)
                Text("Captions run on this phone, offline. This needs $setupText, downloaded only when you tap Download in Settings.",
                    color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = onSettings) { Text("Open Settings") }
            }
        }
        st.error?.let { e -> item { Banner(e, true) } }
        st.status?.let { s -> item { Banner(s, false) } }
        if (st.running && st.behindMs > 5_000) item {
            Banner("Captions are about ${st.behindMs / 1000} s behind the audio and audio may be dropped. Close other apps, or lower the video speed.", true)
        }
        st.progress?.let { p ->
            item {
                Panel {
                    Text("Accurate transcript", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    Text(st.progressLabel, color = t.muted, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { CaptureService.skipAccurate(ctx) }) { Text("Skip and keep the live transcript") }
                }
            }
        }
        if (st.running || st.lines.isNotEmpty() || st.partial.isNotEmpty()) item {
            Panel {
                Text("Live captions", style = MaterialTheme.typography.labelLarge, color = t.muted)
                val shown = st.lines.takeLast(8)
                Text(
                    buildString { shown.forEach { append(it).append(' ') } }.trim().ifEmpty { if (st.partial.isEmpty()) "Waiting for speech..." else "" },
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (st.partial.isNotEmpty()) Text(st.partial, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (!st.running && st.progress == null) st.savedId?.let { id ->
            item { OutlinedButton(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth()) { Text("Open saved transcript") } }
        }
    }
}
