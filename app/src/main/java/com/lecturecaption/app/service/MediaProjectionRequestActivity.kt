package com.lecturecaption.app.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Shows the system "start capturing?" dialog FIRST. Only after the user accepts do we start the
 * foreground service (Android 14+ refuses a mediaProjection foreground service that starts
 * before consent). The consent result + session settings are handed to the service in one intent.
 */
class MediaProjectionRequestActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            TranscriptionForegroundService.startWithProjection(
                context = this,
                resultCode = result.resultCode,
                projectionData = data,
                language = intent.getStringExtra(TranscriptionForegroundService.EXTRA_LANGUAGE) ?: "en-US",
                engine = intent.getStringExtra(TranscriptionForegroundService.EXTRA_ENGINE) ?: "VOSK",
                title = intent.getStringExtra(TranscriptionForegroundService.EXTRA_TITLE) ?: "Untitled Lecture",
                speed = intent.getFloatExtra(TranscriptionForegroundService.EXTRA_SPEED, 1f),
                includeMic = intent.getBooleanExtra(TranscriptionForegroundService.EXTRA_INCLUDE_MIC, false)
            )
        } else {
            TranscriptionForegroundService.reportError(
                "Screen/audio capture permission was denied. Tap START again and choose \"Start now\"."
            )
            Toast.makeText(this, "Capture permission denied", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(pm.createScreenCaptureIntent())
        }
    }

    companion object {
        fun createIntent(context: Context, language: String, engine: String, title: String, speed: Float, includeMic: Boolean): Intent =
            Intent(context, MediaProjectionRequestActivity::class.java).apply {
                putExtra(TranscriptionForegroundService.EXTRA_LANGUAGE, language)
                putExtra(TranscriptionForegroundService.EXTRA_ENGINE, engine)
                putExtra(TranscriptionForegroundService.EXTRA_TITLE, title)
                putExtra(TranscriptionForegroundService.EXTRA_SPEED, speed)
                putExtra(TranscriptionForegroundService.EXTRA_INCLUDE_MIC, includeMic)
            }
    }
}
