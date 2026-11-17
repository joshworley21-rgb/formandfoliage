package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import uk.co.jwgardenservices.crm.R
import uk.co.jwgardenservices.crm.core.CrashLog

/**
 * The dialling tone: a bundled UK ringback cadence played through
 * [MediaPlayer] on a loop, started the instant the service receives the
 * call intent so the wait for the speech models is masked.
 *
 * MediaPlayer can fail asynchronously — an unsupported file, a device with no
 * free audio track — and a silent prepareAsync would leave the caller staring
 * at "Calling…" in silence. So there are two guards: an error listener and a
 * short watchdog. Either one hands over to the synthesised [VoiceRingback],
 * which needs no file and always starts instantly.
 */
class RingbackPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private val fallback = VoiceRingback()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var playing = false

    @Volatile
    private var fellBack = false

    private val watchdog = Runnable {
        if (!playing || fellBack) return@Runnable
        val started = runCatching { player?.isPlaying == true }.getOrDefault(false)
        if (!started) {
            CrashLog.note(context, "Ringback", "media player did not start; using synth")
            useFallback()
        }
    }

    @Synchronized
    fun start() {
        if (playing) return
        playing = true
        fellBack = false
        runCatching {
            val media = MediaPlayer()
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            context.resources.openRawResourceFd(R.raw.ringback).use { file ->
                media.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            media.isLooping = true
            media.setOnPreparedListener { prepared ->
                if (!playing) return@setOnPreparedListener
                runCatching { prepared.start() }
                    .onSuccess { CrashLog.note(context, "Ringback", "media ringback started") }
                    .onFailure { useFallback() }
            }
            media.setOnErrorListener { _, what, extra ->
                CrashLog.note(context, "Ringback", "media ringback error $what/$extra")
                useFallback()
                true
            }
            media.prepareAsync()
            player = media
            // WAV looping is not supported by every vendor's MediaPlayer. If
            // nothing is audible within 700 ms, the tone is synthesised.
            handler.postDelayed(watchdog, 700)
        }.onFailure {
            player = null
            useFallback()
        }
    }

    @Synchronized
    fun stop() {
        playing = false
        handler.removeCallbacks(watchdog)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        fallback.stop()
        fellBack = false
    }

    private fun useFallback() {
        if (fellBack || !playing) return
        fellBack = true
        handler.removeCallbacks(watchdog)
        runCatching { player?.release() }
        player = null
        fallback.start()
    }
}
