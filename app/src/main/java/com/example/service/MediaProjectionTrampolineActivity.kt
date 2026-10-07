package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.overlay.FloatingSubtitleService

/**
 * Transparent trampoline Activity to launch MediaProjection permission dialog
 * on behalf of Quick Settings Tile (required on Android 14+).
 */
class MediaProjectionTrampolineActivity : ComponentActivity() {

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start internal capture foreground service with granted MediaProjection token
            val captureIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START_INTERNAL_CAPTURE
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, captureIntent)

            // Start floating overlay window if permitted
            if (android.provider.Settings.canDrawOverlays(this)) {
                val overlayIntent = Intent(this, FloatingSubtitleService::class.java).apply {
                    action = FloatingSubtitleService.ACTION_START_OVERLAY
                }
                startService(overlayIntent)
            }
            Toast.makeText(this, "Live Subtitles started from Quick Settings", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Live Subtitles permission cancelled", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager != null) {
            try {
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to launch screen capture: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            Toast.makeText(this, "Media projection not available", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
