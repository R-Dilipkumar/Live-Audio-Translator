package com.example.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.data.SettingsRepository
import com.example.overlay.FloatingSubtitleService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            lifecycleScope.launch {
                // Same as Start in the app: open the floating window only if "Open subtitles
                // when starting" is on (and drawing over apps is allowed)
                val openWindow = try {
                    SettingsRepository(this@MediaProjectionTrampolineActivity).settingsFlow.first().isAutoOverlayEnabled
                } catch (e: Exception) {
                    true
                }
                // Window first: if the capture fails right away, the service can close it again
                if (openWindow && android.provider.Settings.canDrawOverlays(this@MediaProjectionTrampolineActivity)) {
                    val overlayIntent = Intent(this@MediaProjectionTrampolineActivity, FloatingSubtitleService::class.java).apply {
                        action = FloatingSubtitleService.ACTION_START_OVERLAY
                    }
                    startService(overlayIntent)
                }
                ContextCompat.startForegroundService(this@MediaProjectionTrampolineActivity, captureIntent)
                Toast.makeText(this@MediaProjectionTrampolineActivity, "Live Subtitles started from Quick Settings", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            Toast.makeText(this, "Live Subtitles permission cancelled", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phone-audio capture also needs the record-audio permission. The app asks for it on
        // Start; without it the capture could not start, so send the user there instead.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow audio recording first: press Start in LiveSub once", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

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
