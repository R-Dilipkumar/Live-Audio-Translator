package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.ui.AudioTranslatorViewModel
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AudioTranslatorViewModel by viewModels()

    // Activity result launcher for MediaProjection permission dialog
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startInternalCapture(result.resultCode, result.data!!)
        } else {
            viewModel.showError("Audio capture permission was denied or cancelled.")
        }
    }

    // Permission launcher for RECORD_AUDIO and POST_NOTIFICATIONS
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            launchScreenCaptureIntent()
        } else {
            viewModel.showError("Audio recording permission is required to capture sound.")
            Toast.makeText(this, "Permission denied: Audio Recording", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for SYSTEM_ALERT_WINDOW (Floating Window overlay)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            viewModel.startFloatingOverlay()
            Toast.makeText(this, "Floating Subtitle Window enabled", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.showError("Floating window overlay permission was not granted.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestCapturePermission = { checkAndRequestPermissions() },
                    onRequestOverlayPermission = { requestFloatingOverlay() }
                )
            }
        }
    }

    fun requestFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // Requirement 2: Show explanatory snackbar and trigger explicit Intent with package URI
                val explanation = "Please grant 'Display over other apps' to float subtitles over videos and games."
                Toast.makeText(this, explanation, Toast.LENGTH_LONG).show()
                viewModel.showError(explanation)

                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    overlayPermissionLauncher.launch(fallbackIntent)
                }
            } else {
                viewModel.startFloatingOverlay()
                Toast.makeText(this, "Floating Subtitle Window active", Toast.LENGTH_SHORT).show()
            }
        } else {
            viewModel.startFloatingOverlay()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            launchScreenCaptureIntent()
        }
    }

    private fun launchScreenCaptureIntent() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager != null) {
            try {
                val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                mediaProjectionLauncher.launch(captureIntent)
            } catch (e: Exception) {
                viewModel.showError("Failed to initiate screen/audio capture: ${e.localizedMessage}")
            }
        } else {
            viewModel.showError("MediaProjection service unavailable on this device.")
        }
    }
}
