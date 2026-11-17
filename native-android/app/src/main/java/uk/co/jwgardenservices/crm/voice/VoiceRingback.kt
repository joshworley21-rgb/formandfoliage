package uk.co.jwgardenservices.crm.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * A looping UK ringback tone for the dialling state.
 *
 * 400 Hz + 450 Hz in the familiar 0.4 s on / 0.2 s off / 0.4 s on / 2 s off
 * cadence. Generating it here keeps the call independent of ringtone files,
 * and a small writer thread costs nothing while the speech model loads.
 */
class VoiceRingback {

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var playing = false

    @Synchronized
    fun start(): Boolean {
        if (playing) return true
        val pcm = buildPcm()
        val player = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Same route as the assistant's speech. Sonification
                        // follows the system stream, which Samsung can mute
                        // even with media volume up — that is why the dial
                        // tone was silent while replies were audible.
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: run {
            Log.w("Ringback", "AudioTrack could not be created")
            return false
        }

        playing = true
        track = player
        runCatching { player.setVolume(1f) }
        if (runCatching { player.play() }.isFailure) {
            Log.w("Ringback", "AudioTrack refused to play")
            playing = false
            track = null
            runCatching { player.release() }
            return false
        }
        thread = Thread({
            // Write the 3 s cadence again and again until the call connects.
            while (playing) {
                if (player.write(pcm, 0, pcm.size) <= 0) {
                    Log.w("Ringback", "AudioTrack write stopped")
                    break
                }
            }
        }, "ringback").apply { start() }
        return true
    }

    @Synchronized
    fun stop() {
        if (!playing && track == null) return
        playing = false
        val player = track
        track = null
        // stop() unblocks a writer waiting for buffer space; join it before
        // releasing the native track, or write() can touch freed memory.
        runCatching { player?.stop() }
        thread?.let { runCatching { it.join(500) } }
        thread = null
        runCatching { player?.release() }
    }

    private fun buildPcm(): ShortArray {
        val samples = ShortArray((SAMPLE_RATE * CADENCE_SECONDS).toInt())
        val cycleOn = BURST_SECONDS
        val cycleOff = BURST_SECONDS + GAP_SECONDS
        val secondOn = BURST_SECONDS + GAP_SECONDS + BURST_SECONDS
        samples.indices.forEach { index ->
            val time = index.toDouble() / SAMPLE_RATE
            val cycle = time % CADENCE_SECONDS
            val ringing = cycle < cycleOn || (cycle >= cycleOff && cycle < secondOn)
            if (!ringing) return@forEach
            // 400 Hz + 450 Hz is the UK ringback pair. The short fade stops
            // the burst edges clicking on phone speakers.
            val burstStart = if (cycle < cycleOn) 0.0 else cycleOff
            val burstEnd = if (cycle < cycleOn) cycleOn else secondOn
            val intoBurst = cycle - burstStart
            val left = burstEnd - cycle
            val fade = minOf(intoBurst, left, 0.01) / 0.01
            val tone = (sin(2 * PI * 400 * time) + sin(2 * PI * 450 * time)) * 0.18 * fade
            samples[index] = (tone * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    private companion object {
        const val SAMPLE_RATE = 8_000
        const val CADENCE_SECONDS = 3.0
        const val BURST_SECONDS = 0.4
        const val GAP_SECONDS = 0.2
    }
}
