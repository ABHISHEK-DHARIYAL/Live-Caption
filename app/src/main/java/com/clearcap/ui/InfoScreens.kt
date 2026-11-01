package com.clearcap.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.clearcap.app
import com.clearcap.models.DeviceInfoReader
import com.clearcap.session.CaptureService
import com.clearcap.session.SessionBus

@Composable
fun InfoScreen(title: String, body: String, onBack: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader(title, onBack)
        SelectionContainer { Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val st by SessionBus.state.collectAsState()
    val device = remember { DeviceInfoReader.read(ctx) }
    val installed by ctx.app.models.installed.collectAsState()
    val report = buildString {
        append(st.diagnostics.report())
        append("\nDevice: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${device.sdk}) · ABIs ${device.abis.joinToString()}\n")
        append("Memory: ${device.totalRamMb} MB total, ${device.availRamMb} MB free · storage free ${device.freeStorageMb} MB · cores ${device.cores}\n")
        append("Microphone permission: ${if (CaptureService.hasMic(ctx)) "granted" else "not granted"} · overlay permission: ${if (android.provider.Settings.canDrawOverlays(ctx)) "granted" else "not granted"}\n")
        append("Installed models: ${installed.sorted().joinToString().ifEmpty { "none" }}\n")
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Diagnostics", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Numbers and codes only. No audio and no transcript text are included, so this is safe to paste into a bug report. It shows the most recent recording since the app started.", style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.muted)
            Panel { SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) } }
            Button(onClick = { clipboard.setText(AnnotatedString(report)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }) { Text("Copy report") }
        }
    }
}
