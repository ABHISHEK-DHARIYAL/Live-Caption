package com.lecturecaption.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text(
                "Your lecture audio is processed on your device.\n" +
                    "Transcripts are stored locally.\n" +
                    "No lecture audio or transcript is uploaded automatically.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))
            Text("• System Audio mode does not use microphone audio, but Android still requires microphone permission to be granted for playback capture to work — this is an Android platform rule, not a choice made by this app.")
            Text("• Microphone mode captures both device audio and your microphone together, mixed into one transcript. System Audio mode captures only device audio.")
            Text("• Raw audio is discarded immediately after speech recognition — it is never saved by default.")
            Text("• No account, no analytics, no ads.")
            Text("• Speech models are downloaded only when you tap Download, and only over your network.")
            Text("• Delete All Data in Settings permanently removes every lecture, transcript, and model.")
            Spacer(Modifier.height(16.dp))
            Text("AI Notes is the one exception to \"nothing leaves your device\":", style = MaterialTheme.typography.titleSmall)
            Text("• Only happens if you tap \"Generate Notes\" on a transcript AND have entered your own Gemini API key.")
            Text("• When you do, that transcript's text is sent over HTTPS to Google's Gemini API to generate notes.")
            Text("• Your API key is stored encrypted on this device (Android Keystore) and is only ever sent to Google's own Gemini endpoint, never anywhere else.")
            Text("• If you never tap Generate Notes, this never happens.")
        }
    }
}
