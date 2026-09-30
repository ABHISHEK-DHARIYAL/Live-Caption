package com.lecturecaption.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lecturecaption.app.audio.AudioSource
import com.lecturecaption.app.ui.screens.HistoryScreen
import com.lecturecaption.app.ui.screens.MainScreen
import com.lecturecaption.app.ui.screens.PrivacyScreen
import com.lecturecaption.app.ui.screens.SettingsScreen
import com.lecturecaption.app.ui.screens.TranscriptViewerScreen
import com.lecturecaption.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONFIRM_STOP = "extra_confirm_stop"
    }

    /** Becomes true when the user taps the corner icon / notification: show "Is it done?". */
    private val confirmStopRequested = mutableStateOf(false)

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result observed via Settings.canDrawOverlays() when needed */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: if denied, the persistent notification simply won't show; capture still runs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("lecturecaption_prefs", MODE_PRIVATE)
        val introAlreadyShown = prefs.getBoolean("privacy_intro_shown", false)
        if (introAlreadyShown) requestRuntimePermissionsIfNeeded()
        handleIntent(intent)

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel()
                var showPrivacyIntro by remember { mutableStateOf(!introAlreadyShown) }

                if (showPrivacyIntro) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Before you start") },
                        text = {
                            Text(
                                "LectureCaption processes speech on your device.\n\n" +
                                    "• Your transcripts are stored locally on this phone.\n" +
                                    "• Downloaded speech models are stored locally.\n" +
                                    "• Raw audio is not saved.\n" +
                                    "• Audio is not uploaded to a server.\n" +
                                    "• System Audio mode captures only audio playing on this device (no microphone).\n" +
                                    "• Microphone mode captures both device audio and your microphone, mixed into one transcript.\n" +
                                    "• Some apps (e.g. DRM-protected players) can block audio capture; " +
                                    "the app will tell you if this happens rather than pretending it worked.\n" +
                                    "• Generate Notes is the one optional exception: if you choose to use it and " +
                                    "provide your own Gemini API key, that transcript's text is sent to Google's " +
                                    "Gemini API. This never happens unless you tap Generate Notes yourself."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                prefs.edit().putBoolean("privacy_intro_shown", true).apply()
                                showPrivacyIntro = false
                                requestRuntimePermissionsIfNeeded()
                            }) { Text("Got it") }
                        }
                    )
                }

                AppNavHost(
                    navController = navController,
                    viewModel = viewModel,
                    onRequestOverlayPermission = { requestOverlayPermissionIfNeeded() },
                    onRequestMicPermission = { onGranted -> requestMicPermission(onGranted) },
                    confirmStopRequested = confirmStopRequested.value,
                    onConfirmStopHandled = { confirmStopRequested.value = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONFIRM_STOP, false) == true) {
            confirmStopRequested.value = true
            intent.removeExtra(EXTRA_CONFIRM_STOP)
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> pendingMicCallback?.invoke(granted); pendingMicCallback = null }

    private var pendingMicCallback: ((Boolean) -> Unit)? = null

    private fun requestMicPermission(onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
        } else {
            pendingMicCallback = onResult
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    viewModel: MainViewModel,
    onRequestOverlayPermission: () -> Unit,
    onRequestMicPermission: (onResult: (Boolean) -> Unit) -> Unit,
    confirmStopRequested: Boolean,
    onConfirmStopHandled: () -> Unit
) {
    LaunchedEffect(confirmStopRequested) {
        if (confirmStopRequested) navController.popBackStack("main", inclusive = false)
    }
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                viewModel = viewModel,
                confirmStopRequested = confirmStopRequested,
                onConfirmStopHandled = onConfirmStopHandled,
                onOpenHistory = { navController.navigate("history") },
                onOpenSettings = { navController.navigate("settings") },
                onRequestOverlayPermission = onRequestOverlayPermission,
                onRequestMicPermission = onRequestMicPermission
            )
        }
        composable("history") {
            HistoryScreen(
                onOpenLecture = { id -> navController.navigate("transcript/$id") },
                onBack = { navController.popBackStack() }
            )
        }
        composable("transcript/{lectureId}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("lectureId")?.toLongOrNull() ?: return@composable
            TranscriptViewerScreen(lectureId = id, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenPrivacy = { navController.navigate("privacy") }
            )
        }
        composable("privacy") {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
    }
}
