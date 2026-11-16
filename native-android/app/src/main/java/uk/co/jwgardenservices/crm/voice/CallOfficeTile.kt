package uk.co.jwgardenservices.crm.voice

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * The "Call office" quick-settings tile: one swipe from the lock screen and
 * one tap starts the assistant call — no unlocking, no finding the app, no
 * touching the screen with gloves on.
 *
 * Starting the service is all the tile does; the service's own onStartCommand
 * either opens a fresh call or resumes the running one, so the tile behaves
 * correctly in both states. Note that Android 14 can hold back microphone
 * access to a service started from the lock screen until the phone is
 * unlocked; the call still connects and speaks, and the mic opens as soon as
 * the phone is unlocked.
 */
class CallOfficeTile : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AssistantService::class.java),
            )
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (AssistantService.callActiveNow) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
