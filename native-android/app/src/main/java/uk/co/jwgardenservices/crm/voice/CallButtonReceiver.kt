package uk.co.jwgardenservices.crm.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Starts a call from a double-press of the headset stem or the van's
 * hands-free button, without touching the phone.
 *
 * While a call is up, [AssistantService]'s MediaSession owns the button (a
 * double press ends the call), so this receiver stands down. A single press
 * is deliberately ignored: while music is playing elsewhere, one press is
 * someone else's play/pause.
 */
class CallButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        if (AssistantService.callActiveNow) return

        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        if (event.keyCode !in CALL_KEYS) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastPress <= DOUBLE_PRESS_MS) {
            lastPress = 0
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AssistantService::class.java),
                )
            }
        } else {
            lastPress = now
        }
    }

    private companion object {
        const val DOUBLE_PRESS_MS = 900L

        val CALL_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_CALL,
        )

        @Volatile
        var lastPress = 0L
    }
}
