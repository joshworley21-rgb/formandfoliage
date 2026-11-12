package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrackOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.core.AppConfig

/**
 * The server-brain assistant over LiveKit on the VPS.
 *
 * Opening the call joins the shared assistant room; the phone publishes its
 * microphone — with hardware echo cancellation, noise suppression and
 * automatic gain control, so Whisper hears the voice and not the road or the
 * bot itself — and the bm_fable agent's reply audio plays through the room's
 * remote track. Transcripts still arrive over the bridge's JSON control
 * socket; LiveKit carries the audio.
 */
class LiveKitVoiceSession(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Not used here — transcripts ride the bridge socket. */
        fun onUserTranscript(text: String)

        /** Not used here — the agent's words ride the bridge socket. */
        fun onAssistantText(text: String)

        /** Connection is live; mic published; replies will play. */
        fun onConnected()

        /** LiveKit cannot be used; the caller should fall back offline. */
        fun onUnavailable(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var room: Room? = null
    private var running = false

    private val focusManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    fun start() {
        if (running) return
        running = true
        scope.launch {
            // Call mode: the bot's replies must be heard over the road.
            requestFocus()

            runCatching { LiveKit.init(context) }

            val room = LiveKit.create(
                context,
                RoomOptions(
                    audioTrackCaptureDefaults = LocalAudioTrackOptions(
                        echoCancellation = true,     // hardware AEC where the phone has it
                        noiseSuppression = true,     // road noise out of the transcription
                        autoGainControl = true,
                        highPassFilter = true,
                    ),
                ),
            )
            this@LiveKitVoiceSession.room = room

            launch {
                room.events.collect { event ->
                    when (event) {
                        is RoomEvent.Connected, is RoomEvent.Reconnected -> listener.onConnected()
                        is RoomEvent.FailedToConnect ->
                            if (running) listener.onUnavailable(
                                event.error.message ?: "LiveKit refused the connection",
                            )
                        is RoomEvent.Disconnected ->
                            if (running) listener.onUnavailable("LiveKit disconnected (${event.reason})")
                        else -> {}
                    }
                }
            }

            try {
                room.connect(AppConfig.livekitUrl, AppConfig.livekitToken)
            } catch (error: Exception) {
                if (running) listener.onUnavailable(error.message ?: "Could not reach LiveKit")
                return@launch
            }

            try {
                room.localParticipant.setMicrophoneEnabled(true)
            } catch (error: Exception) {
                if (running) listener.onUnavailable(error.message ?: "Microphone could not be published")
                return@launch
            }

            listener.onConnected()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        abandonFocus()
        room?.let { room ->
            runCatching { room.disconnect() }
            scope.launch {
                runCatching { room.release() }
            }
        }
        room = null
        scope.cancel()
    }

    /** The server does the talking in this mode. */
    fun speak(text: String) {}

    /** The server is always listening in this mode. */
    fun listen() {}

    /** Mute is the mic track's own enable flag — no unpublish churn. */
    fun setMuted(muted: Boolean) {
        scope.launch {
            runCatching { room?.localParticipant?.setMicrophoneEnabled(!muted) }
        }
    }

    private fun requestFocus() {
        val manager = focusManager ?: return
        focusRequest?.let { return }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        manager.requestAudioFocus(focusRequest!!)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusManager?.abandonAudioFocusRequest(request)
        focusRequest = null
    }
}
