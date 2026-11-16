package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import uk.co.jwgardenservices.crm.R

/**
 * The dialling tone: a bundled UK ringback cadence played through
 * [MediaPlayer] on a loop, started the instant the service receives the
 * call intent so the wait for the speech models is masked.
 *
 * The synthesised [VoiceRingback] remains as a fallback for the unlikely
 * case the bundled resource cannot be opened.
 */
class RingbackPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private val fallback = VoiceRingback()

    @Volatile
    private var playing = false

    @Synchronized
    fun start() {
        if (playing) return
        playing = true
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
                runCatching { if (playing) prepared.start() }
            }
            media.prepareAsync()
            player = media
        }.onFailure {
            player = null
            if (playing) fallback.start()
        }
    }

    @Synchronized
    fun stop() {
        playing = false
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        fallback.stop()
    }
}
