package uk.co.jwgardenservices.crm.voice

import android.content.Context
import uk.co.jwgardenservices.crm.core.CrashLog

/**
 * The dialling tone.
 *
 * This used to play a bundled WAV through MediaPlayer, but Samsung (and
 * others) can report `isPlaying = true` while producing silence, so the log
 * said "ringback started" and the caller heard nothing. The synthesised
 * [VoiceRingback] — two tones on an AudioTrack we own — is not capable of
 * failing quietly, so it is now the only path.
 */
class RingbackPlayer(@Suppress("UNUSED_PARAMETER") private val context: Context) {

    private val synth = VoiceRingback()

    @Volatile
    private var playing = false

    @Synchronized
    fun start() {
        if (playing) return
        playing = true
        synth.start()
        CrashLog.note(context, "Ringback", "synth ringback started")
    }

    @Synchronized
    fun stop() {
        playing = false
        synth.stop()
    }
}
