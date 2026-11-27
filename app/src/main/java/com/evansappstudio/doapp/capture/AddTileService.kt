package com.evansappstudio.doapp.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import com.evansappstudio.doapp.MainActivity

/**
 * Quick Settings "Do: Add a reminder". Label and icon only, no state. On the lock
 * screen it asks for the unlock first, then opens Add and collapses the shade.
 */
class AddTileService : TileService() {
    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { openAdd() }
        } else {
            openAdd()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAdd() {
        val intent = MainActivity.addIntent(this, CaptureEntry.TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                REQUEST_ADD,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_ADD = 12
    }
}
