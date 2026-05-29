package com.asitsounds.android.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.asitsounds.android.MainActivity

/**
 * Quick Settings tile that opens the app's Transcribe tab in one tap.
 *
 * The tile is added by the user via *Quick Settings → Edit → drag*. There's
 * no programmatic install — it shows up in the picker as soon as the app is
 * installed. Phase 3+ can graduate this to "start live mic STT" once that
 * flow is solid.
 */
class TranscribeQuickTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Transcribe"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Launch the main activity. On Android 14+ we use
        // `startActivityAndCollapse(PendingIntent)`; older API uses Intent.
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
