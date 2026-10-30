package com.clearcap.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.data.LiveMode
import com.clearcap.engine.ModelKind
import com.clearcap.engine.ModelSpec
import com.clearcap.engine.Models

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val t = LocalTokens.current
    val s by ctx.app.settings.state.collectAsState()
    val hasKey by ctx.app.keys.hasKey.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    val set = ctx.app.settings::update

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        Text("Models", style = MaterialTheme.typography.titleMedium)
        Text("Everything runs on this phone. A model is downloaded only when you tap Download; a stopped download resumes where it left off.", color = t.muted, style = MaterialTheme.typography.bodyMedium)
        Models.all.forEach { ModelCard(it) }

        Panel {
            Text("Live captions", style = MaterialTheme.typography.titleMedium)
            Segmented(LiveMode.values().map { it.label }, s.liveMode.ordinal, { i -> set { it.copy(liveMode = LiveMode.values()[i]) } },
                Modifier.padding(top = 8.dp))
            Text(
                if (s.liveMode == LiveMode.ACCURATE) "Accurate: each sentence is transcribed by the model chosen below, a few seconds after it is spoken. Far fewer wrong words; needs the model and the speech detector."
                else "Fast: words appear instantly from a small streaming model. Much less accurate on fast, noisy or musical audio.",
                color = t.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp),
            )
        }

        Panel {
            Text(if (s.liveMode == LiveMode.ACCURATE) "Model" else "Accurate transcript (after you stop)", style = MaterialTheme.typography.titleMedium)
            if (s.liveMode == LiveMode.FAST) {
                SwitchRow("Re-transcribe when I stop, and save that version", s.accurate) { v -> set { it.copy(accurate = v) } }
                Text("Runs after you stop, never during recording, so captions stay real time.", color = t.muted, style = MaterialTheme.typography.labelMedium)
            }
            Text("Model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            val ids = Models.accurate.map { it.id }
            Segmented(listOf("Parakeet 600M", "Parakeet 110M", "Whisper base", "Whisper small"), ids.indexOf(s.accurateModelId).coerceAtLeast(0), { i -> set { it.copy(accurateModelId = ids[i]) } })
            Text("Whisper language", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            val langs = listOf("auto", "en", "hi")
            Segmented(listOf("Auto", "English", "Hindi"), langs.indexOf(s.language).coerceAtLeast(0), { i -> set { it.copy(language = langs[i]) } })
            Text("Parakeet is English only. For Hindi or mixed speech choose a Whisper model (Accurate live captions then support Hindi too). Fast captions are English only.", color = t.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        }

        Panel {
            Text("Audio", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Gentle auto-gain (never boosts silence or noise)", s.autoGain) { v -> set { it.copy(autoGain = v) } }
            SwitchRow("Floating caption bar", s.overlay) { v -> set { it.copy(overlay = v) } }
            if (s.overlay && !Settings.canDrawOverlays(ctx)) OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Allow display over other apps") }
        }

        Panel {
            Text("AI notes (optional)", style = MaterialTheme.typography.titleMedium)
            Text(if (hasKey) "A Gemini key is saved, encrypted on this phone." else "Add your own Gemini key to make study notes from a transcript. Nothing is sent unless you tap the button on a transcript.",
                color = t.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
            OutlinedTextField(keyInput, { keyInput = it }, singleLine = true, label = { Text("Gemini API key") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { ctx.app.keys.set(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("Save key") }
                OutlinedButton(onClick = { ctx.app.keys.clear(); keyInput = "" }, enabled = hasKey) { Text("Remove key") }
            }
            OutlinedTextField(s.geminiModel, { v -> set { it.copy(geminiModel = v.trim()) } }, singleLine = true, label = { Text("Gemini model") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }

        Panel {
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text(PRIVACY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        Text("Clearcap 1.0. Design inspired by VoiceFlow and OpenWhispr (see NOTICE).", color = t.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 16.dp))
    }
}

private const val PRIVACY =
    "Audio is processed in memory only and is never saved: speech is held in RAM for the accurate pass, then discarded. " +
        "Only text transcripts are stored, in private app storage, and are excluded from Android backups.\n\n" +
        "What can leave the phone: (1) model downloads, only when you tap Download (github.com), which receive an ordinary web request; " +
        "(2) AI notes, only when you tap the button: the transcript text goes to Google Gemini with your own key.\n\n" +
        "No analytics, no ads, no accounts, no crash reporting. Your Gemini key is stored encrypted and can be removed here."

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun ModelCard(m: ModelSpec) {
    val t = LocalTokens.current
    val models = LocalContext.current.app.models
    val installed by models.installed.collectAsState()
    val dl by models.downloads.collectAsState()
    val st = dl[m.id]
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(m.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(m.bestFor, style = MaterialTheme.typography.bodySmall, color = t.muted, modifier = Modifier.padding(vertical = 4.dp))
                if (m.kind != ModelKind.VAD) {
                    Dots("Speed", m.speed)
                    Dots("Accuracy", m.accuracy)
                }
                Text(
                    when {
                        m.id in installed -> "Installed"
                        st?.error != null -> "Failed: ${st.error}"
                        st != null -> "${st.phase} ${(st.progress * 100).toInt()}%"
                        else -> "About ${m.downloadMb} MB"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (st?.error != null) t.danger else if (m.id in installed) t.accent else t.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            when {
                m.id in installed -> TextButton(onClick = { models.delete(m) }) { Text("Delete") }
                st != null && st.error == null -> TextButton(onClick = { models.cancel(m) }) { Text("Pause") }
                else -> Button(onClick = { models.download(m) }) { Text(if (st?.error != null) "Retry" else "Download") }
            }
        }
        if (st != null && st.error == null) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
