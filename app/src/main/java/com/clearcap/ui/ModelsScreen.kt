package com.clearcap.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.models.DeviceInfo
import com.clearcap.models.DeviceInfoReader
import com.clearcap.models.DeviceProfile
import com.clearcap.models.DownloadState
import com.clearcap.models.FitLevel
import com.clearcap.models.ModelCatalog
import com.clearcap.models.ModelInfo
import com.clearcap.models.ModelKind
import com.clearcap.pipeline.EngineRouter
import com.clearcap.pipeline.LiveMode
import com.clearcap.session.SessionBus
import com.clearcap.transcript.LanguageMode
import com.clearcap.work.ModelDownloads

@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val t = LocalTokens.current
    val cfg by app.settings.state.collectAsState()
    val installed by app.models.installed.collectAsState()
    val downloads by app.models.downloads.collectAsState()
    val busy = SessionBus.state.collectAsState().value.busy
    val device = remember(installed, downloads.size) { DeviceInfoReader.read(ctx) }
    var confirmDelete by remember { mutableStateOf<ModelInfo?>(null) }
    var verifyText by remember { mutableStateOf<String?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Models and languages", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DevicePanel(device)

            Panel {
                Text("Language", style = MaterialTheme.typography.titleMedium)
                val langs = LanguageMode.values().toList()
                Segmented(listOf("Auto", "English", "Hindi", "Hinglish"), langs.indexOf(cfg.language), { i -> if (!busy) app.settings.update { it.copy(language = langs[i]) } }, Modifier.padding(top = 8.dp))
                Text(
                    "Captions are written in the language that is spoken. Nothing is translated. " +
                        "Hinglish is decoded as Hindi by Whisper, so English words inside a Hindi sentence may come out in Devanagari or in Roman letters. " +
                        "Accuracy for Hindi and Hinglish has not been measured on your speech; check the results before relying on them.",
                    style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 8.dp),
                )
            }

            Panel {
                Text("Offline readiness", style = MaterialTheme.typography.titleMedium)
                LanguageMode.values().forEach { lang ->
                    val r = EngineRouter.route(lang, installed, cfg.preferredWhisperId, device)
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(lang.label, style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (r.mode == LiveMode.NONE) "Not ready. Needs: ${r.missing.joinToString { it.label }}"
                            else "Ready without internet. " + (if (r.mode == LiveMode.STREAMING) "Streaming, text appears as people speak. " else "Near-live, text appears a few seconds after each phrase. ") + "Engine: ${r.liveModel?.label}.",
                            style = MaterialTheme.typography.bodySmall, color = if (r.mode == LiveMode.NONE) t.danger else t.muted,
                        )
                    }
                }
            }

            Panel {
                Text("Recognition profile (Hindi, Hinglish, auto)", style = MaterialTheme.typography.titleMedium)
                Text("Chooses the Whisper model used when streaming is not available. Bigger is more accurate but slower and needs more memory.", style = MaterialTheme.typography.bodySmall, color = t.muted)
                val auto = DeviceProfile.recommendedWhisper(device)
                ProfileRow("Automatic (recommended: ${auto.label})", null, cfg.preferredWhisperId == null, busy) { app.settings.update { it.copy(preferredWhisperId = null) } }
                ModelCatalog.whisper.forEach { m ->
                    val fit = DeviceProfile.fit(m, device)
                    ProfileRow("${m.label}: ${fit.message}", fit.level, cfg.preferredWhisperId == m.id, busy) { app.settings.update { it.copy(preferredWhisperId = m.id) } }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow("Second pass after recording", "Off by default. Re-checks the finished speech with a stronger installed model and keeps your original text so you can restore it. On the only clips measured so far (3 English read-speech clips, desktop), the second pass did NOT beat the live English model, so turn it on and compare on your own recordings before trusting it.", cfg.refine) { v -> app.settings.update { it.copy(refine = v) } }
            }

            SwitchRow("Download only on Wi-Fi", "Models are 1 to 640 MB. Nothing downloads until you tap Download.", cfg.wifiOnlyDownloads) { v -> app.settings.update { it.copy(wifiOnlyDownloads = v) } }

            Text("Models", style = MaterialTheme.typography.titleMedium)
            ModelCatalog.all.forEach { m ->
                ModelCard(
                    m, installed = m.id in installed, dl = downloads[m.id], device = device,
                    onDownload = { ModelDownloads.enqueue(ctx, m, cfg.wifiOnlyDownloads) },
                    onCancel = { ModelDownloads.cancel(ctx, m) },
                    onDelete = { if (busy) Toast.makeText(ctx, "Stop recording before deleting a model", Toast.LENGTH_SHORT).show() else confirmDelete = m },
                    onVerify = { val v = app.models.verify(m); verifyText = if (v.ok) "${m.label}: all ${m.files.size} files are present with the exact expected sizes. (The download checksum was verified when it was installed.)" else "${m.label}: ${v.problems.joinToString("; ")}. Delete and download it again." },
                )
            }
            Text("The speed and accuracy dots and the memory figures are rough guides, not measurements. Real decoding speed on this phone appears under Settings > Diagnostics after a recording.", style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
    }
    confirmDelete?.let { m -> ConfirmDialog("Delete ${m.label}?", "Frees about ${m.installedMb} MB. You can download it again later.", "Delete", danger = true, onConfirm = { app.models.delete(m) }, onDismiss = { confirmDelete = null }) }
    verifyText?.let { AlertDialog(onDismissRequest = { verifyText = null }, title = { Text("Model check") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { verifyText = null }) { Text("OK") } }) }
}

