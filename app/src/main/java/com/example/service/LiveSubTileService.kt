package com.example.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.overlay.FloatingSubtitleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LiveSubTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
        // Collect live running state
        serviceScope.launch {
            AudioCaptureService.isServiceRunning.collect { isRunning ->
                val tile = qsTile ?: return@collect
                tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = if (isRunning) "Subtitles: Active" else "Live Subtitles"
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        serviceScope.cancel()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = AudioCaptureService.isServiceRunning.value

        if (isRunning) {
            // Stop active capture and dismiss floating overlay
            val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP_CAPTURE
            }
            startService(stopIntent)

            val stopOverlayIntent = Intent(this, FloatingSubtitleService::class.java).apply {
                action = FloatingSubtitleService.ACTION_STOP_OVERLAY
            }
            startService(stopOverlayIntent)

            val tile = qsTile
            if (tile != null) {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Live Subtitles"
                tile.updateTile()
            }
        } else {
            // Android 14+ requires an Activity to request MediaProjection token.
            // Launch transparent trampoline Activity!
            val trampolineIntent = Intent(this, MediaProjectionTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    trampolineIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(trampolineIntent)
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = AudioCaptureService.isServiceRunning.value
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isRunning) "Subtitles: Active" else "Live Subtitles"
        tile.updateTile()
    }
}