@Composable
private fun DevicePanel(d: DeviceInfo) {
    val t = LocalTokens.current
    Panel {
        Text("This phone", style = MaterialTheme.typography.titleMedium)
        val tier = DeviceProfile.tier(d.totalRamMb)
        Text("Memory: ${d.totalRamMb} MB total, ${d.availRamMb} MB free now (${tier.name.lowercase()} class)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Text("Free storage: ${d.freeStorageMb} MB", style = MaterialTheme.typography.bodyMedium)
        Text("CPU: ${d.abis.firstOrNull() ?: "unknown"}, ${d.cores} cores · Android API ${d.sdk}", style = MaterialTheme.typography.bodyMedium)
        if (!DeviceProfile.abiSupported(d.abis)) Banner("This CPU is not supported by the speech library (needs arm64-v8a or armeabi-v7a). Captions cannot run on this device.", true)
        Text("Estimated class from installed memory only.", style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

@Composable
private fun ProfileRow(label: String, level: FitLevel?, selected: Boolean, disabled: Boolean, onSelect: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onSelect, enabled = !disabled)
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (level == FitLevel.BLOCK) t.danger else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ModelCard(m: ModelInfo, installed: Boolean, dl: DownloadState?, device: DeviceInfo, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onVerify: () -> Unit) {
    val t = LocalTokens.current
    val fit = DeviceProfile.fit(m, device)
    val storage = DeviceProfile.storageFit(m, device)
    val blocked = !installed && (fit.level == FitLevel.BLOCK || storage.level == FitLevel.BLOCK)
    Panel {
        Text(m.label, style = MaterialTheme.typography.titleMedium)
        Text(m.bestFor, style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(top = 2.dp))
        Text(
            "Languages: ${if (m.multilingual) "many, including Hindi and English (accuracy per language has not been measured)" else m.languages.joinToString()} · " +
                "${m.kind.label()} · download ${m.downloadMb} MB · ${m.installedMb} MB on disk · needs about ${m.minTotalRamMb} MB RAM (estimate)",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) { Dots("Speed", m.relativeSpeed); Dots("Accuracy", m.relativeAccuracy) }
        Text("License: ${m.license}" + (m.attribution?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = t.muted, modifier = Modifier.padding(top = 4.dp))
        if (!installed) {
            Text(fit.message, style = MaterialTheme.typography.bodySmall, color = if (fit.level == FitLevel.BLOCK) t.danger else t.muted, modifier = Modifier.padding(top = 4.dp))
            if (storage.level == FitLevel.BLOCK) Text(storage.message, style = MaterialTheme.typography.bodySmall, color = t.danger)
        }
        when {
            dl != null && dl.error == null -> {
                LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${dl.phase} ${(dl.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = t.muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text("Installed", color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(onClick = onVerify) { Text("Verify") }
                TextButton(onClick = onDelete) { Text("Delete", color = t.danger) }
            }
            else -> {
                dl?.error?.let { Banner(it, true) }
                Button(onClick = onDownload, enabled = !blocked, modifier = Modifier.padding(top = 8.dp)) { Text(if (dl?.error != null) "Try again" else "Download ${m.downloadMb} MB") }
            }
        }
    }
}

private fun ModelKind.label() = when (this) { ModelKind.LIVE_STREAMING -> "live streaming"; ModelKind.OFFLINE_ACCURATE -> "offline accurate"; ModelKind.VAD -> "speech detector" }
